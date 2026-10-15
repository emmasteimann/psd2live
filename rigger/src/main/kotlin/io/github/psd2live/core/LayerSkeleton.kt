package io.github.psd2live.core

/** Layerloom patch: the auto skeleton for `--skeleton arms|full` (see [PipelineConfig.autoSkeleton]). */
object LayerSkeleton {
	private val armRoles = setOf(BoneRole.UPPER_ARM, BoneRole.FOREARM, BoneRole.HAND)
	private val limbRoles = setOf(BoneRole.THIGH, BoneRole.SHIN, BoneRole.FOOT, BoneRole.TAIL, BoneRole.WING)

	fun propose(analysis: PipelineAnalysis, rig: BuiltRig, mode: String, armJoints: Map<String, Map<String, List<Float>>> = emptyMap()): SkeletonSpec {
		val spec = withArmJoints(SkeletonAutoBuilder.build(analysis, rig).copy(enabled = true), armJoints)
		if (mode == "full") return spec
		// Arms only: drop the other limbs (and anything hanging from them); their meshes ride the body as before.
		val dropped = spec.bones.filter { it.role in limbRoles }.mapTo(HashSet()) { it.id }
		var grew = true
		while (grew) grew = spec.bones.filter { it.parentId in dropped && it.id !in dropped }.onEach { dropped += it.id }.isNotEmpty()
		val kept = spec.bones.filterNot { it.id in dropped }
		warn("Skeleton (arms): " + kept.filter { it.role in armRoles }.joinToString { "${it.name} (${it.headX.toInt()},${it.headY.toInt()})->(${it.tailX.toInt()},${it.tailY.toInt()}) ${it.drawableIds.size} mesh(es)" })
		return spec.copy(bones = kept, ikTargets = spec.ikTargets.filterKeys { it !in dropped })
	}

	/**
	 * The arm bones moved onto joints placed by hand. The auto skeleton reads an arm's line through its mesh, which a
	 * wide sleeve (Quill's bell sleeves) bends into the wrong shape; hand-placed joints keep its bindings and weights.
	 */
	private fun withArmJoints(spec: SkeletonSpec, joints: Map<String, Map<String, List<Float>>>): SkeletonSpec {
		if (joints.isEmpty()) return spec
		val segments = mapOf(BoneRole.UPPER_ARM to ("shoulder" to "elbow"), BoneRole.FOREARM to ("elbow" to "wrist"), BoneRole.HAND to ("wrist" to "tip"))
		return spec.copy(bones = spec.bones.map { bone ->
			val side = when (bone.side) { Side.LEFT -> "L"; Side.RIGHT -> "R"; else -> null }
			val (from, to) = segments[bone.role] ?: return@map bone
			val points = joints[side] ?: return@map bone
			val head = points[from]?.takeIf { it.size == 2 } ?: return@map bone
			val tail = points[to]?.takeIf { it.size == 2 } ?: return@map bone
			bone.copy(headX = head[0], headY = head[1], tailX = tail[0], tailY = tail[1])
		})
	}
}
