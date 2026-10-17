package io.github.psd2live.scenario

import io.github.psd2live.application.LayoutManifest
import io.github.psd2live.application.WorkspaceLayoutEdits
import io.github.psd2live.application.WorkspaceMaterialFiles
import io.github.psd2live.application.WorkspaceMaterialStaging
import io.github.psd2live.application.WorkspaceSourceImporter
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.png.PngCodec
import org.umamo.format.psd.PsdReader
import org.umamo.format.raster.RasterImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A layout manifest opens as a Layout project laid out as it says: each image keeps its pixels over its rectangle (a
 * face at three times its pixels stays that dense), the list's order is the stack, groups, tags, variants and opacity
 * hold, the layers are materials, and a zip holding the manifest at its root reads the same. Layers the generator has
 * not read move in the stack; generated ones keep their order.
 */
class LayoutManifestScenario {
	@TempDir lateinit var temp: Path

	private val figure = Characters.figure().source
	private val names = mapOf("back_hair" to "back hair", "leg_r" to "legwear-r", "leg_l" to "legwear-l", "skirt" to "skirt",
		"top" to "top", "sleeve_r" to "handwear-r", "sleeve_l" to "handwear-l", "face" to "face", "front_hair" to "front hair")
	private val groups = mapOf("back_hair" to "head", "face" to "head", "front_hair" to "head", "sleeve_r" to "body/arms", "sleeve_l" to "body/arms")
	private val config = PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false)

	private fun box(id: String): LayerBounds = opaqueBox(figure.layers.single { it.name == id }.raster)
	/** The figure's stack, top-most first, by manifest name. */
	private fun figureStack(): List<String> = WorkspaceLayoutEdits.stack(figure).map { names.getValue(it.name) }
	private fun stack(source: SourceArt): List<String> = WorkspaceLayoutEdits.stack(source).map { it.name }

	@Test fun aManifestOpensAsALayoutProjectLaidOutAsItSays() = Studio.run(temp) {
		val path = manifest(Files.createDirectories(temp.resolve("out")))
		step("open the manifest") { WorkspaceSourceImporter(runtime).importLayout(path, null, runtime.state.value.state, initialConfig = config) }
		fun check(stage: String) {
			val document = now.document
			val source = document.source
			expect(stage, stack(source) == figureStack()) { "the stack is ${stack(source)}" }
			val face = source.layers.single { it.name == "face" }
			expect(stage, face.id.raw == "face" && face.bounds == box("face") && face.raster.width == box("face").width * 3 && face.groupPath == "head") {
				"the face is ${face.id.raw} over ${face.bounds} with ${face.raster.width} px in ${face.groupPath}" }
			val t = box("top")
			val top = source.layers.single { it.name == "top" }
			expect(stage, top.canvasRect() == LayerCanvasRect(t.left + 0.25f, t.top + 0.25f, t.width.toFloat(), t.height.toFloat())) { "the top lies at ${top.canvasRect()}" }
			expect(stage, source.groups.map { it.path }.toSet() == setOf("head", "body", "body/arms") &&
				source.groups.single { it.path == "head" }.opacity == 0.9f) { "groups ${source.groups.map { it.path to it.opacity }}" }
			expect(stage, source.layers.single { it.name == "skirt" }.opacity == 0.8f) { "the skirt's opacity" }
		}
		check("open")
		val document = now.document
		expect("not generated", !document.layout.generation.generated && now.model.layoutPreview) { "a manifest opens to arrange before generating" }
		val overrides = document.layerOverrides
		val id = { name: String -> document.source.layers.single { it.name == name }.id.raw }
		expect("tags", overrides[id("legwear-l")]?.let { it.tag to it.side } == (SemanticTag.LEGWEAR to Side.LEFT) &&
			overrides[id("top")]?.let { it.tag to it.side } == (SemanticTag.TOPWEAR to Side.NONE) &&
			document.layout.layerTags[id("top")] == setOf("coat")) { "overrides $overrides, user tags ${document.layout.layerTags}" }
		expect("variant", overrides[id("skirt")]?.let { Triple(it.type, it.tag, it.parameter) } == Triple(LayerType.TOGGLE, SemanticTag.BOTTOMWEAR, "ParamSkirt")) {
			"the skirt is ${overrides[id("skirt")]}" }
		expect("names classify the rest", id("handwear-r") !in overrides &&
			now.model.analysis.layers.single { it.source.name == "handwear-r" }.semantic.let { it.tag to it.side } == (SemanticTag.HANDWEAR to Side.RIGHT)) {
			"handwear-r is ${now.model.analysis.layers.single { it.source.name == "handwear-r" }.semantic}" }
		val materials = document.layout.materials
		expect("materials", materials.items.size == names.size && document.layout.layerMaterials.size == names.size &&
			materials.folders.any { it.name == "figure" && it.parentId == null } && materials.items.single { it.name == "face" }.raster.width == box("face").width * 3) {
			"${materials.items.size} materials in ${materials.folders.map { it.name }}" }

		// The stack: to the back, a step forward, above another, and back to the front.
		edit("face to the back", WorkspaceLayoutEdits.REORDER, req("layer_ids" to listOf("face"), "to" to "back"))
		expect("back", stack(now.document.source).last() == "face") { "${stack(now.document.source)}" }
		edit("face forward", WorkspaceLayoutEdits.REORDER, req("layer_ids" to listOf("face"), "to" to "forward"))
		val forward = stack(now.document.source)
		expect("forward", forward[forward.lastIndex - 1] == "face") { "$forward" }
		edit("face above the top", WorkspaceLayoutEdits.REORDER, req("layer_ids" to listOf("face"), "to" to "above", "target" to id("top")))
		val above = stack(now.document.source)
		expect("above", above.indexOf("face") == above.indexOf("top") - 1) { "$above" }
		edit("face to the front", WorkspaceLayoutEdits.REORDER, req("layer_ids" to listOf("face", id("front hair")), "to" to "front"))
		val front = stack(now.document.source)
		val expected = listOf("front hair", "face") + figureStack().filterNot { it == "face" || it == "front hair" }
		expect("front", front.take(2).toSet() == setOf("face", "front hair") && front.drop(2) == expected.drop(2)) { "$front" }

		edit("generate", "rig_generate", req())
		expect("generated", !now.model.layoutPreview && puppet.deformers.isNotEmpty()) { "the manifest's layers did not generate a rig" }
		refused("a generated layer keeps its order", WorkspaceLayoutEdits.REORDER, req("layer_ids" to listOf("face"), "to" to "back"))
		reopen()
	}

	@Test fun aZipHoldingTheManifestReadsTheSame() {
		val directory = Files.createDirectories(temp.resolve("out"))
		val path = manifest(directory)
		val atRoot = zip(directory, temp.resolve("root.zip"), "")
		val inFolder = zip(directory, temp.resolve("folder.zip"), "figure/")
		val read = LayoutManifest.read(path)
		for (archive in listOf(atRoot, inFolder)) {
			assertTrue(LayoutManifest.matches(archive))
			val zipped = LayoutManifest.read(archive)
			assertEquals(read.layers.map { it.name to it.rect }, zipped.layers.map { it.name to it.rect })
			assertTrue(read.layers.zip(zipped.layers).all { (a, b) -> a.raster.rgba.contentEquals(b.raster.rgba) })
		}
		// As materials: a folder named after the manifest, its groups as subfolders, each item placed and tagged.
		val entries = WorkspaceMaterialFiles.read(listOf(atRoot))
		assertEquals(figureStack().reversed(), entries.map { it.name })
		val face = entries.single { it.name == "face" }
		assertEquals(listOf("figure", "head"), face.folder)
		assertEquals(LayerCanvasRect.of(box("face")), face.placement)
		assertEquals(setOf("top", "coat"), entries.single { it.name == "top" }.tags)
		assertTrue(entries.all { it.placementWidth == 420 && it.placementHeight == 420 })
		// A folder's scan reads the images one by one and leaves the manifest and archives out.
		assertTrue(WorkspaceMaterialFiles.read(listOf(directory)).all { it.placement == null && it.folder == listOf("out", "parts") })
	}

	@Test fun aManifestIsRefusedWhereItIsWrong() {
		val directory = Files.createDirectories(temp.resolve("out"))
		Characters.disc(directory, "disc.png", size = 24)
		Characters.disc(temp, "outside.png", size = 24)
		fun manifest(vararg layers: JsonObject, version: Int = 1, format: String = LayoutManifest.FORMAT): Path =
			directory.resolve("m.json").also { Files.writeString(it, buildJsonObject {
				put("format", format); put("version", version)
				put("canvas", buildJsonObject { put("width", 64); put("height", 64) }); put("layers", JsonArray(layers.toList()))
			}.toString()) }
		fun layer(image: String, width: Int = 24, height: Int = 24) = buildJsonObject {
			put("name", "disc"); put("image", buildJsonObject { put("path", image); put("width", width); put("height", height) })
			put("rect", buildJsonObject { put("left", 0); put("top", 0); put("width", 24); put("height", 24) })
		}
		assertEquals(1, LayoutManifest.read(manifest(layer("disc.png"))).layers.size)
		assertFailsWith<IllegalArgumentException> { LayoutManifest.read(manifest(layer("../outside.png"))) }
		assertFailsWith<IllegalArgumentException> { LayoutManifest.read(manifest(layer("disc.png", width = 25))) }
		assertFailsWith<IllegalArgumentException> { LayoutManifest.read(manifest(layer("disc.png"), version = 2)) }
		assertFalse(LayoutManifest.matches(manifest(layer("disc.png"), format = "something-else")))
		assertFailsWith<IllegalArgumentException> { WorkspaceMaterialFiles.read(listOf(directory.resolve("m.json"))) }
		// Two manifests at an archive's root leave it ambiguous.
		val good = manifest(layer("disc.png"))
		val twice = temp.resolve("twice.zip")
		ZipOutputStream(Files.newOutputStream(twice)).use { out ->
			for (name in listOf("a.json", "b.json")) { out.putNextEntry(ZipEntry(name)); out.write(Files.readAllBytes(good)); out.closeEntry() }
			out.putNextEntry(ZipEntry("disc.png")); out.write(Files.readAllBytes(directory.resolve("disc.png"))); out.closeEntry()
		}
		assertFailsWith<IllegalArgumentException> { LayoutManifest.read(twice) }
	}

	private fun emptyLayout() = WorkspaceDocument(WorkspaceSourceArt(420, 420, emptyList(), emptyList()), emptyMap(), emptySet(), emptyMap(), emptyMap(),
		RigEditOverlay.Empty, Characters.figure().settings, layout = WorkspaceLayout(generation = WorkspaceGeneration.NotGenerated))

	/** Placing a PSD's folder of materials keeps the PSD's stack. */
	@Test fun placingAPsdFolderKeepsItsStack() = Studio.run(temp, emptyLayout()) {
		val psd = Characters.figurePsd(Files.createDirectories(temp.resolve("files")))
		val expected = stack(PsdReader.read(Files.readAllBytes(psd)))
		edit("import the figure", "material_add", req("staging" to WorkspaceMaterialStaging.stage(WorkspaceMaterialFiles.read(listOf(psd)))))
		edit("place the whole figure", "layer_place", req("material_id" to now.document.layout.materials.folders.single().id))
		expect("stack", stack(now.document.source) == expected) { "placed ${stack(now.document.source)}, the PSD stacks $expected" }
	}

	/** Placing a manifest's folder of materials keeps the manifest's stack. */
	@Test fun placingAManifestFolderKeepsItsStack() = Studio.run(temp, emptyLayout()) {
		val path = manifest(Files.createDirectories(temp.resolve("out")))
		edit("import the manifest", "material_add", req("staging" to WorkspaceMaterialStaging.stage(WorkspaceMaterialFiles.read(listOf(path)))))
		edit("place the manifest", "layer_place", req("material_id" to now.document.layout.materials.folders.single { it.parentId == null }.id))
		expect("stack", stack(now.document.source) == figureStack()) { "placed ${stack(now.document.source)}, the manifest stacks ${figureStack()}" }
	}

	/**
	 * A reference layer stays on the canvas and in the stack but out of every generation, so out of the rig and exports;
	 * given back, it waits for the next generation like a layer placed since. A generated layer stays a model layer.
	 */
	@Test fun referenceLayersStayOutOfTheRig() = Studio.run(temp) {
		val path = manifest(Files.createDirectories(temp.resolve("out")), references = setOf("back_hair"))
		step("open the manifest") { WorkspaceSourceImporter(runtime).importLayout(path, null, runtime.state.value.state, initialConfig = config) }
		val hair = now.document.source.layers.single { it.name == "back hair" }.id.raw
		fun generated() = now.model.generated.rig.layerIdByDrawableId.values.toSet()
		fun shown() = now.model.rig.layerIdByDrawableId.values.toSet()
		fun listed(key: String) = inspect("generation").getValue(key).jsonArray.map { it.jsonPrimitive.content }
		expect("marked", now.document.layout.referenceLayerIds == setOf(hair) &&
			now.document.layout.materials.items.single { it.name == "back hair" }.reference) { "references ${now.document.layout.referenceLayerIds}" }
		expect("listed", listed("reference_layers") == listOf(hair) && hair !in listed("pending_layers")) { "${inspect("generation")}" }
		// A reference shows as a bare quad over its rectangle, drawn apart from the artwork: no atlas holds its pixels.
		fun quad() = now.model.rig.puppet.drawables.single { now.model.rig.layerIdByDrawableId[it.id.raw] == hair }
		fun corners() = quad().mesh!!.positions.toList()
		fun apart(stage: String) = expect(stage, now.model.referenceLayerIds == setOf(hair) && hair !in now.model.atlas.placementByLayerId &&
			quad().mesh!!.vertexCount == 4 && now.model.analysis.layers.any { it.source.id.raw == hair }) {
			"references ${now.model.referenceLayerIds}, atlas ${now.model.atlas.placementByLayerId.keys}, ${quad().mesh!!.vertexCount} vertices" }
		apart("apart before generating")
		val r = now.document.source.layers.single { it.id.raw == hair }.canvasRect()
		expect("over its rectangle", corners() == listOf(r.left, -r.top, r.right, -r.top, r.right, -r.bottom, r.left, -r.bottom)) { "corners ${corners()} for $r" }
		edit("move the reference", "layer_transform", req("layer_id" to hair, "translate" to listOf(10, 4)))
		expect("the quad follows", corners().let { it[0] == r.left + 10 && it[1] == -(r.top + 4) }) { "corners ${corners()}" }

		edit("generate", "rig_generate", req())
		expect("out of the rig", hair !in generated() && hair in shown() && "face" in generated()) { "generated ${generated()}, shown ${shown()}" }
		apart("apart after generating")
		expect("never waits", inspect("generation").let { it.getValue("pending_layers").jsonArray.isEmpty() && listed("reference_layers") == listOf(hair) }) {
			"${inspect("generation")}" }
		edit("the reference moves to the front", WorkspaceLayoutEdits.REORDER, req("layer_ids" to listOf(hair), "to" to "front"))
		expect("front", stack(now.document.source).first() == "back hair") { "${stack(now.document.source)}" }
		refused("a generated layer stays a model layer", WorkspaceLayoutEdits.MARK_REFERENCE, req("layer_ids" to listOf("face"), "reference" to true))
		reopen()
		expect("kept", now.document.layout.referenceLayerIds == setOf(hair) && hair !in generated()) { "after reopening ${now.document.layout.referenceLayerIds}" }

		edit("give it back", WorkspaceLayoutEdits.MARK_REFERENCE, req("layer_ids" to listOf(hair), "reference" to false))
		expect("waits", listed("pending_layers") == listOf(hair) && listed("reference_layers").isEmpty()) { "${inspect("generation")}" }
		edit("generate it", "rig_generate", req())
		expect("in the rig", hair in generated()) { "generated ${generated()}" }
		refused("now generated", WorkspaceLayoutEdits.MARK_REFERENCE, req("layer_ids" to listOf(hair), "reference" to true))
	}

	/** A manifest's reference layers import as marked materials; placing follows the mark unless the placement says. */
	@Test fun referenceMaterialsPlaceAsReference() = Studio.run(temp, emptyLayout()) {
		val path = manifest(Files.createDirectories(temp.resolve("out")), references = setOf("face"))
		assertEquals(listOf("face"), WorkspaceMaterialFiles.read(listOf(path)).filter { it.reference }.map { it.name })
		edit("import the manifest", "material_add", req("staging" to WorkspaceMaterialStaging.stage(WorkspaceMaterialFiles.read(listOf(path)))))
		val library = now.document.layout.materials
		val face = library.items.single { it.name == "face" }
		edit("place the face", "layer_place", req("material_id" to face.id))
		val placed = now.document.source.layers.single().id.raw
		expect("as reference", now.document.layout.referenceLayerIds == setOf(placed)) { "${now.document.layout.referenceLayerIds}" }
		refused("only a reference to generate from", "rig_generate", req())
		edit("place it as a model layer", "layer_place", req("material_id" to face.id, "reference" to false))
		expect("as model", now.document.layout.referenceLayerIds == setOf(placed)) { "${now.document.layout.referenceLayerIds}" }
		val root = library.folders.single { it.parentId == null }.id
		edit("mark the folder", "material_mark_reference", req("ids" to listOf(root), "reference" to true))
		expect("all marked", now.document.layout.materials.items.all { it.reference }) { "unmarked ${now.document.layout.materials.items.filterNot { it.reference }.map { it.name }}" }
		edit("place the folder", "layer_place", req("material_id" to root))
		expect("all reference", now.document.layout.referenceLayerIds.size == 1 + names.size) { "${now.document.layout.referenceLayerIds.size} references" }
	}

	/**
	 * The figure as a manifest: tight crops listed back to front, the face at three times its pixels, the top a quarter
	 * pixel off the grid and tagged with a user word, the skirt a toggle at 80% opacity, the head and arms in groups;
	 * the layers named in [references] are reference layers.
	 */
	private fun manifest(directory: Path, references: Set<String> = emptySet()): Path {
		val layers = WorkspaceLayoutEdits.stack(figure).reversed().map { layer ->
			val name = names.getValue(layer.name)
			val b = opaqueBox(layer.raster)
			val scale = if (layer.name == "face") 3 else 1
			val file = "parts/${layer.name}.png"
			Files.createDirectories(directory.resolve("parts"))
			Files.write(directory.resolve(file), PngCodec.write(RasterImage(b.width * scale, b.height * scale, crop(layer.raster, b, scale))))
			val shift = if (layer.name == "top") 0.25 else 0.0
			buildJsonObject {
				if (layer.name in setOf("face", "top")) put("id", layer.name)
				put("name", name)
				put("image", buildJsonObject { put("path", file); put("width", b.width * scale); put("height", b.height * scale) })
				put("rect", buildJsonObject { put("left", b.left + shift); put("top", b.top + shift); put("width", b.width); put("height", b.height) })
				groups[layer.name]?.let { put("group", it) }
				if (layer.name in references) put("reference", true)
				when (layer.name) {
					"top" -> put("tags", JsonArray(listOf("top", "coat").map(::JsonPrimitive)))
					"leg_l" -> put("tags", JsonArray(listOf("leg", "left").map(::JsonPrimitive)))
					"skirt" -> { put("tags", JsonArray(listOf(JsonPrimitive("bottom")))); put("opacity", 0.8)
						put("variant", buildJsonObject { put("type", "toggle"); put("parameter", "ParamSkirt") }) }
				}
			}
		}
		return directory.resolve("figure.json").also {
			Files.writeString(it, buildJsonObject {
				put("format", LayoutManifest.FORMAT); put("version", LayoutManifest.VERSION); put("name", "figure")
				put("canvas", buildJsonObject { put("width", figure.widthPx); put("height", figure.heightPx) })
				put("groups", JsonArray(listOf(buildJsonObject { put("path", "head"); put("opacity", 0.9) })))
				put("layers", JsonArray(layers))
			}.toString())
		}
	}

	/** [directory]'s files zipped under [prefix]. */
	private fun zip(directory: Path, target: Path, prefix: String): Path {
		ZipOutputStream(Files.newOutputStream(target)).use { out ->
			Files.walk(directory).use { files -> files.filter(Files::isRegularFile).sorted().toList() }.forEach { file ->
				out.putNextEntry(ZipEntry(prefix + directory.relativize(file).joinToString("/")))
				out.write(Files.readAllBytes(file)); out.closeEntry()
			}
		}
		return target
	}

	private fun opaqueBox(raster: LayerRaster): LayerBounds {
		var left = raster.width; var top = raster.height; var right = 0; var bottom = 0
		for (y in 0 until raster.height) for (x in 0 until raster.width) if (raster.rgba[(y * raster.width + x) * 4 + 3].toInt() != 0) {
			left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
		}
		return LayerBounds(left, top, right - left, bottom - top)
	}

	/** The pixels of [box] in [raster], each repeated [scale] times across and down. */
	private fun crop(raster: LayerRaster, box: LayerBounds, scale: Int): ByteArray {
		val w = box.width * scale; val h = box.height * scale
		val out = ByteArray(w * h * 4)
		for (y in 0 until h) for (x in 0 until w)
			System.arraycopy(raster.rgba, ((box.top + y / scale) * raster.width + box.left + x / scale) * 4, out, (y * w + x) * 4, 4)
		return out
	}
}
