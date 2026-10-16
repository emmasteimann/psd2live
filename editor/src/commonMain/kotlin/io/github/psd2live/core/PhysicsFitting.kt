package io.github.psd2live.core

/**
 * Fit physics to the art: a group's output scales set so a standard head sway (pulled fully right for 1 s, then let
 * go) swings each output to [target] of its parameter's end. Shared by the desktop's `physics_fit` and the iPad.
 */
object PhysicsFitting {
	fun fit(overlay: RigEditOverlay, group: PhysicsGroup, parameters: List<org.umamo.runtime.model.Parameter>, target: Float,
		progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): RigEditOverlay {
		require(target.isFinite() && target in 0.1f..3f) { "Target fraction must be in 0.1..3" }
		val trace = PhysicsResponse.trace(group.setting, PhysicsEngine.ranges(parameters), overlay.physicsFps, progress = progress, cancelled = cancelled)
		val fitted = PhysicsAuthoring.fitScales(group.setting, trace.reach, target)
		require(trace.reach.values.any { it > 0.01f }) { "No output of ${group.id} swings under a head sway; check its inputs" }
		return PhysicsAuthoring.put(overlay, fitted, group.generated)
	}
}
