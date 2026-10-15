package io.github.psd2live.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.psd2live.core.BoneRole
import io.github.psd2live.core.Side
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.ui.components.BoneKind

/** How a bone is drawn: its silhouette follows what it is, whatever its color. */
fun SkeletonBone.visualKind(): BoneKind = when {
	role.anchor -> BoneKind.ANCHOR
	flex -> BoneKind.FLEX
	role.body -> BoneKind.BODY
	else -> BoneKind.LIMB
}

/**
 * One color per bone, shared by everything that shows a skeleton: the bone tree, the bones on the
 * canvas and the art meshes bound to them. A mesh tinted in a bone's color is bound to that bone.
 * The color follows the bone's role, so adding or removing a bone never recolors the others; the
 * right-hand side of a pair is a shade darker than the left.
 */
object SkeletonPalette {
	private val arm = Color(0xFF7AB7EA)
	private val leg = Color(0xFF8ED390)
	private val torso = Color(0xFFED9F70)
	private val tail = Color(0xFFC69BE8)
	private val wing = Color(0xFFE08CB7)

	/** Custom bones have no role to color by, so they take these in turn. */
	private val custom = listOf(Color(0xFFEA7A78), Color(0xFFDBB468), Color(0xFF6FD0CB), Color(0xFFB5C95A))

	/** Anchor bones carry no deformer and share a neutral grey, so the limb colors stay distinct. */
	private val anchor = Color(0xFF9AA3AD)

	fun color(spec: SkeletonSpec, boneId: String): Color {
		val bone = spec.bone(boneId) ?: return anchor
		val base = when (bone.role) {
			BoneRole.HEAD -> return anchor
			BoneRole.UPPER_BODY, BoneRole.LOWER_BODY -> torso
			BoneRole.UPPER_ARM, BoneRole.FOREARM, BoneRole.HAND -> arm
			BoneRole.THIGH, BoneRole.SHIN, BoneRole.FOOT -> leg
			BoneRole.TAIL -> tail
			BoneRole.WING -> wing
			BoneRole.CUSTOM -> custom[spec.bones.filter { it.role == BoneRole.CUSTOM }.indexOfFirst { it.id == boneId }.coerceAtLeast(0) % custom.size]
		}
		return if (bone.side == Side.RIGHT) lerp(base, Color.Black, 0.22f) else base
	}
}
