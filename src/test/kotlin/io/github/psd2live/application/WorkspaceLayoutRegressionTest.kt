package io.github.psd2live.application

import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.project.*
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import kotlin.test.*

class WorkspaceLayoutRegressionTest {
    private fun operation(name: String, vararg fields: Pair<String, String>) =
        WorkspaceDocumentOperation(name, buildJsonObject { fields.forEach { (key, value) -> put(key, value) } })

    @Test fun deletingAReplacedLayerDoesNotBlockGeneration() = runBlocking {
        val document = WorkspaceMaterialEdits.addLinked(Characters.head(false), "head.psd")
        val model = WorkspacePreviewBuilder().build(document)
        val image = document.source.layers.single { it.id.raw == "hair" }.raster
        val replacement = LayerRaster(image.width, image.height, image.rgba.copyOf().also { bytes ->
            for (o in bytes.indices step 4) bytes[o] = (bytes[o].toInt() xor 64).toByte()
        })
        val replaced = WorkspaceDocumentEdits.replaceImage(document, model, "hair", replacement)
        assertContains(replaced.layout.generation.refreshLayerIds, "hair")
        val classified = WorkspaceDocumentEdits.apply(operation("layer_classify", "layer_id" to "hair", "role" to "objects"), replaced, model)
        val deleted = WorkspaceDocumentEdits.apply(operation("layer_delete", "layer_id" to "hair"), classified, model)
        assertFalse("hair" in deleted.layout.layerMaterials)
        assertFalse("hair" in deleted.layout.generation.pendingClassifications)
        assertFalse("hair" in deleted.layout.generation.refreshLayerIds)
        val generated = WorkspaceGenerationEdits.generate(deleted, model, null)
        assertEquals(listOf("face"), generated.source.layers.map { it.id.raw })
    }

    @Test fun classificationPatchesKeepPreviouslyPendingFields() = runBlocking {
        val document = Characters.head(false)
        val model = WorkspacePreviewBuilder().build(document)
        val first = WorkspaceDocumentEdits.apply(operation("layer_classify", "layer_id" to "hair", "role" to "objects"), document, model)
        val second = WorkspaceDocumentEdits.apply(operation("layer_classify", "layer_id" to "hair", "side" to "right"), first, model)
        val pending = assertNotNull(second.layout.generation.pendingClassifications["hair"])
        assertEquals(SemanticTag.OBJECTS, pending.tag)
        assertEquals(Side.RIGHT, pending.side)
        val generated = WorkspaceGenerationEdits.generate(second, model, null)
        assertEquals(pending, generated.layerOverrides["hair"])
    }

    @Test fun deletingAGeneratedLayerKeepsPendingLayersAndTheirMetadata() = runBlocking {
        val document = WorkspaceMaterialEdits.addLinked(Characters.withPlacedHat(), "figure.psd").let {
            it.copy(layout = it.layout.copy(layerTags = mapOf("hat" to setOf("prop"))))
        }
        val model = WorkspacePreviewBuilder().build(document)
        val deleted = WorkspaceDocumentEdits.apply(operation("layer_delete", "layer_id" to "back_hair"), document, model)
        assertContains(deleted.source.layers.map { it.id.raw }, "hat")
        assertContains(deleted.layout.generation.pendingLayerIds, "hat")
        assertEquals(setOf("prop"), deleted.layout.layerTags["hat"])
        assertEquals(document.layout.layerMaterials["hat"], deleted.layout.layerMaterials["hat"])
    }

    @Test fun typedImageReplacementUnlinksTheOldMaterial() = runBlocking {
        val document = WorkspaceMaterialEdits.addLinked(Characters.head(false), "head.psd")
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime({ document: WorkspaceDocument -> builder.build(document) })
        val before = runtime.install(runtime.state.value.state, "test", document, builder.build(document))
        val original = document.source.layers.single { it.id.raw == "face" }.raster
        val replacement = LayerRaster(original.width, original.height, original.rgba.copyOf().also { bytes ->
            for (o in bytes.indices step 4) if (bytes[o + 3].toInt() != 0) {
                bytes[o] = -1; bytes[o + 1] = 0; bytes[o + 2] = 0
            }
        })
        val replaced = WorkspaceTextureCommands(runtime).execute(before.projectId, before.state,
            WorkspaceTextureEdit.ReplaceImage("face", WorkspaceTextureImage.Raster(replacement)), MutationAuthor.USER).commit.capture
        assertNull(replaced.document.layout.layerMaterials["face"])
        // Editing the former material must leave the independent replacement alone.
        val adjusted = WorkspaceDocumentCommands(runtime).execute(replaced.projectId, replaced.state, "Adjust old material",
            listOf(WorkspaceDocumentOperation("material_adjust", buildJsonObject {
                put("id", document.layout.layerMaterials.getValue("face")); put("hue", 90)
            })), MutationAuthor.USER).capture.document
        assertContentEquals(replacement.rgba, adjusted.source.layers.single { it.id.raw == "face" }.raster.rgba)
        assertEquals(document.layout.layerMaterials["hair"], adjusted.layout.layerMaterials["hair"])
        val current = runtime.capture()
        val linked = WorkspaceTextureCommands(runtime).execute(current.projectId, current.state,
            WorkspaceTextureEdit.ReplaceImage("face", WorkspaceTextureImage.Material(document.layout.layerMaterials.getValue("hair"))),
            MutationAuthor.USER).commit.capture.document
        assertEquals(document.layout.layerMaterials["hair"], linked.layout.layerMaterials["face"])
    }
}
