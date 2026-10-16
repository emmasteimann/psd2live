package io.github.psd2live.ipad

import io.github.psd2live.application.WorkspaceSwingDrafts
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.RigSwingEdit
import io.github.psd2live.core.SwingAuthoring
import io.github.psd2live.core.SwingKind
import io.github.psd2live.core.SwingPhysics
import io.github.psd2live.core.SwingPreset
import io.github.psd2live.core.SwingPresets
import io.github.psd2live.core.SwingShape

/**
 * Swings (the desktop's swing sessions): a regenerating sway on hair, ribbons or cloth - one or two directions, a few
 * segments each, a shape and an optional pendulum. A draft is edited and auditioned on a copy of the rig, then kept as
 * one undoable step; existing swings can be edited again, removed, or baked into plain keyforms.
 */
object RigSwings {
	/** The rig's swings. */
	fun list(e: RigEditing): List<RigSwingEdit> = e.model.config.rigEdits.swingEdits

	/** The swing already moving [targets], or a new one with [preset]'s defaults. */
	fun start(e: RigEditing, targets: List<String>, preset: SwingPreset = SwingPreset.HAIR): RigSwingEdit {
		require(targets.isNotEmpty() && targets.distinct().size == targets.size) { "Choose what swings" }
		return WorkspaceSwingDrafts.existing(e.model, e.model.config.rigEdits, targets)
			?: WorkspaceSwingDrafts.defaults(e.model, e.model.config.rigEdits, targets, preset, null)
	}

	/** The rig with [draft] applied, for the audition, and [draft] as authoring prepares it. Blocking. */
	fun prepare(e: RigEditing, draft: RigSwingEdit): Pair<RigPreviewModel, RigSwingEdit> =
		WorkspaceSwingDrafts.prepare(e.model, e.model.config.rigEdits, draft)

	/** [draft] moving in [kinds] (side to side, up and down, or both). */
	fun kinds(e: RigEditing, draft: RigSwingEdit, kinds: List<SwingKind>): RigSwingEdit =
		SwingAuthoring.withKinds(e.model.rig.puppet, e.model.config.rigEdits, draft, kinds)

	/** [draft] with [segments] segments (1..3) in direction [motion]. */
	fun segments(e: RigEditing, draft: RigSwingEdit, motion: Int, segments: Int): RigSwingEdit =
		SwingAuthoring.withSegments(e.model.rig.puppet, e.model.config.rigEdits, draft, motion, segments.coerceIn(1, RigSwingEdit.MAX_SEGMENTS))

	/** [draft] with [preset]'s shapes (each direction keeps its flip), resized to the targets. */
	fun preset(e: RigEditing, draft: RigSwingEdit, preset: SwingPreset): RigSwingEdit =
		SwingAuthoring.resized(e.model.rig.puppet, draft.copy(preset = preset, motions = draft.motions.map {
			it.copy(shape = SwingPresets.shape(preset, it.kind).copy(flip = it.shape.flip))
		}))

	/** [draft] with its pendulum on (sized to the targets) or off. */
	fun physics(e: RigEditing, draft: RigSwingEdit, on: Boolean): RigSwingEdit {
		val next = draft.withPhysics { _, _ -> if (on) SwingPhysics() else null }
		return if (on) SwingAuthoring.resized(e.model.rig.puppet, next) else next
	}

	/** [draft] with direction [motion]'s shape changed. */
	fun shape(draft: RigSwingEdit, motion: Int, change: (SwingShape) -> SwingShape): RigSwingEdit =
		draft.copy(motions = draft.motions.mapIndexed { i, m -> if (i == motion) m.copy(shape = change(m.shape)) else m })

	/**
	 * Keeps [draft] in the rig as one undoable step (a pendulum switches physics generation on). The draft as edited, not
	 * as [prepare] returns it: preparing re-targets it to the wraps it makes, which exist only on the audition's copy.
	 * Blocking.
	 */
	fun commit(e: RigEditing, draft: RigSwingEdit) {
		val config = e.model.config
		val overlay = SwingAuthoring.put(config.rigEdits, e.model.rig.puppet, draft)
		val physics = overlay.swingEdits.single { it.id == draft.id }.hasPhysics
		e.reconfigure(config.copy(rigEdits = overlay, generatePhysics = config.generatePhysics || physics))
	}

	/** Takes swing [id] out; [bake] keeps its motion as plain keyforms instead. Blocking. */
	fun remove(e: RigEditing, id: String, bake: Boolean) {
		val config = e.model.config
		e.reconfigure(config.copy(rigEdits = if (bake) SwingAuthoring.bake(config.rigEdits, e.model.rig.puppet, id, e.model.authored.rig.puppet)
			else SwingAuthoring.remove(config.rigEdits, id)))
	}
}
