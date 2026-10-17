package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Generation as an explicit step, and the edits of layers the generators have not read yet ([WorkspaceGeneration]).
 *
 * `rig_generate` hands the generators what waits for them: layers placed since the last generation join the
 * generation input, waiting classifications become the ones it reads, and the layers asked to refresh their geometry
 * are read from their current pixels. The candidate is then prepared as any edit of the generation input
 * ([WorkspacePreviewBuilder.normalizeMeshEdits] and the build): what the generators now make merges onto the user's
 * rig ([RigRegenerationCheckpoint]), so the user's edits stay and the new layers get generated meshes.
 *
 * A pending layer has no rig yet, so it is placed by its canvas rectangle and its pixels alone: moving and scaling set
 * the rectangle, mirroring flips its pixels, replacing swaps its raster, deleting removes it from the source.
 */
internal object WorkspaceGenerationEdits {
    const val GENERATE = "rig_generate"

    /** Operations on a pending layer, each with its own edit here or a document-level one. */
    val pendingLayerOperations = setOf(WorkspaceLayerEdits.DELETE, WorkspaceLayerTransform.OP, "layer_mesh_update")

    /** Operations a project not generated yet accepts besides those on its (pending) layers. */
    val ungeneratedOperations = setOf("settings_update")

    fun generate(document: WorkspaceDocument, model: RigPreviewModel, refresh: Set<String>?): WorkspaceDocument {
        val layout = document.layout
        val generation = layout.generation
        if (!generation.generated) {
            require(document.source.layers.any { it.id.raw !in document.deletedLayerIds && it.id.raw !in layout.referenceLayerIds }) {
                "Place a layer that is not a reference before generating the rig" }
            // The first generation reads every layer as it is now.
            return document.copy(layout = layout.copy(generation = WorkspaceGeneration.Generated),
                layerOverrides = document.layerOverrides + generation.pendingClassifications, generationSource = null, meshSource = null)
        }
        // The reference layers stay out of every generation.
        val pending = WorkspaceLayoutPreview.pendingIds(document) - layout.referenceLayerIds
        val refreshed = (refresh ?: generation.refreshLayerIds).toSet()
        refreshed.forEach { id ->
            require(document.source.layers.any { it.id.raw == id } && id !in document.deletedLayerIds) { "Layer not found: $id" }
            require(id !in pending && id !in layout.referenceLayerIds) { "Layer $id has not been generated yet" }
        }
        if (pending.isEmpty() && generation.pendingClassifications.isEmpty() && refreshed.isEmpty()) return document
        // Absent from the frozen generation input, a layer generates from its current pixels.
        val drop = pending + refreshed
        return document.copy(
            layerOverrides = document.layerOverrides + generation.pendingClassifications,
            generationSource = document.generationSource?.without(drop),
            meshSource = document.meshSource?.without(drop),
            layout = layout.copy(generation = generation.copy(pendingLayerIds = emptySet(), pendingClassifications = emptyMap(),
                refreshLayerIds = generation.refreshLayerIds - refreshed)),
        )
    }

    private fun SourceArt.without(ids: Set<String>): SourceArt =
        if (layers.none { it.id.raw in ids }) this else WorkspaceSourceArt(widthPx, heightPx, layers.filter { it.id.raw !in ids }, groups)

    /** The layers [operation] names. */
    fun targets(operation: WorkspaceDocumentOperation): Set<String> = buildSet {
        operation.request["layer_id"]?.jsonPrimitive?.contentOrNull?.let(::add)
        operation.request["layer_ids"]?.jsonArray?.forEach { add(it.jsonPrimitive.content) }
    }

    /** [operation] on pending layers of [document] - every layer it names is pending. */
    fun pendingEdit(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
        val request = operation.request
        return when (operation.operation) {
            WorkspaceLayerEdits.DELETE -> delete(document, request.getValue("layer_id").jsonPrimitive.content)
            WorkspaceLayerTransform.OP -> transform(document, request.getValue("layer_id").jsonPrimitive.content, WorkspaceLayerTransform.transform(request))
            // Mesh settings are document data the pending layer's preview reads.
            "layer_mesh_update" -> WorkspaceDocumentEdits.mesh(document, model, request.getValue("layer_id").jsonPrimitive.content,
                request["changes"]?.jsonObject, request["reset"]?.jsonPrimitive?.boolean ?: false)
            else -> throw WorkspaceNotGenerated("${operation.operation} needs the layer's rig; generate the rig first (rig_generate)")
        }
    }

    private fun layer(document: WorkspaceDocument, id: String): SourceLayer {
        require(id !in document.deletedLayerIds) { "Layer is deleted: $id" }
        return document.source.layers.singleOrNull { it.id.raw == id } ?: throw IllegalArgumentException("Layer not found: $id")
    }

    private fun withLayer(document: WorkspaceDocument, id: String, change: (WorkspaceSourceLayer) -> WorkspaceSourceLayer): WorkspaceDocument {
        val source = document.source
        return document.copy(source = WorkspaceSourceArt(source.widthPx, source.heightPx, source.layers.map { layer ->
            if (layer.id.raw != id) layer else change(WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer)
        }, source.groups))
    }

    /** [document] with pending layer [id] over [rect]: its integer bounds enclose the rectangle, its pixels are not resampled. */
    fun place(document: WorkspaceDocument, id: String, rect: LayerCanvasRect): WorkspaceDocument {
        val layer = layer(document, id)
        require(rect.width > 0f && rect.height > 0f && listOf(rect.left, rect.top, rect.width, rect.height).all(Float::isFinite)) {
            "Canvas rectangle needs a finite positive width and height" }
        if (layer.canvasRect() == rect && layer.transform.isIdentity) return document
        val left = floor(rect.left.toDouble()).toInt(); val top = floor(rect.top.toDouble()).toInt()
        val right = maxOf(left + 1, ceil(rect.right.toDouble() - 1e-4).toInt()); val bottom = maxOf(top + 1, ceil(rect.bottom.toDouble() - 1e-4).toInt())
        val bounds = LayerBounds(left, top, right - left, bottom - top)
        return withLayer(document, id) { it.copy(bounds = bounds, rect = rect.takeUnless { r -> r == LayerCanvasRect.of(bounds) }, layerTransform = null) }
    }

    /**
     * [document] with pending layer [id] moved by [delta]: a move and a scale set its rectangle, a mirror flips its
     * pixels. A turn needs the rig's mesh to carry it, so it waits until the layer is generated.
     */
    fun transform(document: WorkspaceDocument, id: String, delta: LayerTransform): WorkspaceDocument {
        if (delta.isIdentity) return document
        if (delta.b != 0f || delta.c != 0f || delta.a == 0f || delta.d == 0f)
            throw WorkspaceNotGenerated("A layer placed since the last generation can be moved, scaled and mirrored; turn it after generating the rig")
        val layer = layer(document, id)
        val rect = layer.canvasRect()
        val x0 = delta.x(rect.left, rect.top); val x1 = delta.x(rect.right, rect.top)
        val y0 = delta.y(rect.left, rect.top); val y1 = delta.y(rect.left, rect.bottom)
        val moved = place(document, id, LayerCanvasRect(minOf(x0, x1), minOf(y0, y1), kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)))
        if (delta.a > 0f && delta.d > 0f) return moved
        return withLayer(moved, id) { it.copy(raster = flipped(it.raster, horizontal = delta.a < 0f, vertical = delta.d < 0f)) }
    }

    private fun flipped(raster: LayerRaster, horizontal: Boolean, vertical: Boolean): LayerRaster {
        val w = raster.width; val h = raster.height
        val out = ByteArray(raster.rgba.size)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = if (horizontal) w - 1 - x else x; val sy = if (vertical) h - 1 - y else y
            System.arraycopy(raster.rgba, (sy * w + sx) * 4, out, (y * w + x) * 4, 4)
        }
        return LayerRaster(w, h, out)
    }

    /** [document] with pending layer [id] showing [raster] over its unchanged rectangle; nothing generated is frozen. */
    fun replace(document: WorkspaceDocument, id: String, raster: LayerRaster, fit: WorkspaceImageFit): WorkspaceDocument {
        layer(document, id)
        val replaced = LayerImageReplace.replace(document, id, raster,
            if (fit == WorkspaceImageFit.CONTAIN) LayerImageReplace.Fit.CONTAIN else LayerImageReplace.Fit.STRETCH)
        return replaced.copy(generationSource = document.generationSource, meshSource = document.meshSource)
    }

    /** [document] without pending layer [id]: the generators never read it, so it leaves no trace. */
    fun delete(document: WorkspaceDocument, id: String): WorkspaceDocument {
        layer(document, id)
        val source = document.source
        val layout = document.layout
        val generation = layout.generation
        return document.copy(
            source = WorkspaceSourceArt(source.widthPx, source.heightPx, source.layers.filter { it.id.raw != id }, source.groups),
            layerVisibility = document.layerVisibility - id, layerOverrides = document.layerOverrides - id,
            meshOverrides = document.meshOverrides - id, textureOverrides = document.textureOverrides - id,
            layout = layout.copy(layerTags = layout.layerTags - id, layerMaterials = layout.layerMaterials - id,
                generation = generation.copy(pendingLayerIds = generation.pendingLayerIds - id,
                    pendingClassifications = generation.pendingClassifications - id), referenceLayerIds = layout.referenceLayerIds - id),
        )
    }

    /**
     * [document] after the image of generated layer [id] was replaced: the rig keeps its geometry, and the next
     * generation may read the new pixels instead ([WorkspaceGeneration.refreshLayerIds]).
     */
    fun replacedGenerated(document: WorkspaceDocument, id: String): WorkspaceDocument {
        val generation = document.layout.generation
        if (!generation.generated || id in generation.refreshLayerIds || id in WorkspaceLayoutPreview.pendingIds(document)) return document
        return document.copy(layout = document.layout.copy(generation = generation.copy(refreshLayerIds = generation.refreshLayerIds + id)))
    }

    val schema: JsonObject by lazy {
        buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("state") { put("type", "string"); put("minLength", 1) }
                putJsonObject("refresh_geometry") {
                    put("type", "array"); put("maxItems", 1024); putJsonObject("items") { put("type", "string"); put("minLength", 1) }
                    put("description", "Generated layers to mesh again from their current pixels (their image was replaced since). Omitted: every layer whose image was replaced; [] keeps all geometry.")
                }
            }
            put("required", JsonArray(listOf(JsonPrimitive("state"))))
        }
    }

    const val DESCRIPTION = "Generate the rig, or generate it again. A project created empty for Layout is not generated until this runs; " +
        "afterwards, layers placed since the last generation, classification and tag changes, and replaced images whose geometry should refresh " +
        "wait for it (workspace_inspect scope=generation lists them). The generators then make the rig from the layers as they are now and " +
        "merge it onto the user's edits: what the user left follows the new output, the user's keyforms, warps, physics, swings, skeleton and " +
        "glue stay, new layers get generated meshes, and what does not carry over cleanly is reported in quality.regeneration. " +
        "Takes about as long as importing the artwork. One history edit."
}
