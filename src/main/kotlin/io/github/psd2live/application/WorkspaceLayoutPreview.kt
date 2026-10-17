package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * What the generators read of a document with Layout state, and how the layers they do not read yet are shown.
 *
 * Generation reads the document without its pending layers ([base]): layers placed since the last generation, or every
 * layer of a project not generated yet. Those show as plain meshes - the layer's outline at rest, at the root, without
 * parameters - built by a mesh-only generation of those layers alone ([meshModel]) and laid over the generated model
 * ([augment]). The generated model stays the model's [RigPreviewModel.layoutBase], which builds, replay and export read.
 *
 * Reference layers are not meshed from their pixels and never enter an atlas: each shows as a four-corner quad over
 * its canvas rectangle, there to be picked and moved, which the artwork pass skips; the canvas draws its pixels on
 * their own, under or over the model ([RigPreviewModel.referenceLayerIds]).
 */
internal object WorkspaceLayoutPreview {
    private val pipeline = PSD2LivePipeline()

    /**
     * The source layers [document] shows without generating them: all of them before the first generation, else those
     * placed since and the reference layers, which no generation reads.
     */
    fun pendingIds(document: WorkspaceDocument): Set<String> {
        val generation = document.layout.generation
        val ids = document.source.layers.mapTo(LinkedHashSet()) { it.id.raw }
        if (!generation.generated) return ids - document.deletedLayerIds
        return (generation.pendingLayerIds + document.layout.referenceLayerIds).filterTo(LinkedHashSet()) { it in ids }
    }

    /** The reference layers [document] shows ([WorkspaceLayout.referenceLayerIds]), present and not deleted. */
    fun referenceIds(document: WorkspaceDocument): Set<String> {
        val marked = document.layout.referenceLayerIds
        if (marked.isEmpty()) return emptySet()
        return document.source.layers.mapNotNullTo(LinkedHashSet()) { layer -> layer.id.raw.takeIf { it in marked && it !in document.deletedLayerIds } }
    }

    /** Whether [document] shows layers the generators do not read. */
    fun active(document: WorkspaceDocument): Boolean = pendingIds(document).isNotEmpty() || !document.layout.generation.generated

    /** [document] as the generators read it: without its pending layers. */
    fun base(document: WorkspaceDocument): WorkspaceDocument {
        val pending = pendingIds(document)
        if (pending.isEmpty()) return document
        return document.copy(source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
            document.source.layers.filter { it.id.raw !in pending }, document.source.groups))
    }

    /**
     * [edited] - an edit of [document]'s [base] - with [document]'s pending layers back in the source, each at the index
     * it had. The edit's own changes, its Layout state included, stay.
     */
    fun restore(document: WorkspaceDocument, edited: WorkspaceDocument): WorkspaceDocument {
        if (edited === document) return document
        val pending = pendingIds(document)
        if (pending.isEmpty()) return edited
        // An edit that left the art alone keeps the document's source object, which builds and caches compare by identity.
        val generated = document.source.layers.filter { it.id.raw !in pending }
        if (edited.source.widthPx == document.source.widthPx && edited.source.heightPx == document.source.heightPx &&
            edited.source.groups == document.source.groups && edited.source.layers == generated) return edited.copy(source = document.source)
        val layers = ArrayList(edited.source.layers.filter { it.id.raw !in pending })
        document.source.layers.forEachIndexed { index, layer -> if (layer.id.raw in pending) layers.add(index.coerceAtMost(layers.size), layer) }
        return edited.copy(source = WorkspaceSourceArt(edited.source.widthPx, edited.source.heightPx, layers, edited.source.groups))
    }

    /** Refuses journal entries [after] adds to [before] that address a pending layer's mesh: it has no rig to edit yet. */
    fun requireNoPendingMeshEdits(before: RigEditOverlay, after: RigEditOverlay) {
        val journal = after.authoringJournal
        if (journal.size <= before.authoringJournal.size) return
        val added = journal.subList(before.authoringJournal.size, journal.size)
        if (added.any { MESH_PREFIX in it.toString() }) throw WorkspaceNotGenerated("A layer placed since the last generation has no rig to edit yet; generate the rig first (rig_generate)")
    }

    /** The mesh a pending layer shows as: one per layer, named from the layer so that IDs stay put between builds. */
    fun meshId(layerId: String): String = "$MESH_PREFIX${WorkspaceRevisions.sha256(layerId.encodeToByteArray()).take(16)}"

    /** Whether [id] names a pending layer's mesh, which no rig edit may address. */
    fun isPendingMesh(id: String): Boolean = id.startsWith(MESH_PREFIX)

    const val MESH_PREFIX = "LayoutMesh"

    /**
     * A mesh-only model of [layers] alone, each layer's outline at rest at the root under [document]'s mesh settings:
     * no deformers, physics or motions, and no layer read by anything but its own mesh.
     */
    fun meshModel(document: WorkspaceDocument, layers: List<SourceLayer>, checkCancelled: () -> Unit = {}): RigPreviewModel {
        val ids = layers.mapTo(HashSet()) { it.id.raw }
        val pending = document.layout.generation.pendingClassifications
        val base = document.config()
        val config = base.copy(meshOnly = true, generateDeformers = false, generatePhysics = false, exportMotions = false,
            parentOverrides = emptyMap(), generationSource = null, meshSource = null, deletedLayerIds = emptySet(),
            layerOverrides = (base.layerOverrides + pending).filterKeys { it in ids },
            meshOverrides = base.meshOverrides.filterKeys { it in ids }, drawOrderOverrides = emptyMap(),
            textureOverrides = base.textureOverrides.filterKeys { it in ids }, atlasArrangement = null, allowEmptyArt = true,
            rigEdits = RigEditOverlay.Empty.copy(splitDrawableIds = ids.associateWith(::meshId)))
        val source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx, layers, document.source.groups)
        val built = pipeline.buildPreview(source, config, ProgressListener { _, _ -> checkCancelled() })
        // A layer moved as a whole shows where its transform puts it.
        val transforms = layers.associate { it.id.raw to it.transform }
        val rig = RasterMeshPlacement.underParents(built.rig, built.rig.puppet, { null },
            { mesh -> built.rig.layerIdByDrawableId[mesh.raw]?.let(transforms::get) ?: LayerTransform.IDENTITY }, checkCancelled)
        return built.copy(rig = rig, sources = PreviewRigSources.of(rig))
    }

    /** The placement preview of a project not generated yet: every layer as a plain mesh, the reference layers as quads. */
    fun ungenerated(document: WorkspaceDocument, checkCancelled: () -> Unit = {}): RigPreviewModel {
        val pending = pendingIds(document) - referenceIds(document)
        val meshed = meshModel(document, document.source.layers.filter { it.id.raw in pending }, checkCancelled).copy(layoutPreview = true)
        return withReferences(meshed, document)
    }

    /**
     * [base] - the generated model of [document]'s generated layers - with [document]'s pending layers laid over it as
     * plain meshes, in front of the rig: their textures on pages after [base]'s, their layers listed after its layers.
     */
    fun augment(base: RigPreviewModel, document: WorkspaceDocument, checkCancelled: () -> Unit = {}): RigPreviewModel {
        val references = referenceIds(document)
        val meshed = meshed(base, document, references, checkCancelled)
        return if (references.isEmpty()) meshed else withReferences(meshed.copy(layoutBase = base), document)
    }

    /** [base] with [document]'s pending layers but [references] as plain meshes, or [base] itself when there are none. */
    private fun meshed(base: RigPreviewModel, document: WorkspaceDocument, references: Set<String>, checkCancelled: () -> Unit): RigPreviewModel {
        val pending = pendingIds(document) - references
        val layers = document.source.layers.filter { it.id.raw in pending }
        if (layers.isEmpty()) return base
        val extra = meshModel(document, layers, checkCancelled)
        val shift = base.atlas.pages.size
        val atlas = base.atlas.copy(pages = base.atlas.pages + extra.atlas.pages,
            placementByLayerId = base.atlas.placementByLayerId + extra.atlas.placementByLayerId.mapValues { it.value.copy(page = it.value.page + shift) })
        val basePuppet = base.rig.puppet
        val extraPuppet = extra.rig.puppet
        val tiles = extraPuppet.atlas.tiles.map { tile -> tile.copy(placement = tile.placement?.let { it.copy(pageIndex = it.pageIndex + shift) }) }
        val puppetAtlas = basePuppet.atlas.copy(
            pages = basePuppet.atlas.pages + extra.atlas.pages.map { AtlasPage(it.image.width, it.image.height) },
            tiles = basePuppet.atlas.tiles + tiles.filter { tile -> basePuppet.atlas.tiles.none { it.id == tile.id } })
        val sources = basePuppet.sources.map { source ->
            val added = extraPuppet.sources.firstOrNull { it.id == source.id }?.layers.orEmpty().filter { layer -> source.layers.none { it.key == layer.key } }
            source.copy(layers = source.layers + added)
        } + extraPuppet.sources.filter { extraSource -> basePuppet.sources.none { it.id == extraSource.id } }
        // In front of the rig: top of the panel, drawn last.
        val drawables = extraPuppet.drawables.filter { it.mesh != null }.map { it.copy(texturePage = it.texturePage + shift, drawOrder = 1000f) }
        val ids = drawables.mapTo(HashSet()) { it.id }
        require(basePuppet.drawables.none { it.id in ids }) { "A pending layer's mesh collides with a generated one" }
        val puppet = basePuppet.copy(drawables = basePuppet.drawables + drawables, atlas = puppetAtlas, sources = sources,
            rootChildren = drawables.map { OrgChild.Drawable(it.id) } + basePuppet.rootChildren).withDerivedRenderRoot()
        val raw = ids.map(DrawableId::raw)
        val rig = base.rig.copy(puppet = puppet,
            layerIdByDrawableId = base.rig.layerIdByDrawableId + extra.rig.layerIdByDrawableId.filterKeys { it in raw },
            sourceBoundsByDrawableId = base.rig.sourceBoundsByDrawableId + extra.rig.sourceBoundsByDrawableId.filterKeys { it in raw },
            pageByDrawableId = base.rig.pageByDrawableId + drawables.associate { it.id.raw to it.texturePage })
        val analysis = base.analysis.copy(source = document.source,
            layers = base.analysis.layers + extra.analysis.layers.filter { it.source.id.raw in pending })
        return base.copy(analysis = analysis, atlas = atlas, rig = rig, layoutBase = base)
    }

    /** The static draw order of a reference layer's quad: behind everything, so a click on a part picks the part. */
    const val REFERENCE_DRAW_ORDER = 0f

    /**
     * [model] with [document]'s reference layers as quads: four corners over each layer's canvas rectangle where its
     * transform shows it, texture coordinates over the whole image, no atlas page. Their classification joins the
     * analysis, so the layer table lists them; the canvas finds their pixels in [RigPreviewModel.analysis]'s source.
     */
    private fun withReferences(model: RigPreviewModel, document: WorkspaceDocument): RigPreviewModel {
        val ids = referenceIds(document)
        if (ids.isEmpty()) return model
        val layers = document.source.layers.filter { it.id.raw in ids }
        val config = document.config().let { it.copy(layerOverrides = it.layerOverrides + document.layout.generation.pendingClassifications) }
        val bounds = LinkedHashMap<String, Bounds>()
        val quads = layers.map { layer ->
            val r = layer.canvasRect()
            val canvas = RasterMeshPlacement.shown(floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom), layer.transform)
            val id = meshId(layer.id.raw)
            bounds[id] = Bounds(r.left, r.top, r.right, r.bottom)
            // Root meshes are in canvas units with y up.
            Drawable(DrawableId(id), layer.name, null, BlendMode.Normal, emptyList(),
                DrawableMesh(FloatArray(8) { if (it % 2 == 1) -canvas[it] else canvas[it] }, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3)),
                null, drawOrder = REFERENCE_DRAW_ORDER, opacity = layer.opacity)
        }
        val puppet = model.rig.puppet
        require(puppet.drawables.none { drawable -> quads.any { it.id == drawable.id } }) { "A reference layer's quad collides with a mesh" }
        val rig = model.rig.copy(
            puppet = puppet.copy(drawables = puppet.drawables + quads, rootChildren = puppet.rootChildren + quads.map { OrgChild.Drawable(it.id) }).withDerivedRenderRoot(),
            layerIdByDrawableId = model.rig.layerIdByDrawableId + quads.zip(layers).associate { (quad, layer) -> quad.id.raw to layer.id.raw },
            sourceBoundsByDrawableId = model.rig.sourceBoundsByDrawableId + bounds)
        val analysis = model.analysis.copy(source = document.source,
            layers = model.analysis.layers.filter { it.source.id.raw !in ids } + layers.map { CharacterAnalyzer.classify(it, config) })
        return model.copy(analysis = analysis, rig = rig, referenceLayerIds = ids)
    }
}
