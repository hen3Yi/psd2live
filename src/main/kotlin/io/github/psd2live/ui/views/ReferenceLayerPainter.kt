package io.github.psd2live.ui.views

import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.RigPreviewModel
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.umamo.format.art.LayerRaster
import org.umamo.render.eval.DeformedGeometry
import java.util.WeakHashMap

/**
 * Draws a model's reference layers ([RigPreviewModel.referenceLayerIds]) on the canvas, apart from the artwork pass:
 * each layer's pixels as one image over its quad. An image is made once per raster and kept while the raster is, so
 * Skia uploads it to the GPU once; edits elsewhere neither repack nor upload anything.
 */
internal object ReferenceLayerPainter {
	/** The longest side a reference image keeps; larger ones are shrunk once, as only a look is needed. */
	private const val MAX_SIDE = 4096
	private val images = WeakHashMap<ByteArray, Image>()
	private val sampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

	private fun image(raster: LayerRaster): Image = synchronized(images) {
		images.getOrPut(raster.rgba) {
			val full = Image.makeRaster(ImageInfo(raster.width, raster.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), raster.rgba, raster.width * 4)
			val scale = MAX_SIDE.toFloat() / maxOf(raster.width, raster.height)
			if (scale >= 1f) full else {
				val w = maxOf(1, (raster.width * scale).toInt()); val h = maxOf(1, (raster.height * scale).toInt())
				Surface.makeRasterN32Premul(w, h).use { surface ->
					surface.canvas.drawImageRect(full, Rect.makeWH(raster.width.toFloat(), raster.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
						SamplingMode.MITCHELL, null, true)
					full.close()
					surface.makeImageSnapshot()
				}
			}
		}
	}

	/**
	 * Paints [model]'s reference layers in [visibleLayerIds] (all when null) onto [canvas], back to front as the stack
	 * runs, each over where [geometry] puts its quad, at its opacity.
	 */
	fun paint(canvas: Canvas, model: RigPreviewModel, geometry: DeformedGeometry, viewport: CanvasViewport, visibleLayerIds: Set<String>?) {
		val ids = model.referenceLayerIds
		if (ids.isEmpty()) return
		val layers = model.analysis.source.layers.filter { it.id.raw in ids && (visibleLayerIds == null || it.id.raw in visibleLayerIds) }
			.sortedByDescending { it.order }
		val quadOf = model.rig.puppet.drawables.associateBy { model.rig.layerIdByDrawableId[it.id.raw] }
		for (layer in layers) {
			val quad = quadOf[layer.id.raw] ?: continue
			val world = geometry.worldPositions[quad.id] ?: continue
			val opacity = (geometry.opacity[quad.id] ?: quad.opacity).coerceIn(0f, 1f)
			if (opacity <= 0.001f || layer.raster.width <= 0 || layer.raster.height <= 0) continue
			val image = image(layer.raster)
			fun sx(i: Int) = viewport.x(world[i * 2]).toFloat()
			fun sy(i: Int) = viewport.yFromWorld(world[i * 2 + 1]).toFloat()
			// The image's corners onto the quad's: (0, 0) on the first corner, its width along the second, its height along the fourth.
			val w = image.width.toFloat(); val h = image.height.toFloat()
			val matrix = Matrix33((sx(1) - sx(0)) / w, (sx(3) - sx(0)) / h, sx(0), (sy(1) - sy(0)) / w, (sy(3) - sy(0)) / h, sy(0), 0f, 0f, 1f)
			canvas.save()
			canvas.concat(matrix)
			Paint().use { paint ->
				paint.alpha = (opacity * 255f).toInt()
				canvas.drawImageRect(image, Rect.makeWH(w, h), Rect.makeWH(w, h), sampling, paint, true)
			}
			canvas.restore()
		}
	}
}
