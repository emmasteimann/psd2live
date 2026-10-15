package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlin.math.sqrt

/** A bone in screen space: what every canvas mode picks against. */
class ScreenBone(val id: String, val head: Offset, val tail: Offset)

/** Which end of a bone a pick landed on. */
enum class BoneJoint { HEAD, TAIL }

/**
 * Picking bones on the canvas, shared by the armature editor, the pose tool and Object mode so a bone is
 * grabbed the same way wherever it is drawn. Later bones are drawn above earlier ones and win ties.
 */
object BoneHitTest {
	/** Screen pixels within which the pointer grabs a joint in the armature editor. */
	const val JOINT_RADIUS = 14f

	/** Screen pixels within which the pointer grabs a posed bone's tip. */
	const val TIP_RADIUS = 10f

	/** The joint under [pos]: tails first when [heads] is false, the topmost bone otherwise. */
	fun joint(bones: List<ScreenBone>, pos: Offset, radius: Float, heads: Boolean = true): Pair<String, BoneJoint>? =
		bones.asReversed().firstNotNullOfOrNull { bone ->
			when {
				hypot(pos.x - bone.tail.x, pos.y - bone.tail.y) <= radius -> bone.id to BoneJoint.TAIL
				heads && hypot(pos.x - bone.head.x, pos.y - bone.head.y) <= radius -> bone.id to BoneJoint.HEAD
				else -> null
			}
		}

	/** The bone whose body is nearest [pos] within a reach proportional to its length. */
	fun body(bones: List<ScreenBone>, pos: Offset, reachRatio: Float = 0.14f): String? {
		var best: ScreenBone? = null
		var bestDistance = Float.MAX_VALUE
		for (bone in bones) {
			val dx = bone.tail.x - bone.head.x
			val dy = bone.tail.y - bone.head.y
			val lengthSquared = dx * dx + dy * dy
			val distance: Float
			val reach: Float
			if (lengthSquared < 1e-4f) {
				distance = hypot(pos.x - bone.head.x, pos.y - bone.head.y)
				reach = 12f
			} else {
				val u = (((pos.x - bone.head.x) * dx + (pos.y - bone.head.y) * dy) / lengthSquared).coerceIn(0f, 1f)
				distance = hypot(pos.x - (bone.head.x + u * dx), pos.y - (bone.head.y + u * dy))
				reach = (sqrt(lengthSquared) * reachRatio).coerceIn(6f, 18f)
			}
			if (distance <= reach && distance <= bestDistance) {
				best = bone
				bestDistance = distance
			}
		}
		return best?.id
	}
}
