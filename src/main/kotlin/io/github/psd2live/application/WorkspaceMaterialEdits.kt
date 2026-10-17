package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Files read for import, held in this process until the edit that adds them to the document is prepared. Staged
 * content is immutable and named by its digest, so the same import stages under the same token.
 */
internal object WorkspaceMaterialStaging {
    private val staged = object : LinkedHashMap<String, List<WorkspaceMaterialFiles.Entry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<WorkspaceMaterialFiles.Entry>>?) = size > 16
    }

    fun stage(entries: List<WorkspaceMaterialFiles.Entry>): String {
        require(entries.isNotEmpty()) { "Nothing to import: no supported image in the given paths" }
        val token = StableIds.stem("staged_", JsonArray(entries.map { entry -> buildJsonObject {
            put("name", entry.name); put("folder", JsonArray(entry.folder.map(::JsonPrimitive))); put("kind", entry.kind.name)
            put("raster", WorkspaceRevisions.rasterDigest(entry.raster.rgba)); put("w", entry.raster.width); put("h", entry.raster.height)
            entry.placement?.let { put("placement", "${it.left},${it.top},${it.width},${it.height} on ${entry.placementWidth}x${entry.placementHeight}") }
            entry.svg?.let { put("svg", WorkspaceRevisions.sha256(it.encodeToByteArray())) }
            put("origin", entry.origin); put("opacity", entry.opacity); put("visible", entry.visible)
            if (entry.tags.isNotEmpty()) put("tags", JsonArray(entry.tags.map(::JsonPrimitive)))
            if (entry.reference) put("reference", true)
        } }))
        synchronized(staged) { staged[token] = entries }
        return token
    }

    fun get(token: String): List<WorkspaceMaterialFiles.Entry> = synchronized(staged) { staged[token] }
        ?: throw IllegalArgumentException("The staged import $token is no longer held; import the files again")
}

/**
 * The material library and placing materials on the canvas, as pure candidate edits. The library is document data
 * ([MaterialLibrary]): every edit of it is one history node like any other. A placed layer is a source layer showing a
 * material's pixels ([WorkspaceLayout.layerMaterials]); after the first generation it waits for the next one.
 */
internal object WorkspaceMaterialEdits {
    const val ADD = "material_add"
    const val FOLDER = "material_folder_create"
    const val MOVE = "material_move"
    const val RENAME = "material_rename"
    const val DELETE = "material_delete"
    const val TAG = "material_tag"
    const val RESOLUTION = "material_set_resolution"
    const val PLACE = "layer_place"
    const val UNLINK = "material_unlink"
    const val REFERENCE = "layout_set_reference"
    const val CROP = "material_crop"
    const val MARK_REFERENCE = "material_mark_reference"
    val supported = setOf(ADD, FOLDER, MOVE, RENAME, DELETE, TAG, RESOLUTION, PLACE, UNLINK, REFERENCE, CROP, MARK_REFERENCE) + WorkspaceMaterialPixels.supported +
        WorkspaceDrawingEdits.supported + WorkspaceMaterialPeel.supported
    private const val MAX_NAME = 128

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel,
              work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        val request = operation.request
        if (operation.operation in WorkspaceMaterialPixels.supported) return WorkspaceMaterialPixels.apply(operation, document, model, work)
        if (operation.operation in WorkspaceDrawingEdits.supported) return WorkspaceDrawingEdits.apply(operation, document, model, work)
        if (operation.operation in WorkspaceMaterialPeel.supported) return WorkspaceMaterialPeel.apply(operation, document, work)
        fun text(key: String) = request.getValue(key).jsonPrimitive.content
        fun optional(key: String) = request[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        fun strings(key: String) = request[key]?.jsonArray?.map { it.jsonPrimitive.content }
        return when (operation.operation) {
            ADD -> add(document, WorkspaceMaterialStaging.get(text("staging")), optional("folder_id"))
            FOLDER -> createFolder(document, text("name"), optional("parent_id"), optional("id"))
            MOVE -> move(document, strings("ids").orEmpty(), optional("folder_id"))
            RENAME -> rename(document, text("id"), text("name"))
            DELETE -> delete(document, strings("ids").orEmpty())
            TAG -> tag(document, strings("ids").orEmpty(), strings("set"), strings("add").orEmpty(), strings("remove").orEmpty())
            UNLINK -> WorkspaceMaterialLinks.unlink(document, strings("layer_ids").orEmpty())
            RESOLUTION -> resolution(document, model, text("id"), request.getValue("width").jsonPrimitive.int, request.getValue("height").jsonPrimitive.int)
            PLACE -> place(document, model, text("material_id"), request["rect"]?.jsonObject?.let(::rect),
                request["at"]?.jsonArray?.map { it.jsonPrimitive.float }?.also { require(it.size == 2) { "at takes [x, y]" } },
                optional("layer_id"), request["reference"]?.jsonPrimitive?.boolean)
            REFERENCE -> reference(document, request)
            MARK_REFERENCE -> markReference(document, strings("ids").orEmpty(), request.getValue("reference").jsonPrimitive.boolean)
            CROP -> crop(document, text("id"), request["rect"]?.jsonObject?.let(::rect), request["canvas_rect"]?.jsonObject?.let(::rect),
                optional("name"), optional("new_id"), if ("folder_id" in request) optional("folder_id") else null, "folder_id" in request)
            else -> throw IllegalArgumentException("Not a material edit: ${operation.operation}")
        }
    }

    private fun rect(value: JsonObject) = LayerCanvasRect(value.getValue("left").jsonPrimitive.float, value.getValue("top").jsonPrimitive.float,
        value.getValue("width").jsonPrimitive.float, value.getValue("height").jsonPrimitive.float)

    private fun WorkspaceDocument.withLibrary(library: MaterialLibrary): WorkspaceDocument =
        if (library == layout.materials) this else copy(layout = layout.copy(materials = library))

    private fun requireName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= MAX_NAME && it.none(Char::isISOControl)) { "A name must be 1-$MAX_NAME printable characters" }
    }

    private fun requireFolder(library: MaterialLibrary, id: String?) {
        if (id != null) requireNotNull(library.folder(id)) { "Material folder not found: $id" }
    }

    private fun taken(library: MaterialLibrary): (String) -> Boolean {
        val ids = library.folders.mapTo(HashSet()) { it.id } + library.items.map { it.id }
        return { it in ids }
    }

    /** [entries] added under [folderId], each under its folder path, the folders made as needed. */
    fun add(document: WorkspaceDocument, entries: List<WorkspaceMaterialFiles.Entry>, folderId: String?): WorkspaceDocument {
        var library = document.layout.materials
        requireFolder(library, folderId)
        val folders = ArrayList(library.folders); val items = ArrayList(library.items)
        val ids = HashSet<String>().apply { folders.mapTo(this) { it.id }; items.mapTo(this) { it.id } }
        // The folder standing for a PSD or CMO3 keeps the file's name, so the browser can show what it was read from.
        fun folderFor(entry: WorkspaceMaterialFiles.Entry): String? {
            var parent = folderId
            for ((index, name) in entry.folder.withIndex()) {
                val existing = folders.firstOrNull { it.parentId == parent && it.name == name }
                parent = existing?.id ?: StableIds.fresh(StableIds.stem("folder_", parent, name)) { it in ids }.also { id ->
                    ids += id
                    folders += MaterialFolder(id, requireName(name), parent, origin = if (index == entry.fileFolder) entry.origin.substringBefore('#') else "")
                }
            }
            return parent
        }
        for (entry in entries) {
            val folder = folderFor(entry)
            val id = StableIds.fresh(StableIds.stem("mat_", folder, entry.name, WorkspaceRevisions.rasterDigest(entry.raster.rgba), entry.origin)) { it in ids }
            ids += id
            // A drawing renders with the document's drawing kit, which a file read on its own cannot know.
            val raster = entry.svg?.let { WorkspaceVectorDrawing.render(it, document.layout.drawingKit, entry.raster.width, entry.raster.height) } ?: entry.raster
            items += MaterialItem(id, requireName(entry.name), folder, entry.kind, raster, entry.placement, entry.svg,
                origin = entry.origin, opacity = entry.opacity, visible = entry.visible,
                placementWidth = entry.placementWidth, placementHeight = entry.placementHeight, tags = entry.tags, reference = entry.reference)
        }
        library = MaterialLibrary(folders, items)
        return document.withLibrary(library)
    }

    /**
     * [document] with its source layers added as materials in a folder named [folderName], after [fileName] unless
     * given (its groups as subfolders), each layer linked to its own material ([WorkspaceLayout.layerMaterials]). The
     * materials share the layers' pixels.
     */
    fun addLinked(document: WorkspaceDocument, fileName: String,
                  folderName: String = fileName.substringBeforeLast('.').ifEmpty { fileName }): WorkspaceDocument {
        val layers = document.source.layers.filter { it.raster.width > 0 && it.raster.height > 0 }
        if (layers.isEmpty()) return document
        // Entries come in the layers' order; a reference layer's material places as a reference again.
        val entries = WorkspaceMaterialFiles.layered(document.source, listOf(WorkspaceMaterialFiles.name(folderName, "source")), fileName)
            .mapIndexed { index, entry -> entry.copy(fileFolder = 0, reference = layers.getOrNull(index)?.id?.raw in document.layout.referenceLayerIds) }
        val before = document.layout.materials.items.size
        val added = add(document, entries, null)
        val items = added.layout.materials.items.drop(before)
        check(items.size == layers.size) { "Every layer becomes one material" }
        return added.copy(layout = added.layout.copy(layerMaterials = added.layout.layerMaterials + layers.map { it.id.raw }.zip(items.map { it.id })))
    }

    fun createFolder(document: WorkspaceDocument, name: String, parentId: String?, id: String?): WorkspaceDocument {
        val library = document.layout.materials
        requireFolder(library, parentId)
        val folderId = id?.also { require(!taken(library)(it) && it.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) { "Folder id is taken or invalid: $it" } }
            ?: StableIds.fresh(StableIds.stem("folder_", parentId, name)) { taken(library)(it) }
        return document.withLibrary(library.copy(folders = library.folders + MaterialFolder(folderId, requireName(name), parentId)))
    }

    fun move(document: WorkspaceDocument, ids: List<String>, folderId: String?): WorkspaceDocument {
        require(ids.size in 1..1024 && ids.distinct().size == ids.size) { "Give 1..1024 distinct ids" }
        val library = document.layout.materials
        requireFolder(library, folderId)
        ids.forEach { id ->
            require(library.item(id) != null || library.folder(id) != null) { "Material not found: $id" }
            if (library.folder(id) != null) require(folderId == null || folderId !in library.subtree(id)) { "A folder cannot move into itself" }
        }
        val moving = ids.toSet()
        return document.withLibrary(MaterialLibrary(
            library.folders.map { if (it.id in moving) it.copy(parentId = folderId) else it },
            library.items.map { if (it.id in moving) it.copy(folderId = folderId) else it }))
    }

    fun rename(document: WorkspaceDocument, id: String, name: String): WorkspaceDocument {
        val library = document.layout.materials
        val trimmed = requireName(name)
        return when {
            library.item(id) != null -> document.withLibrary(library.copy(items = library.items.map { if (it.id == id) it.copy(name = trimmed) else it }))
            library.folder(id) != null -> document.withLibrary(library.copy(folders = library.folders.map { if (it.id == id) it.copy(name = trimmed) else it }))
            else -> throw IllegalArgumentException("Material not found: $id")
        }
    }

    /** The layers showing [itemIds], by item. */
    fun references(document: WorkspaceDocument, itemIds: Set<String>): Map<String, List<String>> {
        val present = document.source.layers.mapTo(HashSet()) { it.id.raw } - document.deletedLayerIds
        return document.layout.layerMaterials.filter { (layer, item) -> item in itemIds && layer in present }.entries
            .groupBy({ it.value }, { it.key }).mapValues { it.value.sorted() }
    }

    /** [ids] - items and folders with everything in them - removed; refused while a layer shows one of the items. */
    fun delete(document: WorkspaceDocument, ids: List<String>): WorkspaceDocument {
        require(ids.size in 1..1024 && ids.distinct().size == ids.size) { "Give 1..1024 distinct ids" }
        val library = document.layout.materials
        val folders = HashSet<String>(); val items = HashSet<String>()
        for (id in ids) when {
            library.folder(id) != null -> folders += library.subtree(id)
            library.item(id) != null -> items += id
            else -> throw IllegalArgumentException("Material not found: $id")
        }
        library.items.filter { it.folderId in folders }.mapTo(items) { it.id }
        val used = references(document, items)
        document.layout.reference?.materialId?.let { require(it !in items) { "The reference image shows $it; clear or change it with layout_set_reference first" } }
        require(used.isEmpty()) {
            "Materials in use cannot be deleted; delete these layers first: " + used.entries.joinToString("; ") { (item, layers) -> "$item by ${layers.joinToString()}" }
        }
        // A deleted layer's link goes with the material; undoing the deletion brings both back.
        val links = document.layout.layerMaterials.filterValues { it !in items }
        return document.copy(layout = document.layout.copy(layerMaterials = links,
            materials = MaterialLibrary(library.folders.filter { it.id !in folders }, library.items.filter { it.id !in items })))
    }

    fun tag(document: WorkspaceDocument, ids: List<String>, set: List<String>?, add: List<String>, remove: List<String>): WorkspaceDocument {
        require(ids.size in 1..1024 && ids.distinct().size == ids.size) { "Give 1..1024 distinct ids" }
        require(set != null || add.isNotEmpty() || remove.isNotEmpty()) { "Give set, add or remove" }
        require(set == null || (add.isEmpty() && remove.isEmpty())) { "set replaces the tags; do not combine it with add or remove" }
        fun normalized(values: List<String>) = values.map { LayerTags.normalize(it).also(LayerTags::requireValid) }.toSet()
        val replacing = set?.let(::normalized); val adding = normalized(add); val removing = normalized(remove)
        fun next(tags: Set<String>): Set<String> = (replacing ?: (tags - removing + adding)).also {
            require(!(LayerTags.LEFT in it && LayerTags.RIGHT in it)) { "Tags hold left or right, not both" }
        }
        val library = document.layout.materials
        ids.forEach { require(library.item(it) != null || library.folder(it) != null) { "Material not found: $it" } }
        val targets = ids.toSet()
        return document.withLibrary(MaterialLibrary(
            library.folders.map { if (it.id in targets) it.copy(tags = next(it.tags)) else it },
            library.items.map { if (it.id in targets) it.copy(tags = next(it.tags)) else it }))
    }

    /** Items [ids] - and every item in folders among them - placed as reference layers by default ([reference] true) or not. */
    fun markReference(document: WorkspaceDocument, ids: List<String>, reference: Boolean): WorkspaceDocument {
        require(ids.size in 1..1024 && ids.distinct().size == ids.size) { "Give 1..1024 distinct ids" }
        val library = document.layout.materials
        val folders = HashSet<String>(); val items = HashSet<String>()
        for (id in ids) when {
            library.folder(id) != null -> folders += library.subtree(id)
            library.item(id) != null -> items += id
            else -> throw IllegalArgumentException("Material not found: $id")
        }
        return document.withLibrary(library.copy(items = library.items.map {
            if ((it.id in items || it.folderId in folders) && it.reference != reference) it.copy(reference = reference) else it
        }))
    }

    /**
     * Vector item [id] rendered at [width] x [height] pixels; every layer showing it takes the new raster over its
     * unchanged rectangle, as an image replacement does.
     */
    fun resolution(document: WorkspaceDocument, model: RigPreviewModel, id: String, width: Int, height: Int): WorkspaceDocument {
        val library = document.layout.materials
        val item = library.item(id) ?: throw IllegalArgumentException("Material not found: $id")
        require(item.kind == MaterialKind.VECTOR) { "Only a vector material has a resolution: $id" }
        if (item.raster.width == width && item.raster.height == height) return document
        val raster = WorkspaceVectorDrawing.render(requireNotNull(item.svg), document.layout.drawingKit, width, height)
        var next = document.withLibrary(library.copy(items = library.items.map { if (it.id == id) it.copy(raster = raster) else it }))
        for (layer in references(document, setOf(id))[id].orEmpty()) next = WorkspaceDocumentEdits.replaceImage(next, model, layer, raster)
        return next
    }

    /**
     * Material [materialId] placed on the canvas as new source layers: an item as one layer, a folder as one layer per
     * item in it, each folder becoming a source group. The default size is the item's own placement (a PSD layer's
     * place), else one material pixel per canvas unit fitted into the canvas; [rect] gives an item's rectangle, [at]
     * centres the placement there. The tags of the item and of the folders above it become the layer's tags. [reference]
     * places every layer as a reference layer, or none; null follows each item ([MaterialItem.reference]).
     */
    fun place(document: WorkspaceDocument, model: RigPreviewModel, materialId: String, rect: LayerCanvasRect?, at: List<Float>?,
              layerId: String?, reference: Boolean? = null): WorkspaceDocument {
        val library = document.layout.materials
        val single = library.item(materialId)
        val folder = library.folder(materialId)
        require(single != null || folder != null) { "Material not found: $materialId" }
        require(rect == null || single != null) { "rect places a single item; place a folder at a point" }
        require(layerId == null || single != null) { "layer_id names the layer of a single item" }
        val items = single?.let(::listOf) ?: run {
            val folders = library.subtree(materialId)
            library.items.filter { it.folderId in folders }
        }
        require(items.isNotEmpty()) { "The folder holds no material: $materialId" }
        val width = document.source.widthPx.toFloat(); val height = document.source.heightPx.toFloat()
        // A PSD layer keeps its place in the PSD, the PSD's canvas centred on this one and shrunk to fit it: the
        // same place when both canvases are one size. A place on a canvas of unknown size is centred as a whole.
        fun fitted(cw: Float, ch: Float, r: LayerCanvasRect): LayerCanvasRect {
            if (cw == width && ch == height) return r
            val scale = minOf(1f, width / cw, height / ch)
            val dx = (width - cw * scale) / 2f; val dy = (height - ch * scale) / 2f
            return LayerCanvasRect(dx + r.left * scale, dy + r.top * scale, r.width * scale, r.height * scale)
        }
        val unknown = items.mapNotNull { item -> item.placement?.takeIf { item.placementWidth <= 0 || item.placementHeight <= 0 } }
        val unknownSpan = if (unknown.isEmpty()) null else LayerCanvasRect(unknown.minOf { it.left }, unknown.minOf { it.top },
            unknown.maxOf { it.right } - unknown.minOf { it.left }, unknown.maxOf { it.bottom } - unknown.minOf { it.top })
        fun natural(item: MaterialItem): LayerCanvasRect {
            val place = item.placement ?: run {
                val scale = minOf(1f, width / item.raster.width, height / item.raster.height)
                val w = item.raster.width * scale; val h = item.raster.height * scale
                return LayerCanvasRect((width - w) / 2f, (height - h) / 2f, w, h)
            }
            if (item.placementWidth > 0 && item.placementHeight > 0) return fitted(item.placementWidth.toFloat(), item.placementHeight.toFloat(), place)
            val span = requireNotNull(unknownSpan)
            val shifted = LayerCanvasRect(place.left - span.left, place.top - span.top, place.width, place.height)
            return fitted(span.width, span.height, shifted)
        }
        val placements = items.associateWith { if (rect != null) rect else natural(it) }.toMutableMap()
        if (at != null && rect == null) {
            val left = placements.values.minOf { it.left }; val top = placements.values.minOf { it.top }
            val right = placements.values.maxOf { it.right }; val bottom = placements.values.maxOf { it.bottom }
            val dx = at[0] - (left + right) / 2f; val dy = at[1] - (top + bottom) / 2f
            placements.replaceAll { _, r -> LayerCanvasRect(r.left + dx, r.top + dy, r.width, r.height) }
        }
        val source = document.source
        val existing = HashSet<String>().apply { source.layers.mapTo(this) { it.id.raw }; addAll(document.deletedLayerIds) }
        var minOrder = source.layers.minOfOrNull { it.order } ?: 0
        val groups = source.groups.toMutableList()
        val root = folder?.let { library.path(it.parentId) }.orEmpty()
        val added = ArrayList<SourceLayer>()
        val links = LinkedHashMap<String, String>()
        val tags = LinkedHashMap<String, Set<String>>()
        val references = LinkedHashSet<String>()
        for (item in items) {
            val id = (if (single != null) layerId else null)?.also {
                require(it !in existing && it.matches(Regex("[A-Za-z0-9_.:#-]{1,96}"))) { "layer_id is taken or invalid: $it" }
            } ?: StableIds.fresh(StableIds.stem("placed_", item.id)) { it in existing }
            existing += id
            val place = placements.getValue(item)
            require(place.width > 0f && place.height > 0f && listOf(place.left, place.top, place.width, place.height).all(Float::isFinite)) {
                "A placement needs a finite positive size" }
            // A folder placed whole keeps its folders as source groups, under the canvas root.
            val path = if (folder != null) library.path(item.folderId).drop(root.size).joinToString("/") else ""
            if (path.isNotEmpty()) {
                val parts = path.split('/')
                for (depth in 1..parts.size) {
                    val prefix = parts.take(depth).joinToString("/")
                    if (groups.none { it.path == prefix }) groups += WorkspaceSourceGroup(prefix, parts[depth - 1], true, 1f, false, LayerBlend.Normal, true)
                }
            }
            val bounds = enclosing(place)
            added += WorkspaceSourceLayer(LayerId(id), item.name, path, SourceLayerKind.Raster, item.visible, --minOrder, bounds,
                item.opacity, false, LayerBlend.Normal, ChannelMask.ALL, item.raster, null, null, false,
                rect = place.takeUnless { it == LayerCanvasRect.of(bounds) })
            links[id] = item.id
            if (reference ?: item.reference) references += id
            tags[id] = library.ancestry(item.folderId).flatMapTo(LinkedHashSet()) { it.tags } + item.tags
        }
        // Placed last, they paint over every layer already there.
        val art = WorkspaceSourceArt(source.widthPx, source.heightPx, source.layers + added, groups)
        val generation = document.layout.generation
        var next = document.copy(source = art, layerVisibility = document.layerVisibility,
            layout = document.layout.copy(layerMaterials = document.layout.layerMaterials + links,
                // A reference layer never waits for a generation: none reads it.
                generation = if (generation.generated) generation.copy(pendingLayerIds = generation.pendingLayerIds + (links.keys - references)) else generation,
                referenceLayerIds = document.layout.referenceLayerIds + references))
        // The tags the item and its folders carry: semantic words classify the layer, the rest stay as the user's tags.
        for ((id, set) in tags) {
            if (set.isEmpty()) continue
            val resolution = LayerTags.resolve(set)
            if (resolution.tag != SemanticTag.UNKNOWN || resolution.side != Side.NONE)
                next = next.copy(layerOverrides = next.layerOverrides + (id to LayerClassificationOverride(LayerType.PRESET, resolution.tag, resolution.side)))
            if (resolution.user.isNotEmpty()) next = next.copy(layout = next.layout.copy(layerTags = next.layout.layerTags + (id to resolution.user)))
        }
        return next
    }

    /**
     * The reference image set, changed or cleared ([LayoutReference]). A new material without a rect is fitted
     * into the canvas and centred, scaled up or down; omitted fields keep their values.
     */
    fun reference(document: WorkspaceDocument, request: JsonObject): WorkspaceDocument {
        val current = document.layout.reference
        val materialField = request["material_id"]
        if (materialField is JsonNull) return if (current == null) document else document.copy(layout = document.layout.copy(reference = null))
        val materialId = materialField?.jsonPrimitive?.content ?: current?.materialId
            ?: throw IllegalArgumentException("No reference is set; give material_id")
        val item = document.layout.materials.item(materialId) ?: throw IllegalArgumentException("Material item not found: $materialId")
        val width = document.source.widthPx.toFloat(); val height = document.source.heightPx.toFloat()
        val rect = request["rect"]?.jsonObject?.let(::rect)
            ?: current?.rect?.takeIf { current.materialId == materialId }
            ?: run {
                val scale = minOf(width / item.raster.width, height / item.raster.height)
                val w = item.raster.width * scale; val h = item.raster.height * scale
                LayerCanvasRect((width - w) / 2f, (height - h) / 2f, w, h)
            }
        require(rect.width > 0f && rect.height > 0f && listOf(rect.left, rect.top, rect.width, rect.height).all(Float::isFinite)) { "rect needs a finite positive size" }
        val opacity = request["opacity"]?.jsonPrimitive?.float ?: current?.opacity ?: 0.5f
        require(opacity in 0f..1f) { "opacity is 0..1" }
        val visible = request["visible"]?.jsonPrimitive?.boolean ?: current?.visible ?: true
        val next = LayoutReference(materialId, rect, opacity, visible)
        return if (next == current) document else document.copy(layout = document.layout.copy(reference = next))
    }

    /**
     * A new item holding the pixels of [rect] (the item's pixels) or of [canvasRect] (canvas units, through where the
     * item sits as the reference image, else its own placement on this canvas) of item [id], losslessly. The new
     * item's placement is where those pixels sit on the canvas, so placing it lands on the reference.
     */
    fun crop(document: WorkspaceDocument, id: String, rect: LayerCanvasRect?, canvasRect: LayerCanvasRect?, name: String?, newId: String?,
             folderId: String?, folderGiven: Boolean): WorkspaceDocument {
        val library = document.layout.materials
        val item = library.item(id) ?: throw IllegalArgumentException("Material item not found: $id")
        require((rect == null) != (canvasRect == null)) { "Give rect (material pixels) or canvas_rect (canvas units), not both" }
        val canvasW = document.source.widthPx; val canvasH = document.source.heightPx
        // Where the item's pixels sit on this canvas: the reference rectangle, else its own placement made for this canvas.
        val sits = document.layout.reference?.takeIf { it.materialId == id }?.rect
            ?: item.placement?.takeIf { item.placementWidth == canvasW && item.placementHeight == canvasH }
        val sx = sits?.let { it.width / item.raster.width }; val sy = sits?.let { it.height / item.raster.height }
        val pixels = rect ?: run {
            val on = requireNotNull(sits) { "canvas_rect needs the item to be the reference image or placed on this canvas; give rect in its pixels" }
            val c = requireNotNull(canvasRect)
            LayerCanvasRect((c.left - on.left) / sx!!, (c.top - on.top) / sy!!, c.width / sx, c.height / sy)
        }
        val left = floor(pixels.left.toDouble()).toInt().coerceIn(0, item.raster.width - 1)
        val top = floor(pixels.top.toDouble()).toInt().coerceIn(0, item.raster.height - 1)
        val right = ceil(pixels.right.toDouble() - 1e-4).toInt().coerceIn(left + 1, item.raster.width)
        val bottom = ceil(pixels.bottom.toDouble() - 1e-4).toInt().coerceIn(top + 1, item.raster.height)
        val w = right - left; val h = bottom - top
        val source = item.raster
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) System.arraycopy(source.rgba, ((top + y) * source.width + left) * 4, rgba, y * w * 4, w * 4)
        val raster = LayerRaster(w, h, rgba)
        val placement = sits?.let { LayerCanvasRect(it.left + left * sx!!, it.top + top * sy!!, w * sx, h * sy) }
        val folder = if (folderGiven) folderId else item.folderId
        requireFolder(library, folder)
        val itemName = requireName(name ?: "${item.name} ${left},${top} ${w}x${h}")
        val taken = taken(library)
        val itemId = newId?.also { require(!taken(it) && it.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) { "new_id is taken or invalid: $it" } }
            ?: StableIds.fresh(StableIds.stem("mat_", id, left, top, w, h)) { taken(it) }
        val cropped = MaterialItem(itemId, itemName, folder, MaterialKind.BITMAP, raster, placement, tags = item.tags,
            origin = "crop:$id#$left,$top,$w,$h", placementWidth = if (placement != null) canvasW else 0, placementHeight = if (placement != null) canvasH else 0)
        return document.withLibrary(library.copy(items = library.items + cropped))
    }

    /** The integer bounds enclosing [rect]. */
    fun enclosing(rect: LayerCanvasRect): LayerBounds {
        val left = floor(rect.left.toDouble()).toInt(); val top = floor(rect.top.toDouble()).toInt()
        val right = maxOf(left + 1, ceil(rect.right.toDouble() - 1e-4).toInt()); val bottom = maxOf(top + 1, ceil(rect.bottom.toDouble() - 1e-4).toInt())
        return LayerBounds(left, top, right - left, bottom - top)
    }
}
