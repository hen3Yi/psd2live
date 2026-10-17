package io.github.psd2live.application

import io.github.psd2live.core.PreviewRenderer
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Reading the material library of one captured document: listing, details, previews and PSD export. */
internal object WorkspaceMaterialQueries {
    fun item(item: MaterialItem, references: Int): JsonObject = buildJsonObject {
        put("id", item.id); put("name", item.name); item.folderId?.let { put("folder_id", it) }
        put("kind", item.kind.name.lowercase()); put("width", item.raster.width); put("height", item.raster.height)
        putJsonArray("tags") { item.tags.sorted().forEach { add(JsonPrimitive(it)) } }
        if (item.origin.isNotEmpty()) put("origin", item.origin)
        item.placement?.let { rect -> putJsonArray("placement") { listOf(rect.left, rect.top, rect.width, rect.height).forEach { add(JsonPrimitive(it)) } } }
        put("references", references)
        if (item.reference) put("reference", true)
    }

    fun folder(folder: MaterialFolder, library: MaterialLibrary): JsonObject = buildJsonObject {
        put("id", folder.id); put("name", folder.name); folder.parentId?.let { put("parent_id", it) }
        if (folder.origin.isNotEmpty()) put("origin", folder.origin)
        putJsonArray("tags") { folder.tags.sorted().forEach { add(JsonPrimitive(it)) } }
        put("folders", library.folders.count { it.parentId == folder.id }); put("items", library.items.count { it.folderId == folder.id })
    }

    /** Layers showing each material, among the layers [state] has and has not deleted. */
    fun references(state: WorkspaceLayoutState): Map<String, List<String>> {
        val present = state.layers.mapTo(HashSet()) { it.id.raw } - state.deletedLayerIds
        return state.layout.layerMaterials.filterKeys { it in present }.entries.groupBy({ it.value }, { it.key })
    }

    fun list(state: WorkspaceLayoutState, request: JsonObject): JsonObject {
        val library = state.layout.materials
        val folderId = request["folder_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        if (folderId != null) requireNotNull(library.folder(folderId)) { "Material folder not found: $folderId" }
        val recursive = request["recursive"]?.jsonPrimitive?.boolean ?: false
        val query = request["query"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val kind = request["kind"]?.jsonPrimitive?.content
        val unused = request["unused"]?.jsonPrimitive?.boolean ?: false
        val references = references(state)
        val scope = if (folderId == null) null else library.subtree(folderId)
        fun inScope(parent: String?) = if (recursive) scope == null || parent in scope else parent == folderId
        fun matches(name: String, tags: Set<String>) = query == null || name.contains(query, ignoreCase = true) ||
            tags.any { it.equals(query, ignoreCase = true) }
        val folders = library.folders.filter { inScope(it.parentId) && matches(it.name, it.tags) && kind == null && !unused }
        val items = library.items.filter { inScope(it.folderId) && matches(it.name, it.tags + library.ancestry(it.folderId).flatMap { f -> f.tags }) &&
            (kind == null || it.kind.name.equals(kind, ignoreCase = true)) && (!unused || references[it.id].isNullOrEmpty()) }
        val offset = request["offset"]?.jsonPrimitive?.int ?: 0
        val limit = request["limit"]?.jsonPrimitive?.int ?: 64
        require(offset >= 0 && limit in 1..256) { "offset must be at least 0 and limit 1..256" }
        val rows = folders.map { folder(it, library) } + items.map { item(it, references[it.id].orEmpty().size) }
        return buildJsonObject {
            folderId?.let { put("folder_id", it) }
            putJsonArray("path") { library.ancestry(folderId).reversed().forEach { add(buildJsonObject { put("id", it.id); put("name", it.name) }) } }
            put("total", rows.size)
            state.layout.reference?.let { reference -> putJsonObject("reference") {
                put("material_id", reference.materialId)
                putJsonArray("rect") { reference.rect.let { listOf(it.left, it.top, it.width, it.height) }.forEach { add(JsonPrimitive(it)) } }
                put("opacity", reference.opacity); put("visible", reference.visible)
            } }
            put("folders", JsonArray(rows.take(folders.size).drop(offset).take(limit)))
            val itemRows = rows.drop(folders.size)
            val itemOffset = (offset - folders.size).coerceAtLeast(0)
            val itemLimit = (limit - (folders.size - offset).coerceAtLeast(0).coerceAtMost(limit)).coerceAtLeast(0)
            put("items", JsonArray(itemRows.drop(itemOffset).take(itemLimit)))
            if (offset.toLong() + limit < rows.size) put("next", offset + limit)
        }
    }

    fun get(state: WorkspaceLayoutState, id: String, includeSvg: Boolean = false): JsonObject {
        val library = state.layout.materials
        val references = references(state)
        library.item(id)?.let { item ->
            return JsonObject(item(item, references[id].orEmpty().size) + buildJsonObject {
                put("type", "item")
                putJsonArray("layers") { references[id].orEmpty().sorted().forEach { add(JsonPrimitive(it)) } }
                putJsonArray("folder_path") { library.path(item.folderId).forEach { add(JsonPrimitive(it)) } }
                if (item.kind == MaterialKind.VECTOR) put("vector_size", JsonArray(WorkspaceMaterialFiles.svgSize(requireNotNull(item.svg)).toList().map(::JsonPrimitive)))
                if (includeSvg) item.svg?.let { put("svg", it) }
            })
        }
        val folder = library.folder(id) ?: throw IllegalArgumentException("Material not found: $id")
        return JsonObject(folder(folder, library) + buildJsonObject {
            put("type", "folder")
            putJsonArray("folder_path") { library.path(folder.id).forEach { add(JsonPrimitive(it)) } }
        })
    }

    /**
     * The layers a folder exports as: each item at its placement, or in a row from the corner when it has none, its
     * subfolders as groups. The canvas is [canvasWidth] x [canvasHeight], grown to hold them.
     */
    fun layers(library: MaterialLibrary, folderId: String, canvasWidth: Int, canvasHeight: Int): SourceArt {
        val folders = library.subtree(folderId)
        val items = library.items.filter { it.folderId in folders }
        require(items.isNotEmpty()) { "The folder holds no material: $folderId" }
        val root = library.path(folderId).size
        var cursor = 0f
        val placed = items.map { item ->
            val rect = item.placement ?: LayerCanvasRect(cursor, 0f, item.raster.width.toFloat(), item.raster.height.toFloat()).also { cursor += it.width }
            item to rect
        }
        val width = maxOf(canvasWidth, ceil(placed.maxOf { it.second.right }.toDouble()).toInt())
        val height = maxOf(canvasHeight, ceil(placed.maxOf { it.second.bottom }.toDouble()).toInt())
        val groups = LinkedHashMap<String, SourceGroup>()
        val layers = placed.mapIndexed { index, (item, rect) ->
            val path = library.path(item.folderId).drop(root)
            for (depth in 1..path.size) {
                val prefix = path.take(depth).joinToString("/")
                groups.getOrPut(prefix) { WorkspaceSourceGroup(prefix, path[depth - 1], true, 1f, false, LayerBlend.Normal, true) }
            }
            val bounds = WorkspaceMaterialEdits.enclosing(rect)
            WorkspaceSourceLayer(LayerId(item.id), item.name, path.joinToString("/"), SourceLayerKind.Raster, item.visible, placed.lastIndex - index,
                bounds, item.opacity, false, LayerBlend.Normal, ChannelMask.ALL, item.raster, null, null, false,
                rect = rect.takeUnless { it == LayerCanvasRect.of(bounds) })
        }
        return WorkspaceSourceArt(width, height, layers, groups.values.toList())
    }

    /** A PNG of material [id] fitted into [size] pixels: an item's pixels, or a folder's items composited where they sit. */
    fun preview(state: WorkspaceLayoutState, id: String, size: Int): Pair<JsonObject, ByteArray> {
        require(size in 16..4096) { "size must be 16..4096" }
        val library = state.layout.materials
        val image: BufferedImage = library.item(id)?.let { PreviewRenderer.rasterImage(it.raster.width, it.raster.height, it.raster.rgba) }
            ?: library.folder(id)?.let { PreviewRenderer.composite(layers(library, id, 1, 1).let { art -> cropped(art) }) }
            ?: throw IllegalArgumentException("Material not found: $id")
        val scale = minOf(1.0, size.toDouble() / maxOf(image.width, image.height))
        val width = (image.width * scale).roundToInt().coerceAtLeast(1); val height = (image.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (scale == 1.0) image else BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { out ->
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, 0, 0, width, height, null); g.dispose()
        }
        val png = ByteArrayOutputStream().use { out -> javax.imageio.ImageIO.write(scaled, "png", out); out.toByteArray() }
        return buildJsonObject { put("id", id); put("width", width); put("height", height) } to png
    }

    /** Item [id]'s own pixels as a PNG, unscaled. */
    fun png(state: WorkspaceLayoutState, id: String): Triple<ByteArray, Int, Int> {
        val item = state.layout.materials.item(id) ?: throw IllegalArgumentException("Material item not found: $id")
        val image = PreviewRenderer.rasterImage(item.raster.width, item.raster.height, item.raster.rgba)
        return Triple(ByteArrayOutputStream().use { out -> javax.imageio.ImageIO.write(image, "png", out); out.toByteArray() }, item.raster.width, item.raster.height)
    }

    /** [art] cropped to the union of its layers, moved to the origin. */
    private fun cropped(art: SourceArt): SourceArt {
        val left = art.layers.minOf { it.bounds.left }; val top = art.layers.minOf { it.bounds.top }
        val right = art.layers.maxOf { it.bounds.left + it.bounds.width }; val bottom = art.layers.maxOf { it.bounds.top + it.bounds.height }
        return WorkspaceSourceArt(right - left, bottom - top, art.layers.map { layer ->
            (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).let { copy ->
                copy.copy(bounds = LayerBounds(layer.bounds.left - left, layer.bounds.top - top, layer.bounds.width, layer.bounds.height),
                    rect = copy.rect?.let { LayerCanvasRect(it.left - left, it.top - top, it.width, it.height) })
            }
        }, art.groups)
    }

    /** The PSD bytes of folder [id]: its items as layers where they sit, its subfolders as groups. */
    fun psd(state: WorkspaceLayoutState, id: String): Pair<ByteArray, Int> {
        val library = state.layout.materials
        requireNotNull(library.folder(id)) { "Material folder not found: $id" }
        val art = layers(library, id, state.canvasWidth, state.canvasHeight)
        // Painter's order: bottom first.
        val ordered = art.layers.sortedByDescending { it.order }
        return org.umamo.format.psd.PsdWriter.write(art.widthPx, art.heightPx, ordered, art.groups, fitToBounds = true) to ordered.size
    }
}
