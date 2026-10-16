package io.github.psd2live.application

import org.umamo.format.art.LayerRaster
import kotlin.math.abs
import kotlin.test.*

class WorkspaceMaterialPeelTest {
    private val size = 32

    private fun image(pixel: (Int, Int) -> List<Int>) = LayerRaster(size, size, ByteArray(size * size * 4).also { bytes ->
        for (y in 0 until size) for (x in 0 until size) {
            val rgba = pixel(x, y)
            for (c in 0..3) bytes[(y * size + x) * 4 + c] = rgba[c].toByte()
        }
    })

    private fun checkRecomposition(before: LayerRaster, result: WorkspaceMaterialPeel.Result) {
        fun byte(raster: LayerRaster, o: Int) = raster.rgba[o].toInt() and 255
        for (o in before.rgba.indices step 4) {
            val pa = byte(result.part, o + 3) / 255.0
            val ba = byte(result.base, o + 3) / 255.0
            val originalAlpha = byte(before, o + 3) / 255.0
            val alpha = pa + ba * (1 - pa)
            assertTrue(abs(alpha - originalAlpha) * 255 <= 1.0, "alpha at pixel ${o / 4}")
            for (c in 0..2) {
                val composed = byte(result.part, o + c) * pa + byte(result.base, o + c) * ba * (1 - pa)
                val expected = byte(before, o + c) * originalAlpha
                // Compare over black and white, including alpha error.
                assertTrue(abs(composed - expected) <= 1.0, "colour at ${o / 4}: $composed != $expected")
                assertTrue(abs(composed + 255 * (1 - alpha) - expected - 255 * (1 - originalAlpha)) <= 1.0)
            }
            if (byte(result.part, o + 3) == 0)
                for (c in 0..3) assertEquals(byte(before, o + c), byte(result.base, o + c), "untouched pixel ${o / 4}")
        }
    }

    @Test fun softLowContrastPartsRecomposeToTheOriginal() {
        val after = image { _, _ -> listOf(40, 100, 180, 255) }
        val before = image { x, y -> listOf(if (x in 10..21 && y in 10..21) 60 else 40, 100, 180, 255) }
        for (colorMatch in listOf(false, true)) {
            val result = WorkspaceMaterialPeel.peel(before, after, null, WorkspaceMaterialPeel.Options(colorMatch = colorMatch))
            assertTrue(result.part.rgba.indices.step(4).any { (result.part.rgba[it + 3].toInt() and 255) in 1..254 })
            checkRecomposition(before, result)
        }
    }

    @Test fun transparentAndHighContrastEdgesRecomposeToTheOriginal() {
        val before = image { x, y ->
            if (x in 8..23 && y in 8..23) listOf((x * 17) % 256, (y * 29) % 256, 255, (x * 31 + y * 19) % 256)
            else listOf(0, 0, 0, 0)
        }
        val after = image { x, y -> listOf(255, (x * 13) % 256, (y * 7) % 256, 255) }
        checkRecomposition(before, WorkspaceMaterialPeel.peel(before, after, null, WorkspaceMaterialPeel.Options(colorMatch = false)))
    }

    @Test fun softTranslucentPartsKeepTheOriginalAlpha() {
        val after = image { _, _ -> listOf(40, 100, 180, 128) }
        val before = image { x, y -> listOf(if (x in 10..21 && y in 10..21) 60 else 40, 100, 180, 128) }
        val result = WorkspaceMaterialPeel.peel(before, after, null, WorkspaceMaterialPeel.Options(low = 1f, colorMatch = false))
        assertTrue(result.part.rgba.indices.step(4).any { (result.part.rgba[it + 3].toInt() and 255) in 1..127 })
        checkRecomposition(before, result)
    }
}
