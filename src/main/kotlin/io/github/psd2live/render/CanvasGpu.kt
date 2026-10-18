package io.github.psd2live.render

import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The editing canvases' and the texture atlas page's GPU drawing, inside the canvas's own draw: [draw] renders
 * the scene into the view's texture in Skia's context ([WindowGpu.turn]) and returns it for Compose to draw in
 * the same frame. Nothing is queued, read back or reprojected: the frame shown is the scene just built.
 *
 * A view whose scene key did not change draws its last texture without touching GL, so a hover or an overlay
 * redraw costs no GPU work.
 */
internal object CanvasGpu {
	private class Drawn(val key: List<Any?>, val window: WindowGpu)

	private val drawn = ConcurrentHashMap<String, Drawn>()
	/** Frames rendered per view since start, for the development tools. */
	val framesDrawn = ConcurrentHashMap<String, AtomicInteger>()

	private val _failed = androidx.compose.runtime.mutableStateOf(false)

	/**
	 * Whether a GPU draw failed (a driver that cannot make a target or compile a shader): canvases paint in software
	 * from then on. Snapshot state, read where canvases decide, so they switch on their next composition.
	 */
	val failed: Boolean get() = _failed.value

	/** Whether canvases in [window] can draw on the GPU. */
	fun available(window: Window?): Boolean = SkiaGpu.of(window) != null

	/**
	 * [viewId]'s frame for [key], rendering [scene] when the key changed; null when [window] has no GPU or is not
	 * the one rendering now (the canvas then paints in software).
	 */
	fun draw(window: Window?, viewId: String, key: List<Any?>, scene: () -> GpuScene): GpuFrame? {
		if (_failed.value) return null
		val gpu = SkiaGpu.of(window) ?: return null
		val last = drawn[viewId]
		if (last != null && last.window === gpu && last.key == key) gpu.resources.frame(viewId)?.let { return it }
		// Thrown out of the window's draw, a failure would end Skiko's frame loop and the window would stop drawing.
		return try {
			gpu.turn { resources ->
				val built = scene()
				resources.render(viewId, built.width, built.height, bottomUp = false) {
					resources.canvasRenderer.draw(viewId, built)
				}?.also {
					drawn[viewId] = Drawn(key, gpu)
					framesDrawn.getOrPut(viewId) { AtomicInteger() }.incrementAndGet()
				}
			} ?: gpu.resources.frame(viewId)
		} catch (failure: Exception) {
			System.err.println("GPU canvas failed, painting in software: $failure")
			failure.printStackTrace()
			_failed.value = true
			// Canvases paint in software from now on: free what the failed view and every drawn one hold on the GPU,
			// which a closed canvas would otherwise only free for views that once drew.
			gpu.post { it.release(viewId) }
			for (id in drawn.keys.toList()) release(id)
			null
		}
	}

	/** Forgets [viewId] and frees its GPU objects when its window next renders. */
	fun release(viewId: String) {
		val last = drawn.remove(viewId) ?: return
		last.window.post { it.release(viewId) }
	}
}
