package io.github.psd2live.application

import io.github.psd2live.core.LayerSizeBudget
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.MaterialKind
import io.github.psd2live.project.storedCanvasRect
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthUnit
import org.umamo.format.FormatRegistry
import org.umamo.format.raster.RasterCodec
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.psd.PsdReader
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasPages
import org.umamo.interop.cmo3.cmo3SourceArtOf
import org.umamo.runtime.model.ArtSourceId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.math.roundToInt

/**
 * Reads files into materials: rasters (PNG, BMP, TIFF, WebP, JPEG), layered files (PSD, the images of a CMO3), vector
 * drawings (SVG) and folders of them. Pure reading: nothing is published until the materials are added to the document
 * ([WorkspaceMaterialEdits]).
 */
internal object WorkspaceMaterialFiles {
    const val MAX_PATHS = 64
    private const val MAX_FILES = 4096
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val MAX_DEPTH = 32
    /** The largest texture resolution a vector drawing renders at, per side. */
    const val MAX_VECTOR_SIDE = 4096
    val extensions = setOf("png", "bmp", "tif", "tiff", "webp", "jpg", "jpeg", "psd", "cmo3", "svg")
    /** What can be imported by name: [extensions], and a layout manifest or a zip holding one, which a folder's scan leaves out. */
    val fileExtensions = extensions + setOf("json", "zip")

    /** One material read from a file: [folder] is the folder path under the import's destination, top first. */
    data class Entry(
        val name: String,
        val folder: List<String>,
        val kind: MaterialKind,
        val raster: LayerRaster,
        val placement: LayerCanvasRect? = null,
        val svg: String? = null,
        val origin: String = "",
        val opacity: Float = 1f,
        val visible: Boolean = true,
        /** The index in [folder] of the folder that stands for the file the entry was read from (a PSD or CMO3), or -1. */
        val fileFolder: Int = -1,
        /** The canvas [placement] is on: the PSD's or CMO3's document size; 0 when unknown. */
        val placementWidth: Int = 0,
        val placementHeight: Int = 0,
        /** Semantic and user tags the item carries ([io.github.psd2live.core.LayerTags]). */
        val tags: Set<String> = emptySet(),
        /** Placed as a reference layer by default ([io.github.psd2live.project.MaterialItem.reference]). */
        val reference: Boolean = false,
    )

    /**
     * The materials [paths] hold: a file gives its image, a PSD, CMO3 or layout manifest ([LayoutManifest], also inside
     * a zip) a folder of its layers, a folder a folder of its supported files. [cmo3Images]: read a CMO3's layered images
     * (its atlas pages when it has none); the whole model imports through `project_import_cmo3` instead.
     */
    fun read(paths: List<Path>, checkCancelled: () -> Unit = {}): List<Entry> {
        require(paths.size in 1..MAX_PATHS) { "Give 1..$MAX_PATHS paths" }
        require(paths.all { it.isAbsolute }) { "Paths must be absolute" }
        var files = 0
        var bytes = 0L
        fun file(path: Path, folder: List<String>): List<Entry> {
            checkCancelled()
            require(++files <= MAX_FILES) { "An import holds at most $MAX_FILES files" }
            val size = Files.size(path)
            bytes += size
            require(bytes <= MAX_BYTES) { "An import reads at most ${MAX_BYTES / (1024 * 1024)} MiB" }
            if (path.extension.lowercase() in setOf("json", "zip")) {
                val layout = LayoutManifest.read(path, checkCancelled)
                return LayoutManifest.materials(layout, folder + name(layout.name, "layout"), path.name).map { it.copy(fileFolder = folder.size) }
            }
            val data = Files.readAllBytes(path)
            val origin = path.name
            return when (path.extension.lowercase()) {
                "psd" -> layered(PsdReader.read(data), folder + name(path.nameWithoutExtension, "psd"), origin, checkCancelled).map { it.copy(fileFolder = folder.size) }
                "cmo3" -> cmo3(data, folder + name(path.nameWithoutExtension, "cmo3"), origin, checkCancelled).map { it.copy(fileFolder = folder.size) }
                "svg" -> listOf(vector(data.decodeToString(), name(path.nameWithoutExtension, "svg"), folder, origin))
                else -> listOf(Entry(name(path.nameWithoutExtension, "image"), folder, MaterialKind.BITMAP, bitmap(data, path.name), origin = origin))
            }
        }
        // A folder linked into itself is read once; hidden files and folders (.git, .DS_Store) are left out.
        val visited = HashSet<Path>()
        fun directory(path: Path, folder: List<String>): List<Entry> {
            checkCancelled()
            require(folder.size < MAX_DEPTH) { "An import nests folders at most $MAX_DEPTH deep" }
            if (!visited.add(path.toRealPath())) return emptyList()
            val here = folder + name(path.name, "folder")
            val children = Files.list(path).use { stream -> stream.toList() }
                .filterNot { it.name.startsWith(".") || runCatching { Files.isHidden(it) }.getOrDefault(false) }
                .sortedWith { a, b -> naturalOrder(a.name, b.name) }
            return children.flatMap { child ->
                when {
                    child.isDirectory() -> directory(child, here)
                    child.extension.lowercase() in extensions -> file(child, here)
                    else -> emptyList()
                }
            }
        }
        return paths.flatMap { path ->
            require(Files.exists(path)) { "File not found: $path" }
            if (path.isDirectory()) directory(path, emptyList())
            else {
                require(path.extension.lowercase() in extensions || LayoutManifest.matches(path)) { "Unsupported material file: ${path.name}" }
                file(path, emptyList())
            }
        }
    }

    /** File names in reading order, numbers by value: `arm2` before `arm10`, case aside. */
    fun naturalOrder(a: String, b: String): Int {
        val pattern = Regex("""\d+|\D+""")
        val left = pattern.findAll(a.lowercase()).map { it.value }.toList(); val right = pattern.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]; val y = right[i]
            val order = if (x[0].isDigit() && y[0].isDigit()) x.trimStart('0').length.compareTo(y.trimStart('0').length)
                .takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0')) else x.compareTo(y)
            if (order != 0) return order
        }
        return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }

    /** [bytes] decoded to straight-alpha RGBA: the built-in codecs, else Skia (lossy WebP, progressive JPEG and the like). */
    fun bitmap(bytes: ByteArray, name: String): LayerRaster {
        val codec = FormatRegistry.detect(bytes, name) as? RasterCodec
        val decoded = codec?.let { runCatching { it.read(bytes) }.getOrNull() }
        val raster = decoded?.let { LayerRaster(it.width, it.height, it.rgba) } ?: skiaDecode(bytes, name)
        requireSize(raster.width, raster.height, name)
        return raster
    }

    private fun requireSize(width: Int, height: Int, name: String) {
        require(width > 0 && height > 0) { "Image has no pixels: $name" }
        require(width.toLong() * height <= LayerSizeBudget.MAX_PIXELS) { "Image exceeds ${LayerSizeBudget.MAX_PIXELS / (1024 * 1024)} megapixels: $name" }
    }

    private fun skiaDecode(bytes: ByteArray, name: String): LayerRaster {
        val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: throw IllegalArgumentException("Unsupported image: $name")
        image.use {
            requireSize(image.width, image.height, name)
            return pixels(image.width, image.height) { bitmap -> image.readPixels(bitmap) }
        }
    }

    /** Straight-alpha RGBA of a [width] x [height] image that [read] writes into a bitmap. */
    private fun pixels(width: Int, height: Int, read: (Bitmap) -> Boolean): LayerRaster {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        Bitmap().use { bitmap ->
            require(bitmap.allocPixels(info)) { "Cannot allocate ${width}x$height pixels" }
            require(read(bitmap)) { "Cannot read the image pixels" }
            val rgba = requireNotNull(bitmap.readPixels(info, width * 4, 0, 0)) { "Cannot read the image pixels" }
            return LayerRaster(width, height, rgba)
        }
    }

    /** A layered file's layers as materials under [folder]: its groups as folders, each layer where the file placed it. */
    fun layered(art: SourceArt, folder: List<String>, origin: String, checkCancelled: () -> Unit = {}): List<Entry> =
        art.layers.filter { it.raster.width > 0 && it.raster.height > 0 }.map { layer ->
            checkCancelled()
            val groups = layer.groupPath.split('/').filter { it.isNotEmpty() }.map { name(it, "group") }
            val hidden = groups.indices.any { depth -> art.groups.firstOrNull { it.path == groups.take(depth + 1).joinToString("/") }?.visible == false }
            Entry(name(layer.name, layer.id.raw), folder + groups, MaterialKind.BITMAP, layer.raster,
                layer.storedCanvasRect ?: LayerCanvasRect.of(layer.bounds), origin = "$origin#${layer.id.raw}",
                opacity = layer.opacity, visible = layer.visible && !hidden, placementWidth = art.widthPx, placementHeight = art.heightPx)
        }

    /** [raw] as a material name: printable, at most 128 characters, [fallback] when nothing is left. */
    fun name(raw: String, fallback: String): String =
        raw.filterNot(Char::isISOControl).trim().take(128).trim().ifEmpty { fallback.filterNot(Char::isISOControl).take(128).ifEmpty { "material" } }

    /** A CMO3's layered images as materials, or its atlas pages when it holds none. */
    fun cmo3(bytes: ByteArray, folder: List<String>, origin: String, checkCancelled: () -> Unit = {}): List<Entry> {
        val model = Cmo3.read(bytes)
        val root = model.root as? CModelSource ?: throw IllegalArgumentException("Invalid CMO3 model: $origin")
        val images = (root.textureManager as? CTextureManager)?._rawImages.let(Cmo3Import::elementsOf)
            .mapNotNull { (it as? LayeredImageWrapper)?.image as? CLayeredImage }
        val layered = images.flatMap { image ->
            val id = Cmo3Import.uuidOf(image.guid) ?: return@flatMap emptyList()
            val art = cmo3SourceArtOf(root, ArtSourceId(id), model::extractLayerPng) ?: return@flatMap emptyList()
            layered(art, if (images.size > 1) folder + (image.name ?: id) else folder, origin, checkCancelled)
        }
        if (layered.isNotEmpty()) return layered
        return cmo3AtlasPages(root, model::extractLayerPng).pageBytes.mapIndexed { index, png ->
            checkCancelled()
            Entry("texture_$index", folder, MaterialKind.BITMAP, bitmap(png, "texture_$index.png"), origin = "$origin#texture_$index")
        }
    }

    /** An SVG drawing as a vector material, rendered at its own size (its width and height, else its view box). */
    fun vector(svg: String, name: String, folder: List<String> = emptyList(), origin: String = ""): Entry {
        val (width, height) = svgSize(svg)
        return Entry(name, folder, MaterialKind.VECTOR, rasterize(svg, width, height), svg = svg, origin = origin)
    }

    /** The size an SVG drawing asks for, in pixels, clamped to [MAX_VECTOR_SIDE]. */
    fun svgSize(svg: String): Pair<Int, Int> = SVGDOM(Data.makeFromBytes(svg.encodeToByteArray())).use { dom ->
        val root = requireNotNull(dom.root) { "Not an SVG drawing" }
        fun length(value: org.jetbrains.skia.svg.SVGLength) = value.value.takeIf { value.unit != SVGLengthUnit.PERCENTAGE && it > 0f }
        val box = root.viewBox
        val width = length(root.width) ?: box?.width ?: 512f
        val height = length(root.height) ?: box?.height ?: 512f
        val scale = minOf(1f, MAX_VECTOR_SIDE / maxOf(width, height))
        (width * scale).roundToInt().coerceIn(1, MAX_VECTOR_SIDE) to (height * scale).roundToInt().coerceIn(1, MAX_VECTOR_SIDE)
    }

    /** [svg] rendered over [width] x [height] pixels, straight-alpha RGBA. */
    fun rasterize(svg: String, width: Int, height: Int): LayerRaster {
        require(width in 1..MAX_VECTOR_SIDE && height in 1..MAX_VECTOR_SIDE) { "Vector resolution must be 1..$MAX_VECTOR_SIDE per side" }
        return SVGDOM(Data.makeFromBytes(svg.encodeToByteArray())).use { dom ->
            requireNotNull(dom.root) { "Not an SVG drawing" }
            dom.setContainerSize(width.toFloat(), height.toFloat())
            Surface.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)).use { surface ->
                dom.render(surface.canvas)
                surface.makeImageSnapshot().use { image -> pixels(width, height) { bitmap -> image.readPixels(bitmap) } }
            }
        }
    }
}
