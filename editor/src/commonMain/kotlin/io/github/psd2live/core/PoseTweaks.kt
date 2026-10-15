package io.github.psd2live.core

import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.colorAt

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

	/** Every mesh's vertices at [pose] in canvas pixels (x, y per vertex, in the mesh's own order), front-most first. */
	fun vertices(model: PuppetModel, pose: Map<String, Float>): List<Pair<String, FloatArray>> {
		val defaults = model.parameters.associate { it.id to it.default }
		val worlds = buildDeformerWorlds(model.deformers, { pose[it.raw] ?: defaults[it] ?: 0f }, { defaults[it] ?: 0f })
		return model.drawables.withIndex().sortedByDescending { (index, d) -> d.drawOrder * 10_000 + index }.mapNotNull { (_, drawable) ->
			if (drawable.mesh == null) return@mapNotNull null
			val geometry = runCatching { RigGeometryTools.geometry(model, "mesh", drawable.id.raw, pose) }.getOrNull() ?: return@mapNotNull null
			val world = DrawableSpaceMapping(geometry.parent?.let { worlds[DeformerId(it)] ?: return@let null }).localToWorld(geometry.points)
			for (i in 1 until world.size step 2) world[i] = -world[i]
			drawable.id.raw to world
		}
	}

	/**
	 * Moving [selection] (vertex indices by mesh id) exactly with the pencil from ([x], [y]) at [pose]: a push whose
	 * reach covers the whole canvas at full strength, limited to the selected vertices, so they move as one rigid
	 * group and nothing else moves. Same keyform edits as a brush stroke.
	 */
	fun beginPoints(model: PuppetModel, pose: Map<String, Float>, selection: Map<String, Set<Int>>, x: Float, y: Float): CanvasDeformStroke.Session {
		val defaults = model.parameters.associate { it.id.raw to it.default }
		val posed = pose.filter { (id, value) -> defaults[id]?.let { kotlin.math.abs(value - it) > 1e-4f } == true }.keys.toList()
		val targets = selection.filterValues { it.isNotEmpty() }.map { (id, vertices) ->
			val geometry = RigGeometryTools.geometry(model, "mesh", id, pose)
			CanvasDeformStroke.Target("mesh", id, canvasDeformationCoordinate(model, geometry.axes, pose, posed), vertices)
		}
		require(targets.isNotEmpty()) { "Select some points first" }
		val reach = maxOf(model.canvasWidth, model.canvasHeight) * 2f
		val tip = CanvasBrushTip(reach.coerceIn(0.5f, 4096f), 0.95f, CanvasBrushShape.CIRCLE, 0f, 1f, CanvasBrushFalloff.CONSTANT)
		val request = CanvasDeformStroke.Request(CanvasDeformStroke.Action.BRUSH, CanvasDeformStroke.Mode.DEFORM, targets, pose, tip, 1f, false)
		return CanvasDeformStroke.begin(model, request, CanvasDeformStroke.Sample(CanvasBrushPoint(x, y)))
	}

	/** A mesh's look at a pose: opacity 0..1, draw order (Cubism's 0..1000, 500 by default), multiply and screen RGB 0..1. */
	data class Look(val opacity: Float, val drawOrder: Float, val multiply: List<Float>, val screen: List<Float>)

	/** [meshId]'s look at [pose], as the runtime blends its keyed channels (or its statics where it has none). */
	fun look(model: PuppetModel, meshId: String, pose: Map<String, Float>): Look {
		val d = model.drawables.single { it.id.raw == meshId }
		val defaults = model.parameters.associate { it.id to it.default }
		val value = { id: org.umamo.runtime.model.ParameterId -> pose[id.raw] ?: defaults[id] ?: 0f }
		val g = d.channelGrids
		fun rgb(c: org.umamo.runtime.model.ColorRgb) = listOf(c.red, c.green, c.blue)
		return Look(
			g.scalarAt(org.umamo.runtime.model.FormChannel.OPACITY, d.opacity, value),
			g.scalarAt(org.umamo.runtime.model.FormChannel.DRAW_ORDER, d.drawOrder, value),
			rgb(g.colorAt(org.umamo.runtime.model.FormChannel.MULTIPLY_COLOR, d.multiplyColor, value)),
			rgb(g.colorAt(org.umamo.runtime.model.FormChannel.SCREEN_COLOR, d.screenColor, value)))
	}

	/**
	 * [current]'s config with [meshId]'s look at [pose] set to [look]: a keyform channel edit at the pose's
	 * coordinate (keys on the posed parameters, as a deform tweak gets), journaled like a stroke, so it replays over
	 * re-rigs. At rest on a mesh with no keys it sets the mesh's own look everywhere. Null when nothing changes.
	 */
	fun withLook(current: RigPreviewModel, meshId: String, pose: Map<String, Float>, look: Look): PipelineConfig? {
		val model = current.rig.puppet
		val now = look(model, meshId, pose)
		if (now == look) return null
		val defaults = model.parameters.associate { it.id.raw to it.default }
		val posed = pose.filter { (id, v) -> defaults[id]?.let { kotlin.math.abs(v - it) > 1e-4f } == true }.keys.toList()
		val axes = runCatching { RigGeometryTools.geometry(model, "mesh", meshId, pose).axes }.getOrDefault(emptyList())
		val key = canvasDeformationCoordinate(model, axes, pose, posed)
		val channels = kotlinx.serialization.json.buildJsonObject {
			if (now.opacity != look.opacity) put("opacity", kotlinx.serialization.json.JsonPrimitive(look.opacity.coerceIn(0f, 1f)))
			if (now.drawOrder != look.drawOrder) put("drawOrder", kotlinx.serialization.json.JsonPrimitive(look.drawOrder.coerceIn(0f, 1000f)))
			if (now.multiply != look.multiply) put("multiplyColor", kotlinx.serialization.json.JsonArray(look.multiply.map { kotlinx.serialization.json.JsonPrimitive(it.coerceIn(0f, 1f)) }))
			if (now.screen != look.screen) put("screenColor", kotlinx.serialization.json.JsonArray(look.screen.map { kotlinx.serialization.json.JsonPrimitive(it.coerceIn(0f, 1f)) }))
		}
		// No key to set it at (rest, and a mesh with no keys): it is the mesh's own look, the desktop Inspector's static edit.
		val command = if (key.isEmpty()) kotlinx.serialization.json.buildJsonObject {
			put("op", kotlinx.serialization.json.JsonPrimitive("structure"))
			put("edits", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
				put("action", kotlinx.serialization.json.JsonPrimitive("static")); put("kind", kotlinx.serialization.json.JsonPrimitive("mesh"))
				put("id", kotlinx.serialization.json.JsonPrimitive(meshId))
				channels["opacity"]?.let { put("opacity", it) }; channels["drawOrder"]?.let { put("draw_order", it) }
				channels["multiplyColor"]?.let { put("multiply_color", it) }; channels["screenColor"]?.let { put("screen_color", it) }
			})))
		} else kotlinx.serialization.json.buildJsonObject {
			put("op", kotlinx.serialization.json.JsonPrimitive("set")); put("target", kotlinx.serialization.json.JsonPrimitive("art_mesh:$meshId"))
			put("key", kotlinx.serialization.json.JsonObject(key.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
			put("channels", channels)
		}
		val overlay = current.config.rigEdits
		val journal = JournalRecording.record(model, current.authored.rig.puppet, overlay, kotlinx.serialization.json.JsonArray(listOf(command)), current.primitiveSkins)
		if (journal.isEmpty()) return null
		return current.config.copy(rigEdits = overlay.copy(authoringJournal = overlay.authoringJournal + journal))
	}

	/** The mesh drawn on top at canvas ([x], [y]) at [pose]: the front-most whose triangles hold the point. */
	fun meshAt(model: PuppetModel, pose: Map<String, Float>, x: Float, y: Float): String? {
		val verts = vertices(model, pose).toMap()
		for ((_, d) in model.drawables.withIndex().sortedByDescending { (i, d) -> d.drawOrder * 10_000 + i }) {
			val mesh = d.mesh ?: continue; val p = verts[d.id.raw] ?: continue
			val idx = mesh.indices; var t = 0
			while (t + 2 < idx.size) {
				val ax = p[idx[t] * 2]; val ay = p[idx[t] * 2 + 1]; val bx = p[idx[t + 1] * 2]; val by = p[idx[t + 1] * 2 + 1]; val cx = p[idx[t + 2] * 2]; val cy = p[idx[t + 2] * 2 + 1]
				val d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by); val d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy); val d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
				if (!((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))) return d.id.raw
				t += 3
			}
		}
		return null
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
