package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * The PSD2Live layout manifest ([FORMAT], specified in docs/zh/spec/LAYOUT_MANIFEST.md): a JSON file that lays out a
 * canvas from images. Each layer names its image and the image's pixel size, its rectangle on the canvas, its group,
 * tags, variant, visibility and opacity; the layers are listed back to front. A manifest is read from the folder that
 * holds it, or from a zip archive whose root - or only top-level folder - holds it beside the images.
 */
internal object LayoutManifest {
    const val FORMAT = "psd2live-layout"
    const val VERSION = 1
    private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    private const val MAX_LAYERS = 4096
    /** One image file's bytes; the images together are bounded only by what the decoded layers take, as a PSD's are. */
    private const val MAX_IMAGE_BYTES = 1024L * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 16384
    private val ID = Regex("[A-Za-z0-9_.:#-]{1,96}")

    data class Layer(val id: String?, val name: String, val raster: LayerRaster, val rect: LayerCanvasRect, val group: String,
                     val tags: Set<String>?, val variant: LayerVariant, val visible: Boolean, val opacity: Float, val reference: Boolean = false)
    data class Group(val path: String, val visible: Boolean, val opacity: Float)
    /** A manifest read with its images: [layers] back to front, [groups] the properties it gives group paths. */
    data class Layout(val name: String, val width: Int, val height: Int, val layers: List<Layer>, val groups: Map<String, Group>)
    /** A new project's source from a manifest: the art, the classifications its tags and variants give, the user's own tags, the reference layers. */
    data class Source(val art: WorkspaceSourceArt, val overrides: Map<String, LayerClassificationOverride>, val userTags: Map<String, Set<String>>,
                      val references: Set<String> = emptySet())

    /** Whether [path] is a manifest, or a zip archive holding one. */
    fun matches(path: Path): Boolean = runCatching {
        Files.isRegularFile(path) && when (path.extension.lowercase()) {
            "json" -> Files.size(path) <= MAX_MANIFEST_BYTES && declares(Files.readString(path))
            "zip" -> ZipFile(path.toFile()).use { manifestEntry(it) != null }
            else -> false
        }
    }.getOrDefault(false)

    fun read(path: Path, checkCancelled: () -> Unit = {}): Layout {
        require(path.isAbsolute && Files.isRegularFile(path)) { "Layout manifest not found: $path" }
        return when (path.extension.lowercase()) {
            "json" -> {
                require(Files.size(path) <= MAX_MANIFEST_BYTES) { "A layout manifest is at most ${MAX_MANIFEST_BYTES / (1024 * 1024)} MiB" }
                val directory = path.toAbsolutePath().normalize().parent
                parse(Files.readString(path), path.nameWithoutExtension, checkCancelled) { image ->
                    val file = directory.resolve(image).normalize()
                    require(file.startsWith(directory) && Files.isRegularFile(file)) { "Image not found beside the manifest: $image" }
                    require(Files.size(file) <= MAX_IMAGE_BYTES) { imageTooLarge(image) }
                    Files.readAllBytes(file)
                }
            }
            "zip" -> ZipFile(path.toFile()).use { zip ->
                val manifest = requireNotNull(manifestEntry(zip)) { "No layout manifest at the root of ${path.name}" }
                val root = manifest.name.substringBeforeLast('/', "")
                parse(text(zip, manifest), path.nameWithoutExtension, checkCancelled) { image ->
                    val entry = zip.getEntry(if (root.isEmpty()) image else "$root/$image")?.takeUnless { it.isDirectory }
                        ?: throw IllegalArgumentException("Image not found in ${path.name}: $image")
                    zip.getInputStream(entry).use { capped(it, MAX_IMAGE_BYTES) { imageTooLarge(image) } }
                }
            }
            else -> throw IllegalArgumentException("A layout manifest is a .json file or a .zip archive: ${path.name}")
        }
    }

    /**
     * [layout] as the source of a new project, its layers in painter's order as a PSD's are (the list back to front, the
     * order counted from the top-most), with groups for every group path a layer uses. A
     * layer's tags classify it when they hold part words (part and side both from the tags); otherwise its name does, as
     * a PSD layer's, with a side word in the tags still choosing the side. A variant joins either.
     */
    fun source(layout: Layout): Source {
        val taken = layout.layers.mapNotNullTo(HashSet()) { it.id }
        val overrides = LinkedHashMap<String, LayerClassificationOverride>()
        val userTags = LinkedHashMap<String, Set<String>>()
        val references = LinkedHashSet<String>()
        val count = layout.layers.size
        val layers = layout.layers.mapIndexed { index, layer ->
            val id = layer.id ?: StableIds.fresh(StableIds.stem("layer_", index, layer.name)) { it in taken }.also { taken += it }
            classification(layer)?.let { overrides[id] = it }
            layer.tags?.let(LayerTags::resolve)?.user?.takeIf { it.isNotEmpty() }?.let { userTags[id] = it }
            if (layer.reference) references += id
            val bounds = WorkspaceMaterialEdits.enclosing(layer.rect)
            // Listed back to front; the source counts its order from the top-most layer.
            WorkspaceSourceLayer(LayerId(id), layer.name, layer.group, SourceLayerKind.Raster, layer.visible, count - 1 - index, bounds,
                layer.opacity, false, LayerBlend.Normal, ChannelMask.ALL, layer.raster, null, null, false,
                rect = layer.rect.takeUnless { it.matches(bounds) })
        }
        val groups = layout.layers.flatMapTo(LinkedHashSet()) { prefixes(it.group) }.map { path ->
            val group = layout.groups[path]
            WorkspaceSourceGroup(path, path.substringAfterLast('/'), group?.visible ?: true, group?.opacity ?: 1f, false, LayerBlend.Normal, true)
        }
        return Source(WorkspaceSourceArt(layout.width, layout.height, layers, groups), overrides, userTags, references)
    }

    /**
     * [layout]'s layers as materials under [folder], back to front as a PSD's are: groups become subfolders, each item
     * is placed where the manifest puts it and carries the layer's tags. Variants belong to layers, not materials.
     */
    fun materials(layout: Layout, folder: List<String>, origin: String): List<WorkspaceMaterialFiles.Entry> =
        layout.layers.withIndex().map { (index, layer) ->
            val hidden = prefixes(layer.group).any { layout.groups[it]?.visible == false }
            WorkspaceMaterialFiles.Entry(layer.name, folder + layer.group.split('/').filter { it.isNotEmpty() }, MaterialKind.BITMAP,
                layer.raster, layer.rect, origin = "$origin#$index", opacity = layer.opacity, visible = layer.visible && !hidden,
                placementWidth = layout.width, placementHeight = layout.height, tags = layer.tags.orEmpty(), reference = layer.reference)
        }

    private fun classification(layer: Layer): LayerClassificationOverride? {
        val resolution = layer.tags?.let(LayerTags::resolve)
        val part = resolution?.tag?.takeIf { it != SemanticTag.UNKNOWN }
        val side = resolution?.side?.takeIf { it != Side.NONE }
        if (part == null && side == null && layer.variant == LayerVariant.NONE) return null
        val named = LayerClassifier.classify(layer.name)
        return LayerClassificationOverride(layer.variant.type, part ?: named.tag, side ?: if (part != null) Side.NONE else named.side,
            layer.variant.parameter, layer.variant.switchId)
    }

    private fun parse(text: String, fallbackName: String, checkCancelled: () -> Unit, load: (String) -> ByteArray): Layout {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw IllegalArgumentException("A layout manifest is a JSON object")
        require(root.text("format") == FORMAT) { "Not a PSD2Live layout manifest: format must be \"$FORMAT\"" }
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("The layout manifest has no version")
        require(version in 1..VERSION) { "Layout manifest version $version is newer than this PSD2Live reads ($VERSION)" }
        val canvas = root.objectAt("canvas", "the manifest")
        val width = canvas.integer("width", "canvas"); val height = canvas.integer("height", "canvas")
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) { "The canvas must be 1..8192 per side and at most 16 megapixels" }
        val groupList = root["groups"]?.jsonArray.orEmpty().mapIndexed { index, element ->
            val group = element.jsonObject
            Group(groupPath(group.text("path") ?: throw IllegalArgumentException("groups[$index] has no path")),
                group.flag("visible") ?: true, group.opacity("groups[$index]"))
        }
        val groups = groupList.associateBy { it.path }
        require(groups.size == groupList.size) { "groups lists a path twice" }
        val entries = root["layers"]?.jsonArray ?: throw IllegalArgumentException("The layout manifest has no layers")
        require(entries.size in 1..MAX_LAYERS) { "A layout manifest lists 1..$MAX_LAYERS layers" }
        val images = HashMap<String, LayerRaster>()
        val ids = HashSet<String>()
        val layers = entries.mapIndexed { index, element ->
            checkCancelled()
            val layer = element as? JsonObject ?: throw IllegalArgumentException("layers[$index] is not an object")
            val at = "layers[$index]"
            val name = layer.text("name")?.filterNot(Char::isISOControl)?.trim()?.take(128)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("$at has no name")
            val id = layer.text("id")?.also { require(it.matches(ID) && ids.add(it)) { "$at: id must be unique and match ${ID.pattern}" } }
            val image = layer.objectAt("image", at)
            val path = imagePath(image.text("path") ?: throw IllegalArgumentException("$at: image has no path"), at)
            // Each file is decoded as it is read, so only the decoded layers stay in memory.
            val raster = images.getOrPut(path) { WorkspaceMaterialFiles.bitmap(load(path), path) }
            val pixelsWide = image.integer("width", "$at.image"); val pixelsHigh = image.integer("height", "$at.image")
            require(raster.width == pixelsWide && raster.height == pixelsHigh) {
                "$at: $path is ${raster.width}x${raster.height} pixels, the manifest says ${pixelsWide}x$pixelsHigh" }
            val r = layer.objectAt("rect", at)
            val rect = LayerCanvasRect(r.number("left", "$at.rect"), r.number("top", "$at.rect"), r.number("width", "$at.rect"), r.number("height", "$at.rect"))
            require(rect.width > 0f && rect.height > 0f) { "$at: rect needs a positive width and height" }
            val tags = layer["tags"]?.jsonArray?.mapTo(LinkedHashSet()) { LayerTags.normalize(it.jsonPrimitive.content).also(LayerTags::requireValid) }
            require(tags == null || !(LayerTags.LEFT in tags && LayerTags.RIGHT in tags)) { "$at: a layer is left or right, not both" }
            Layer(id, name, raster, rect, layer.text("group")?.let(::groupPath).orEmpty(), tags,
                layer["variant"]?.let { variant(it.jsonObject, at) } ?: LayerVariant.NONE, layer.flag("visible") ?: true, layer.opacity(at),
                layer.flag("reference") ?: false)
        }
        return Layout(root.text("name")?.let { WorkspaceMaterialFiles.name(it, fallbackName) } ?: fallbackName, width, height, layers, groups)
    }

    private fun variant(value: JsonObject, at: String): LayerVariant {
        val type = when (val kind = value.text("type")) {
            "none" -> LayerType.PRESET; "toggle" -> LayerType.TOGGLE; "switch" -> LayerType.SWITCH
            else -> throw IllegalArgumentException("$at: variant type must be none, toggle or switch, not $kind")
        }
        val parameter = value.text("parameter")?.trim().orEmpty()
        val switchId = value["switch_id"]?.jsonPrimitive?.intOrNull ?: 0
        require(type == LayerType.PRESET || parameter.isNotEmpty()) { "$at: a toggle or switch names its parameter" }
        require(type != LayerType.PRESET || (parameter.isEmpty() && switchId == 0)) { "$at: variant none takes no parameter or switch_id" }
        require(switchId >= 0 && (type == LayerType.SWITCH || switchId == 0)) { "$at: only a switch takes a nonnegative switch_id" }
        return LayerVariant(type, parameter, switchId)
    }

    /** A group path: names joined by `/`, each printable and nonempty. */
    private fun groupPath(raw: String): String {
        val names = raw.split('/').map { it.trim() }
        require(raw.isNotBlank() && names.all { it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl) }) { "Invalid group path: $raw" }
        return names.joinToString("/")
    }

    private fun prefixes(path: String): List<String> =
        if (path.isEmpty()) emptyList() else path.split('/').let { names -> names.indices.map { names.take(it + 1).joinToString("/") } }

    /** An image path: relative, `/`-separated, inside the manifest's folder. */
    private fun imagePath(raw: String, at: String): String {
        val names = raw.split('/')
        require(raw.isNotEmpty() && '\\' !in raw && ':' !in raw && !raw.startsWith('/') && names.none { it.isEmpty() || it == "." || it == ".." }) {
            "$at: an image path is relative, uses / and stays inside the manifest's folder: $raw" }
        return raw
    }

    /** The archive's manifest: the one root-level JSON declaring [FORMAT], else the one in its only top-level folder. */
    private fun manifestEntry(zip: ZipFile): ZipEntry? {
        require(zip.size() <= MAX_ARCHIVE_ENTRIES) { "An archive holds at most $MAX_ARCHIVE_ENTRIES entries" }
        // Resource forks and Finder metadata that macOS adds to archives are not content.
        val files = zip.entries().asSequence().filterNot { entry -> entry.isDirectory || entry.name.split('/').any { it == "__MACOSX" || it.startsWith("._") } }.toList()
        fun declared(folder: String) = files.filter { entry ->
            entry.name.substringBeforeLast('/', "") == folder && entry.name.endsWith(".json", true) &&
                runCatching { declares(text(zip, entry)) }.getOrDefault(false)
        }
        val tops = files.mapTo(HashSet()) { it.name.substringBefore('/') }
        val found = declared("").ifEmpty {
            val single = tops.singleOrNull()?.takeIf { top -> files.all { it.name.startsWith("$top/") } }
            single?.let(::declared).orEmpty()
        }
        require(found.size <= 1) { "The archive holds more than one layout manifest: ${found.joinToString { it.name }}" }
        return found.singleOrNull()
    }

    private fun declares(text: String): Boolean =
        (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.text("format") == FORMAT

    private fun text(zip: ZipFile, entry: ZipEntry): String =
        zip.getInputStream(entry).use { capped(it, MAX_MANIFEST_BYTES) { "A layout manifest is at most ${MAX_MANIFEST_BYTES / (1024 * 1024)} MiB" } }.decodeToString()

    /** The bytes of [input], refused past [limit]: an archive's own sizes are not trusted. */
    private fun capped(input: InputStream, limit: Long, message: () -> String): ByteArray {
        val bytes = input.readNBytes((minOf(limit, Int.MAX_VALUE - 8L) + 1).toInt())
        require(bytes.size <= limit) { message() }
        return bytes
    }

    private fun imageTooLarge(image: String) = "An image file is at most ${MAX_IMAGE_BYTES / (1024 * 1024)} MiB: $image"

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.flag(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.objectAt(key: String, at: String): JsonObject =
        this[key] as? JsonObject ?: throw IllegalArgumentException("$at has no $key object")
    private fun JsonObject.integer(key: String, at: String): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("$at has no integer $key")
    private fun JsonObject.number(key: String, at: String): Float =
        this[key]?.jsonPrimitive?.floatOrNull?.takeIf(Float::isFinite) ?: throw IllegalArgumentException("$at has no finite number $key")
    private fun JsonObject.opacity(at: String): Float =
        (this["opacity"]?.let { it.jsonPrimitive.floatOrNull ?: Float.NaN } ?: 1f).also { require(it in 0f..1f) { "$at: opacity is 0..1" } }
}
