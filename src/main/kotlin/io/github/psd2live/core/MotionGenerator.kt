package io.github.psd2live.core

/** Demonstration motions that exercise the generated rig without audio assets. */
object MotionGenerator {
	fun idle(): String = buildMotionJson(
		duration = 6.0f,
		loop = true,
		curves = listOf(
			curve("ParamBreath", listOf(0f to 0f, 1.5f to 1f, 3f to 0f, 4.5f to 1f, 6f to 0f)),
			curve("ParamAngleZ", listOf(0f to -2f, 1.5f to 2f, 3f to -2f, 4.5f to 2f, 6f to -2f)),
			curve("ParamBodyAngleX", listOf(0f to -1.2f, 3f to 1.2f, 6f to -1.2f)),
			// Same rhythm as blinkCurve() (close 0.08 s / hold 0.34 s / open 0.16 s), at 2.60-3.18 s.
			curve("ParamEyeLOpen", listOf(0f to 1f, 2.60f to 1f, 2.68f to 0f, 3.02f to 0f, 3.18f to 1f, 6f to 1f)),
			curve("ParamEyeROpen", listOf(0f to 1f, 2.60f to 1f, 2.68f to 0f, 3.02f to 0f, 3.18f to 1f, 6f to 1f)),
		),
	)

	/**
	 * InkPet fork (2026-09-22): blink with a short hold, like the desktop pet's own blink:
	 * close 0.08 s -> hold 0.34 s -> open 0.16 s (0.58 s in total). The upstream curve reopened
	 * right after closing, which reads as a twitch.
	 */
	private fun blinkCurve(): List<Pair<Float, Float>> =
		listOf(0f to 1f, 0.08f to 0f, 0.42f to 0f, 0.58f to 1f)

	fun blink(): String = buildMotionJson(
		duration = 0.58f,
		loop = false,
		curves = listOf(
			curve("ParamEyeLOpen", blinkCurve()),
			curve("ParamEyeROpen", blinkCurve()),
		),
	)

	fun nod(): String = buildMotionJson(
		duration = 2.0f,
		loop = false,
		curves = listOf(
			curve("ParamAngleY", listOf(0f to 0f, 0.55f to -18f, 1.25f to 6f, 2.0f to 0f)),
			curve("ParamBodyAngleY", listOf(0f to 0f, 0.55f to -4f, 1.25f to 1.5f, 2.0f to 0f)),
			curve("ParamEyeLOpen", listOf(0f to 1f, 0.55f to 0.75f, 1.25f to 1f, 2.0f to 1f)),
			curve("ParamEyeROpen", listOf(0f to 1f, 0.55f to 0.75f, 1.25f to 1f, 2.0f to 1f)),
		),
	)

	fun shake(): String = buildMotionJson(
		duration = 2.0f,
		loop = false,
		curves = listOf(
			curve("ParamAngleX", listOf(0f to 0f, 0.4f to -20f, 0.9f to 20f, 1.4f to -8f, 2.0f to 0f)),
			curve("ParamBodyAngleX", listOf(0f to 0f, 0.4f to -3f, 0.9f to 3f, 1.4f to -1.2f, 2.0f to 0f)),
			curve("ParamAngleZ", listOf(0f to 0f, 0.4f to 2f, 0.9f to -2f, 1.4f to 1f, 2.0f to 0f)),
		),
	)

	private fun buildMotionJson(duration: Float, loop: Boolean, curves: List<Curve>): String {
		val segmentCount = curves.sumOf { it.pointCount - 1 }
		val pointCount = curves.sumOf { it.pointCount }
		return """
		{
		  "Version": 3,
		  "Meta": {
		    "Duration": $duration,
		    "Fps": 60.0,
		    "Loop": $loop,
		    "AreBeziersRestricted": true,
		    "CurveCount": ${curves.size},
		    "TotalSegmentCount": $segmentCount,
		    "TotalPointCount": $pointCount,
		    "UserDataCount": 0,
		    "TotalUserDataSize": 0
		  },
		  "Curves": [${curves.joinToString(",") { it.json }}]
		}
		""".trimIndent()
	}

	private data class Curve(val json: String, val pointCount: Int)

	private fun curve(parameter: String, points: List<Pair<Float, Float>>): Curve {
		require(points.size >= 2)
		val segments = buildList<Number> {
			add(points.first().first)
			add(points.first().second)
			for ((time, value) in points.drop(1)) {
				add(0) // linear segment
				add(time)
				add(value)
			}
		}.joinToString(",") { number ->
			if (number is Int) number.toString() else number.toFloat().toString()
		}
		return Curve("""{"Target":"Parameter","Id":"$parameter","Segments":[$segments]}""", points.size)
	}
}
