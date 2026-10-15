package io.github.psd2live.core

import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot

/**
 * Tweaking a rig by hand at a pose, the way a deform brush in the editor does, for touch and pencil front ends: the
 * meshes a brush press reaches, a stroke over all of them (liquify-like: what is under the brush moves together,
 * welded partners follow), and the finished stroke committed as keyform edits in the rig's edits - the same journal
 * the desktop editor writes, so the edits replay over every re-rig and open on the Mac.
 *
 * Points are canvas pixels (x right, y down), as the brush engine's are.
 */
object PoseTweaks {
	/** Every mesh with a vertex within [radius] of ([x], [y]) at [pose], front-most first, at most 64. */
	fun meshesUnder(model: PuppetModel, pose: Map<String, Float>, x: Float, y: Float, radius: Float): List<String> {
		val defaults = model.parameters.associate { it.id to it.default }
		val worlds = buildDeformerWorlds(model.deformers, { pose[it.raw] ?: defaults[it] ?: 0f }, { defaults[it] ?: 0f })
		val order = model.drawables.withIndex().sortedByDescending { (index, d) -> d.drawOrder * 10_000 + index }
		val reached = ArrayList<String>()
		for ((_, drawable) in order) {
			if (drawable.mesh == null) continue
			val geometry = runCatching { RigGeometryTools.geometry(model, "mesh", drawable.id.raw, pose) }.getOrNull() ?: continue
			val mapping = DrawableSpaceMapping(geometry.parent?.let { worlds[DeformerId(it)] ?: return@let null })
			val world = mapping.localToWorld(geometry.points)
			var hit = false
			var i = 0
			while (i < world.size) {
				if (hypot(world[i] - x, -world[i + 1] - y) <= radius) { hit = true; break }
				i += 2
			}
			if (hit) reached += drawable.id.raw
			if (reached.size == 64) break
		}
		return reached
	}

	/**
	 * Starts a stroke at ([x], [y]) over [meshes] at [pose]: each mesh's keyform at the pose's coordinate is what moves
	 * ([CanvasDeformStroke.Mode.DEFORM]), so a tweak at "looking left" changes looking left and blends toward the rest.
	 * A mesh without keys on a posed parameter gets one there (the editor's "deformation parameters"), so the tweak
	 * stays at the pose instead of moving the mesh everywhere.
	 */
	fun begin(model: PuppetModel, pose: Map<String, Float>, meshes: List<String>, x: Float, y: Float, radius: Float,
		hardness: Float = 0.5f, strength: Float = 1f, action: CanvasDeformStroke.Action = CanvasDeformStroke.Action.BRUSH): CanvasDeformStroke.Session {
		val defaults = model.parameters.associate { it.id.raw to it.default }
		val posed = pose.filter { (id, value) -> defaults[id]?.let { kotlin.math.abs(value - it) > 1e-4f } == true }.keys.toList()
		val targets = meshes.map { id ->
			val geometry = RigGeometryTools.geometry(model, "mesh", id, pose)
			CanvasDeformStroke.Target("mesh", id, canvasDeformationCoordinate(model, geometry.axes, pose, posed))
		}
		val tip = CanvasBrushTip(radius.coerceIn(0.5f, 4096f), hardness.coerceIn(0f, 0.95f), CanvasBrushShape.CIRCLE, 0f, 1f, CanvasBrushFalloff.SMOOTH)
		val request = CanvasDeformStroke.Request(action, CanvasDeformStroke.Mode.DEFORM, targets, pose, tip, strength.coerceIn(0f, 1f), false)
		return CanvasDeformStroke.begin(model, request, CanvasDeformStroke.Sample(CanvasBrushPoint(x, y)))
	}

	/** One more point of the stroke; the returned puppet is the live preview. */
	fun step(session: CanvasDeformStroke.Session, x: Float, y: Float): PuppetModel =
		session.step(CanvasDeformStroke.Sample(CanvasBrushPoint(x, y)))

	/** [current] with the finished [session]'s edits journaled into its config's rig edits (the desktop's journal step). */
	fun journaled(current: RigPreviewModel, session: CanvasDeformStroke.Session): PipelineConfig? {
		val edits = session.commands
		if (edits.isEmpty() || session.capturedSamples().size < 2) return null
		val overlay = current.config.rigEdits
		val journal = JournalRecording.record(current.rig.puppet, current.authored.rig.puppet, overlay, edits, current.primitiveSkins)
		val next = SwingAuthoring.withoutTargets(overlay.copy(authoringJournal = overlay.authoringJournal + journal), SwingAuthoring.deletedDeformers(journal))
		return current.config.copy(rigEdits = next)
	}

	/** The rig with [config]'s edits, replayed onto [current]'s base without regenerating it. */
	fun rebuilt(pipeline: PSD2LivePipeline, current: RigPreviewModel, config: PipelineConfig): RigPreviewModel =
		pipeline.updateRigEdits(current, config)
}
