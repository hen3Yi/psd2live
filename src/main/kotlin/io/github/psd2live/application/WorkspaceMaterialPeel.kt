package io.github.psd2live.application

import io.github.psd2live.core.StableIds
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.MaterialItem
import io.github.psd2live.project.MaterialKind
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Splitting a part off an image with a generated version that has it removed ("peeling"): the part covers where the
 * two differ, and the image under it is the original with only that area taken from the generated
 * version. Generators redraw the whole image slightly, so everything outside the part keeps the original's pixels
 * and putting the part back over the image under it gives the original again. Both results are new items placed
 * where the original sits, so each step of a peel works on the image the step before left.
 */
internal object WorkspaceMaterialPeel {
    const val PEEL = "material_peel"
    const val PLACEMENT = "material_set_placement"
    val supported = setOf(PEEL, PLACEMENT)
    const val MAX_RADIUS = 64

    data class Options(val low: Float = 8f, val high: Float = 30f, val grow: Int = 0, val core: Int = 0, val close: Int = 2,
                       val colorMatch: Boolean = true, val band: Int = 24) {
        init {
            require(low >= 0f && high > low && high <= 255f) { "threshold needs 0 <= low < high <= 255" }
            require(listOf(grow, core, close, band).all { it in 0..MAX_RADIUS }) { "grow, core, close and band are 0..$MAX_RADIUS pixels" }
        }
    }

    class Result(val part: LayerRaster, val base: LayerRaster, val coverage: Float, val matched: Boolean)

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, work: WorkspaceRasterWork): WorkspaceDocument {
        val request = operation.request
        fun optional(key: String) = request[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        return when (operation.operation) {
            PEEL -> peel(document, request, work)
            PLACEMENT -> placement(document, request.getValue("id").jsonPrimitive.content,
                request["canvas_rect"]?.takeUnless { it is JsonNull }?.jsonObject?.let { r ->
                    LayerCanvasRect(r.getValue("left").jsonPrimitive.float, r.getValue("top").jsonPrimitive.float,
                        r.getValue("width").jsonPrimitive.float, r.getValue("height").jsonPrimitive.float) },
                optional("from"), "canvas_rect" in request && request["canvas_rect"] is JsonNull)
            else -> throw IllegalArgumentException("Not a peel edit: ${operation.operation}")
        }
    }

    /** Item [id] placed at [rect], or where item [from] sits (scaled to its own pixels by the rectangle), or nowhere. */
    fun placement(document: WorkspaceDocument, id: String, rect: LayerCanvasRect?, from: String?, clear: Boolean): WorkspaceDocument {
        val library = document.layout.materials
        val item = library.item(id) ?: throw IllegalArgumentException("Material item not found: $id")
        require(listOf(rect != null, from != null, clear).count { it } == 1) { "Give canvas_rect, from, or canvas_rect null to clear" }
        val canvasW = document.source.widthPx; val canvasH = document.source.heightPx
        val place = when {
            clear -> null
            rect != null -> rect
            else -> {
                val other = library.item(from!!) ?: throw IllegalArgumentException("Material item not found: $from")
                requireNotNull(other.placement?.takeIf { other.placementWidth == canvasW && other.placementHeight == canvasH }) {
                    "$from has no place on this canvas" }
            }
        }
        place?.let { require(it.width > 0f && it.height > 0f && listOf(it.left, it.top, it.width, it.height).all(Float::isFinite)) { "canvas_rect needs a finite positive size" } }
        val next = item.copy(placement = place, placementWidth = if (place != null) canvasW else 0, placementHeight = if (place != null) canvasH else 0)
        if (next == item) return document
        return document.copy(layout = document.layout.copy(materials = library.copy(items = library.items.map { if (it.id == id) next else it })))
    }

    private fun peel(document: WorkspaceDocument, request: JsonObject, work: WorkspaceRasterWork): WorkspaceDocument {
        val library = document.layout.materials
        fun item(key: String) = request[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let { id ->
            library.item(id) ?: throw IllegalArgumentException("Material item not found: $id") }
        val source = requireNotNull(item("id"))
        val revealed = requireNotNull(item("revealed"))
        val region = item("region")
        for (other in listOfNotNull(revealed, region)) require(other.raster.width == source.raster.width && other.raster.height == source.raster.height) {
            "${other.id} is ${other.raster.width}x${other.raster.height}; it must be the size of ${source.id} (${source.raster.width}x${source.raster.height})" }
        val threshold = request["threshold"]?.jsonArray?.map { it.jsonPrimitive.float }
        val options = Options(low = threshold?.get(0) ?: 8f, high = threshold?.get(1) ?: 30f,
            grow = request["grow"]?.jsonPrimitive?.int ?: 0, core = request["core"]?.jsonPrimitive?.int ?: 0,
            close = request["close"]?.jsonPrimitive?.int ?: 2, colorMatch = request["color_match"]?.jsonPrimitive?.boolean ?: true,
            band = request["band"]?.jsonPrimitive?.int ?: 24)
        val result = peel(source.raster, revealed.raster, region?.raster, options, work::checkpoint)
        require(result.coverage > 0f) { "${source.id} and ${revealed.id} do not differ above the threshold${if (region != null) " inside the region" else ""}; nothing to peel" }
        val ids = library.folders.mapTo(HashSet()) { it.id } + library.items.map { it.id }
        fun fresh(key: String, suffix: String): String = request[key]?.jsonPrimitive?.content?.also {
            require(it !in ids && it.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) { "$key is taken or invalid: $it" }
        } ?: StableIds.fresh(StableIds.stem("mat_", source.id, revealed.id, region?.id, suffix)) { it in ids }
        val partId = fresh("part_id", "part")
        val baseId = fresh("base_id", "base").also { require(it != partId) { "part_id and base_id must differ" } }
        val name = request["name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: source.name
        fun derived(id: String, itemName: String, raster: LayerRaster, tags: Set<String>) = MaterialItem(id, itemName.take(128), source.folderId,
            MaterialKind.BITMAP, raster, source.placement, tags = tags, origin = "peel:${source.id}-${revealed.id}",
            placementWidth = source.placementWidth, placementHeight = source.placementHeight)
        val part = derived(partId, name, result.part, emptySet())
        val base = derived(baseId, "${source.name} under $name", result.base, source.tags)
        return document.copy(layout = document.layout.copy(materials = library.copy(items = library.items + part + base)))
    }

    /**
     * [before] split by [after] (the same image with a part removed): the part's initial alpha comes from where the two differ
     * by more than the threshold, kept to [region] (white or opaque where the part may be) when given; the image under
     * it is [before] with the part's area taken from [after], colour-matched to the band of untouched pixels around it.
     * Foreground colour and coverage, and base alpha, are solved so normal source-over restores [before], within byte
     * rounding. A translucent original can require a more transparent base; an opaque reveal cannot sit under it unchanged.
     */
    fun peel(before: LayerRaster, after: LayerRaster, region: LayerRaster?, options: Options, checkpoint: () -> Unit = {}): Result {
        val w = before.width; val h = before.height; val n = w * h
        val b = before.rgba; val r = after.rgba
        // A generator shifts the whole image's tone a little: each channel's median offset over the pixels both show
        // is taken out before comparing, so the shift alone never reads as a part.
        val shift = IntArray(3)
        run {
            val histogram = Array(3) { IntArray(511) }; var count = 0
            for (i in 0 until n) {
                val o = i * 4
                if ((b[o + 3].toInt() and 255) < 250 || (r[o + 3].toInt() and 255) < 250) continue
                for (c in 0..2) histogram[c][(r[o + c].toInt() and 255) - (b[o + c].toInt() and 255) + 255]++
                count++
            }
            if (count > 0) for (c in 0..2) {
                var seen = 0; var k = 0
                while (seen + histogram[c][k] <= count / 2) { seen += histogram[c][k]; k++ }
                shift[c] = k - 255
            }
        }
        // Colours over black, so a transparent pixel compares by what it shows.
        val diff = FloatArray(n)
        for (i in 0 until n) {
            if (i % 65536 == 0) checkpoint()
            val o = i * 4
            val ab = (b[o + 3].toInt() and 255) / 255f; val ar = (r[o + 3].toInt() and 255) / 255f
            var d = abs(ab - ar) * 255f
            for (c in 0..2) d = max(d, abs((b[o + c].toInt() and 255) * ab - ((r[o + c].toInt() and 255) - shift[c]) * ar))
            diff[i] = d
        }
        val smooth = boxBlur(boxBlur(diff, w, h, 1, checkpoint), w, h, 1, checkpoint)
        val span = options.high - options.low
        val alpha = FloatArray(n) { ((smooth[it] - options.low) / span).coerceIn(0f, 1f) }
        if (region != null) {
            val inside = FloatArray(n) { val o = it * 4
                if ((region.rgba[o + 3].toInt() and 255) * luma(region.rgba, o) / 255f > 127f) 1f else 0f }
            val allowed = if (options.grow > 0) maxFilter(inside, w, h, options.grow, checkpoint) else inside
            for (i in 0 until n) alpha[i] *= allowed[i]
            if (options.core > 0) {
                val core = boxBlur(minFilter(inside, w, h, options.core, checkpoint), w, h, 1, checkpoint)
                for (i in 0 until n) alpha[i] = max(alpha[i], core[i])
            }
        }
        val closed = if (options.close > 0) minFilter(maxFilter(alpha, w, h, options.close, checkpoint), w, h, options.close, checkpoint) else alpha
        val coverage = closed.count { it > 0.5f }.toFloat() / n
        val matched = if (options.colorMatch) colorMatched(before, after, closed, options.band, checkpoint) else null
        val source = matched ?: r
        val part = ByteArray(n * 4); val base = ByteArray(n * 4)
        for (i in 0 until n) {
            if (i % 65536 == 0) checkpoint()
            val o = i * 4; val a = closed[i]
            val ab = (b[o + 3].toInt() and 255).toDouble()
            if (a <= 0f || ab == 0.0) {
                b.copyInto(base, o, o, o + 4)
                continue
            }
            val ar = (source[o + 3].toInt() and 255).toDouble()
            val outA = ab * (1f - a) + ar * a
            var needed = ab * a
            for (c in 0..2) {
                val original = b[o + c].toInt() and 255
                val v = if (outA > 0.0)
                    (original * ab * (1f - a) + (source[o + c].toInt() and 255) * ar * a) / outA else 0.0
                val under = (v + 0.5).toInt().coerceIn(0, 255)
                base[o + c] = under.toByte()
                // Enough foreground to keep its solved colour in [0, 255], even at high-contrast edges.
                if (under > original) needed = max(needed, ab * (under - original) / under)
                if (under < original) needed = max(needed, ab * (original - under) / (255 - under))
            }
            val pa = ceil(needed).toInt().coerceIn(1, ab.toInt())
            val ba = (if (pa == 255) outA else (ab - pa) * 255.0 / (255 - pa))
                .let { (it + 0.5).toInt().coerceIn(0, 255) }
            base[o + 3] = ba.toByte()
            part[o + 3] = pa.toByte()
            for (c in 0..2) {
                val original = b[o + c].toInt() and 255
                val under = base[o + c].toInt() and 255
                val v = (original * ab - under * ba * (1.0 - pa / 255.0)) / pa
                part[o + c] = (v + 0.5).toInt().coerceIn(0, 255).toByte()
            }
        }
        return Result(LayerRaster(w, h, part), LayerRaster(w, h, base), coverage, matched != null)
    }

    /**
     * [after]'s pixels moved to [before]'s colours: Lab mean and spread of each channel matched over the band of
     * pixels within [band] of the part that the part does not touch. Null when the band has too few opaque pixels.
     */
    private fun colorMatched(before: LayerRaster, after: LayerRaster, alpha: FloatArray, band: Int, checkpoint: () -> Unit): ByteArray? {
        if (band == 0) return null
        val w = before.width; val h = before.height; val n = w * h
        val inPart = FloatArray(n) { if (alpha[it] > 0.5f) 1f else 0f }
        val near = maxFilter(inPart, w, h, band, checkpoint)
        val touched = maxFilter(FloatArray(n) { if (alpha[it] > 0.02f) 1f else 0f }, w, h, 2, checkpoint)
        val sums = DoubleArray(12); var count = 0
        val lb = FloatArray(3); val lr = FloatArray(3)
        for (i in 0 until n) {
            if (near[i] == 0f || touched[i] > 0f) continue
            val o = i * 4
            if ((before.rgba[o + 3].toInt() and 255) < 250 || (after.rgba[o + 3].toInt() and 255) < 250) continue
            lab(before.rgba, o, lb); lab(after.rgba, o, lr)
            for (c in 0..2) { sums[c] += lb[c]; sums[3 + c] += lb[c] * lb[c]; sums[6 + c] += lr[c]; sums[9 + c] += lr[c] * lr[c] }
            count++
        }
        if (count < 64) return null
        val mb = DoubleArray(3) { sums[it] / count }; val mr = DoubleArray(3) { sums[6 + it] / count }
        val sb = DoubleArray(3) { sqrt(max(1e-6, sums[3 + it] / count - mb[it] * mb[it])) }
        val sr = DoubleArray(3) { sqrt(max(1e-6, sums[9 + it] / count - mr[it] * mr[it])) }
        // A flat band says nothing about contrast; then only the means move.
        val gain = DoubleArray(3) { if (sr[it] < 1.0 || sb[it] < 1.0) 1.0 else (sb[it] / sr[it]).coerceIn(0.5, 2.0) }
        val out = after.rgba.copyOf()
        val v = FloatArray(3)
        for (i in 0 until n) {
            if (i % 65536 == 0) checkpoint()
            if (alpha[i] <= 0f) continue
            val o = i * 4
            lab(after.rgba, o, v)
            for (c in 0..2) v[c] = ((v[c] - mr[c]) * gain[c] + mb[c]).toFloat()
            rgb(v, out, o)
        }
        return out
    }

    private fun luma(rgba: ByteArray, o: Int) = 0.299f * (rgba[o].toInt() and 255) + 0.587f * (rgba[o + 1].toInt() and 255) + 0.114f * (rgba[o + 2].toInt() and 255)

    private fun linear(v: Int): Double { val c = v / 255.0; return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
    private fun gamma(c: Double): Int = ((if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055) * 255.0 + 0.5).toInt().coerceIn(0, 255)
    private fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
    private fun fInv(t: Double) = if (t * t * t > 216.0 / 24389.0) t * t * t else (116.0 * t - 16.0) * 27.0 / 24389.0

    private fun lab(rgba: ByteArray, o: Int, out: FloatArray) {
        val r = linear(rgba[o].toInt() and 255); val g = linear(rgba[o + 1].toInt() and 255); val b = linear(rgba[o + 2].toInt() and 255)
        val x = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047); val y = f(0.2126 * r + 0.7152 * g + 0.0722 * b)
        val z = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
        out[0] = (116 * y - 16).toFloat(); out[1] = (500 * (x - y)).toFloat(); out[2] = (200 * (y - z)).toFloat()
    }

    private fun rgb(lab: FloatArray, out: ByteArray, o: Int) {
        val fy = (lab[0] + 16.0) / 116.0; val fx = fy + lab[1] / 500.0; val fz = fy - lab[2] / 200.0
        val x = fInv(fx) * 0.95047; val y = fInv(fy); val z = fInv(fz) * 1.08883
        out[o] = gamma(3.2406 * x - 1.5372 * y - 0.4986 * z).toByte()
        out[o + 1] = gamma(-0.9689 * x + 1.8758 * y + 0.0415 * z).toByte()
        out[o + 2] = gamma(0.0557 * x - 0.2040 * y + 1.0570 * z).toByte()
    }

    /** Mean over a (2r+1) square, edges clamped. */
    fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int, checkpoint: () -> Unit = {}): FloatArray =
        separable(v, w, h, r, checkpoint) { values, from, to -> var s = 0f; for (k in from..to) s += values[k]; s / (to - from + 1) }

    /** Largest value within a (2r+1) square: growing a mask by r pixels. */
    fun maxFilter(v: FloatArray, w: Int, h: Int, r: Int, checkpoint: () -> Unit = {}): FloatArray =
        separable(v, w, h, r, checkpoint) { values, from, to -> var m = values[from]; for (k in from + 1..to) m = max(m, values[k]); m }

    /** Smallest value within a (2r+1) square: shrinking a mask by r pixels. */
    fun minFilter(v: FloatArray, w: Int, h: Int, r: Int, checkpoint: () -> Unit = {}): FloatArray =
        separable(v, w, h, r, checkpoint) { values, from, to -> var m = values[from]; for (k in from + 1..to) m = min(m, values[k]); m }

    private inline fun separable(v: FloatArray, w: Int, h: Int, r: Int, checkpoint: () -> Unit,
                                 reduce: (FloatArray, Int, Int) -> Float): FloatArray {
        if (r <= 0) return v.copyOf()
        val rows = FloatArray(v.size); val line = FloatArray(max(w, h))
        for (y in 0 until h) {
            if (y % 64 == 0) checkpoint()
            System.arraycopy(v, y * w, line, 0, w)
            for (x in 0 until w) rows[y * w + x] = reduce(line, max(0, x - r), min(w - 1, x + r))
        }
        val out = FloatArray(v.size)
        for (x in 0 until w) {
            if (x % 64 == 0) checkpoint()
            for (y in 0 until h) line[y] = rows[y * w + x]
            for (y in 0 until h) out[y * w + x] = reduce(line, max(0, y - r), min(h - 1, y + r))
        }
        return out
    }
}
