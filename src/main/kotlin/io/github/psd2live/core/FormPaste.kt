package io.github.psd2live.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Layerloom patch (ONE-APP-PLAN S4.2): the points Paste Form and Paste Inverted put on an object. Forms are carried
 * as offsets from each object's default-pose form ("rest"), in the object's local space.
 */
object FormPaste {
	/**
	 * Paste special: [pasted] laid over [current] at [strength] (0..1), only on [selected] points when given (others keep
	 * [current]). A rotation blends as a whole.
	 */
	fun special(current: FloatArray, pasted: FloatArray, strength: Float, selected: Set<Int>?): FloatArray {
		require(current.size == pasted.size) { "Paste special needs the same number of points" }
		val s = strength.coerceIn(0f, 1f)
		return FloatArray(current.size) { k ->
			if (selected != null && (k / 2) !in selected) current[k] else current[k] + (pasted[k] - current[k]) * s
		}
	}

	/**
	 * The destination's new local points.
	 * - [sourcePoints] / [sourceRest]: the copied form and its rest, local; [sourceRestWorld]: that rest in canvas space.
	 * - [rest] / [restWorld]: the destination's rest, local and in canvas space.
	 * - [inverted]: mirror left to right. A point takes the offset (x reversed) of the copied point whose rest mirrors
	 *   nearest its own rest; the mirror line is [axis] in canvas x, or the copied rest's own centre when null (the
	 *   same object). A rotation keeps its origin offset (x reversed) and turns the other way by the same angle.
	 */
	fun points(kind: String, sourcePoints: FloatArray, sourceRest: FloatArray, sourceRestWorld: FloatArray,
	           rest: FloatArray, restWorld: FloatArray, inverted: Boolean, axis: Float?): FloatArray {
		if (kind == "rotation") {
			var dx = sourcePoints[0] - sourceRest[0]; val dy = sourcePoints[1] - sourceRest[1]
			if (inverted) dx = -dx
			val ox = rest[0] + dx; val oy = rest[1] + dy
			val restArmX = sourceRest[2] - sourceRest[0]; val restArmY = sourceRest[3] - sourceRest[1]
			val armX = sourcePoints[2] - sourcePoints[0]; val armY = sourcePoints[3] - sourcePoints[1]
			val turn = atan2(restArmX * armY - restArmY * armX, restArmX * armX + restArmY * armY) * if (inverted) -1f else 1f
			val stretch = hypot(armX, armY) / hypot(restArmX, restArmY).coerceAtLeast(1e-6f)
			val baseX = rest[2] - rest[0]; val baseY = rest[3] - rest[1]
			return floatArrayOf(ox, oy, ox + (baseX * cos(turn) - baseY * sin(turn)) * stretch, oy + (baseX * sin(turn) + baseY * cos(turn)) * stretch)
		}
		if (!inverted) {
			require(sourcePoints.size == rest.size) { "Paste Form needs the same number of points" }
			return FloatArray(rest.size) { k -> rest[k] + (sourcePoints[k] - sourceRest[k]) }
		}
		val sourceCount = sourceRestWorld.size / 2
		val mirror = axis ?: run {
			var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
			for (i in 0 until sourceCount) { lo = minOf(lo, sourceRestWorld[i * 2]); hi = maxOf(hi, sourceRestWorld[i * 2]) }
			(lo + hi) / 2f
		}
		return FloatArray(rest.size).also { out ->
			for (j in 0 until rest.size / 2) {
				var best = 0; var bestDistance = Float.POSITIVE_INFINITY
				for (i in 0 until sourceCount) {
					val mx = 2f * mirror - sourceRestWorld[i * 2] - restWorld[j * 2]; val my = sourceRestWorld[i * 2 + 1] - restWorld[j * 2 + 1]
					val d = mx * mx + my * my
					if (d < bestDistance) { bestDistance = d; best = i }
				}
				out[j * 2] = rest[j * 2] - (sourcePoints[best * 2] - sourceRest[best * 2])
				out[j * 2 + 1] = rest[j * 2 + 1] + (sourcePoints[best * 2 + 1] - sourceRest[best * 2 + 1])
			}
		}
	}
}
