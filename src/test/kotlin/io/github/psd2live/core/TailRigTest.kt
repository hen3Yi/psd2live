package io.github.psd2live.core

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** InkPet fork: tail physics (ParamTail) and the program-driven wag (ParamTailWag). */
class TailRigTest {
	@Test
	fun `tail physics is driven by the body and outputs ParamTail`() {
		val rule = PhysicsGenerator.rules(hasFrontHair = false, hasBackHair = false, hasEyeJelly = false, hasTail = true).single()
		assertEquals("PhysicsTail", rule.id)
		assertEquals("ParamTail", rule.outputParameter)
		assertTrue(rule.inputs.any { it.parameter == "ParamBodyAngleX" }, "a tail hangs from the hips")
		assertTrue(PhysicsGenerator.rules(hasFrontHair = false, hasBackHair = false, hasEyeJelly = false).isEmpty())
	}

	@Test
	fun `wag keeps the tail root pinned and swings the tip`() {
		val root = RigBuilder.tailWagPoint(0.5f, 0f, 1f, 0.3f, 0.06f)
		assertEquals(0.5f, root.first, 1e-6f)
		assertEquals(0f, root.second, 1e-6f)

		val tip = RigBuilder.tailWagPoint(0.5f, 1f, 1f, 0.3f, 0.06f)
		assertEquals(0.8f, tip.first, 1e-6f)
		assertTrue(tip.second < 1f, "a wagging tip curls up a little")
		val other = RigBuilder.tailWagPoint(0.5f, 1f, -1f, 0.3f, 0.06f)
		assertEquals(0.2f, other.first, 1e-6f)
	}

	@Test
	fun `a tail layer gets the root, physics and wag warps and the tail parameters`() {
		val art = object : SourceArt {
			override val widthPx = 96
			override val heightPx = 96
			override val layers = listOf(
				testLayer("body", 0, rectangleRaster(96, 96, 30, 20, 66, 80)),
				testLayer("tail", 1, rectangleRaster(96, 96, 60, 50, 80, 92)),
			)
		}
		val preview = PSD2LivePipeline().buildPreview(art, PipelineConfig(atlasSize = 256, meshSpacing = 12))
		val puppet = preview.rig.puppet
		val deformers = puppet.deformers.associateBy { it.id.raw }
		assertEquals("DeformTailRoot", deformers["DeformTailPhysics"]?.parent?.raw)
		assertEquals("DeformTailPhysics", deformers["DeformTailWag"]?.parent?.raw)
		val parameterIds = puppet.parameters.map { it.id.raw }
		assertTrue("ParamTail" in parameterIds && "ParamTailWag" in parameterIds)

		val tagByLayerId = preview.analysis.layers.associate { it.source.id.raw to it.semantic.tag }
		val tail = puppet.drawables.single {
			tagByLayerId[preview.rig.layerIdByDrawableId.getValue(it.id.raw)] == SemanticTag.TAIL
		}
		assertEquals("DeformTailWag", tail.parentDeformerId?.raw)
		val physics = assertNotNull(
			org.umamo.format.moc3.Moc3.readPhysics3(
				preview.runtimeBundle.assets.single { it.path.endsWith(".physics3.json") }.bytes.decodeToString(),
			),
		)
		assertTrue(physics.physicsSettings.any { it.id == "PhysicsTail" })
	}

	private fun rectangleRaster(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): ByteArray {
		val rgba = ByteArray(width * height * 4)
		for (y in top until bottom) for (x in left until right) {
			val offset = (y * width + x) * 4
			rgba[offset] = 0x30
			rgba[offset + 1] = 0x40
			rgba[offset + 2] = 0x50
			rgba[offset + 3] = 0xff.toByte()
		}
		return rgba
	}

	private fun testLayer(name: String, order: Int, rgba: ByteArray) = object : SourceLayer {
		override val id = LayerId(name)
		override val name = name
		override val groupPath = ""
		override val order = order
		override val bounds = LayerBounds(0, 0, 96, 96)
		override val opacity = 1f
		override val clipped = false
		override val blend = LayerBlend.Normal
		override val raster = LayerRaster(96, 96, rgba)
	}
}
