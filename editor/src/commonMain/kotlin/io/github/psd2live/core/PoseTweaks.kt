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

	/** A warp's lattice at [pose] in canvas pixels (x, y per control point, row by row), with its rows and columns. */
	class Lattice(val id: String, val name: String, val rows: Int, val columns: Int, val points: FloatArray)

	/** Every warp deformer's lattice at [pose]. */
	fun lattices(model: PuppetModel, pose: Map<String, Float>): List<Lattice> {
		val defaults = model.parameters.associate { it.id to it.default }
		val worlds = buildDeformerWorlds(model.deformers, { pose[it.raw] ?: defaults[it] ?: 0f }, { defaults[it] ?: 0f })
		return model.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>().mapNotNull { warp ->
			val geometry = runCatching { RigGeometryTools.geometry(model, "warp", warp.id.raw, pose) }.getOrNull() ?: return@mapNotNull null
			val world = DrawableSpaceMapping(geometry.parent?.let { worlds[DeformerId(it)] ?: return@let null }).localToWorld(geometry.points)
			for (i in 1 until world.size step 2) world[i] = -world[i]
			Lattice(warp.id.raw, warp.name, warp.rows, warp.columns, world)
		}
	}

	/**
	 * The warp to push at ([x], [y]): of the lattices whose outline holds the point, the smallest - the most local
	 * deformer there (an eye's before the face's, the face's before the head's). Physics and sway warps are left out:
	 * their shape is the simulation's.
	 */
	fun warpUnder(model: PuppetModel, pose: Map<String, Float>, x: Float, y: Float): Lattice? =
		lattices(model, pose).filter { !it.id.endsWith("Physics") && !it.id.startsWith("ParamSwing") && !it.id.contains("Swing") }
			.mapNotNull { lattice ->
				val xs = (lattice.points.indices step 2).map { lattice.points[it] }; val ys = (1 until lattice.points.size step 2).map { lattice.points[it] }
				val (l, r, t, b) = listOf(xs.min(), xs.max(), ys.min(), ys.max())
				if (x in l..r && y in t..b) lattice to (r - l) * (b - t) else null
			}.minByOrNull { it.second }?.first

	/** A stroke on warp [warpId]'s lattice at [pose], as [begin] does on meshes. */
	fun beginWarp(model: PuppetModel, pose: Map<String, Float>, warpId: String, x: Float, y: Float, radius: Float,
		hardness: Float = 0.5f, strength: Float = 1f, action: CanvasDeformStroke.Action = CanvasDeformStroke.Action.BRUSH): CanvasDeformStroke.Session {
		val defaults = model.parameters.associate { it.id.raw to it.default }
		val posed = pose.filter { (id, value) -> defaults[id]?.let { kotlin.math.abs(value - it) > 1e-4f } == true }.keys.toList()
		val geometry = RigGeometryTools.geometry(model, "warp", warpId, pose)
		val target = CanvasDeformStroke.Target("warp", warpId, canvasDeformationCoordinate(model, geometry.axes, pose, posed))
		val tip = CanvasBrushTip(radius.coerceIn(0.5f, 4096f), hardness.coerceIn(0f, 0.95f), CanvasBrushShape.CIRCLE, 0f, 1f, CanvasBrushFalloff.SMOOTH)
		val request = CanvasDeformStroke.Request(action, CanvasDeformStroke.Mode.DEFORM, listOf(target), pose, tip, strength.coerceIn(0f, 1f), false)
		return CanvasDeformStroke.begin(model, request, CanvasDeformStroke.Sample(CanvasBrushPoint(x, y)))
	}

	/** Each mesh's triangle edges at [pose] as canvas-pixel segments (x0, y0, x1, y1, ...), front-most first. */
	fun wireframes(model: PuppetModel, pose: Map<String, Float>, only: Set<String>? = null): List<Pair<String, FloatArray>> {
		val defaults = model.parameters.associate { it.id to it.default }
		val worlds = buildDeformerWorlds(model.deformers, { pose[it.raw] ?: defaults[it] ?: 0f }, { defaults[it] ?: 0f })
		return model.drawables.withIndex().sortedByDescending { (index, d) -> d.drawOrder * 10_000 + index }.mapNotNull { (_, drawable) ->
			val mesh = drawable.mesh ?: return@mapNotNull null
			if (only != null && drawable.id.raw !in only) return@mapNotNull null
			val geometry = runCatching { RigGeometryTools.geometry(model, "mesh", drawable.id.raw, pose) }.getOrNull() ?: return@mapNotNull null
			val world = DrawableSpaceMapping(geometry.parent?.let { worlds[DeformerId(it)] ?: return@let null }).localToWorld(geometry.points)
			val edges = HashSet<Long>()
			val out = ArrayList<Float>()
			val idx = mesh.indices
			fun edge(a: Int, b: Int) {
				val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
				if (edges.add(key)) { out += world[a * 2]; out += -world[a * 2 + 1]; out += world[b * 2]; out += -world[b * 2 + 1] }
			}
			var t = 0
			while (t + 2 < idx.size) { edge(idx[t], idx[t + 1]); edge(idx[t + 1], idx[t + 2]); edge(idx[t + 2], idx[t]); t += 3 }
			drawable.id.raw to out.toFloatArray()
		}
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

	/** Parameters whose sign flips in a left-right mirror: head and body turn and roll, gaze across. */
	val MIRRORED_PARAMETERS = setOf("ParamAngleX", "ParamAngleZ", "ParamBodyAngleX", "ParamBodyAngleZ", "ParamEyeBallX")

	/** [pose] mirrored left to right: the turn and roll parameters negated (where the rig's range allows). */
	fun mirroredPose(model: PuppetModel, pose: Map<String, Float>): Map<String, Float> {
		val ranges = model.parameters.associate { it.id.raw to (it.min..it.max) }
		return pose.mapValues { (id, value) -> if (id in MIRRORED_PARAMETERS) (-value).coerceIn(ranges[id] ?: (-value..-value)) else value }
	}

	/**
	 * [session]'s stroke done again on [model] as its mirror image: points reflected about the canvas's vertical centre
	 * line, at the mirrored pose, over the meshes under the reflected press. Null when nothing is under it.
	 */
	fun mirrored(model: PuppetModel, session: CanvasDeformStroke.Session): CanvasDeformStroke.Session? {
		val samples = session.capturedSamples()
		if (samples.size < 2) return null
		val axis = model.canvasWidth
		fun flip(x: Float) = axis - x
		val pose = mirroredPose(model, session.request.pose)
		val first = samples.first().point
		val radius = session.request.tip.radius
		val meshes = meshesUnder(model, pose, flip(first.x), first.y, radius)
		if (meshes.isEmpty()) return null
		val mirror = begin(model, pose, meshes, flip(first.x), first.y, radius, session.request.tip.hardness, session.request.strength, session.request.action)
		for (sample in samples.drop(1)) step(mirror, flip(sample.point.x), sample.point.y)
		return mirror
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
