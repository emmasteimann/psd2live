package io.github.psd2live.core

import org.umamo.runtime.model.Parameter

/**
 * Layerloom patch (ONE-APP-PLAN S4.4): a motion mirrored left to right, for any rig. A parameter with a left/right
 * counterpart (ParamArmLA / ParamArmRA, ParamEyeLOpen / ParamEyeROpen, "Left hand" / "Right hand") trades curves
 * with it. One without, whose id ends in X or Z and whose range is centred on its default (ParamAngleX,
 * ParamBodyAngleZ, ParamEyeBallX), is a sideways axis and is reflected about its default. Everything else is kept.
 */
object MotionMirror {
	private val wordPairs = listOf("Left" to "Right", "left" to "right", "LEFT" to "RIGHT")
	// A lone L or R between word parts: ParamArmLA, ParamEyeLOpen, Param_L, hair_r_1.
	private val letter = Regex("(?<=[a-z0-9_])([LRlr])(?=[A-Z0-9_]|$)")

	/** The counterpart of [id] among [ids], or null. */
	fun counterpart(id: String, ids: Set<String>): String? {
		for ((a, b) in wordPairs) {
			for ((from, to) in listOf(a to b, b to a)) {
				var at = id.indexOf(from)
				while (at >= 0) {
					val candidate = id.replaceRange(at, at + from.length, to)
					if (candidate != id && candidate in ids) return candidate
					at = id.indexOf(from, at + 1)
				}
			}
		}
		for (m in letter.findAll(id)) {
			val swapped = when (m.value) { "L" -> "R"; "R" -> "L"; "l" -> "r"; else -> "l" }
			val candidate = id.replaceRange(m.range, swapped)
			if (candidate in ids) return candidate
		}
		return null
	}

	/** Whether [p] is a sideways axis to reflect: an X or Z axis centred on its default, with no counterpart. */
	fun isSideways(p: Parameter): Boolean {
		val id = p.id.raw
		val axis = id.trimEnd { it.isDigit() }.lastOrNull()
		return (axis == 'X' || axis == 'Z') && kotlin.math.abs((p.max - p.default) - (p.default - p.min)) <= 1e-4f * (p.max - p.min).coerceAtLeast(1f)
	}

	fun mirrored(clip: MotionClip, parameters: List<Parameter>): List<MotionCurve> {
		val byId = parameters.associateBy { it.id.raw }
		val ids = byId.keys
		return clip.curves.map { curve ->
			val other = counterpart(curve.parameterId, ids)
			val p = byId[curve.parameterId]
			when {
				other != null -> curve.copy(parameterId = other)
				p != null && isSideways(p) -> curve.copy(keys = curve.keys.map { k ->
					k.copy(value = 2f * p.default - k.value, outHandle = k.outHandle.copy(y = -k.outHandle.y), inHandle = k.inHandle.copy(y = -k.inHandle.y))
				})
				else -> curve
			}
		}
	}
}
