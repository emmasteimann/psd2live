package io.github.psd2live.ipad

import io.github.psd2live.core.SkeletonDraftEdits
import io.github.psd2live.core.SkeletonDraftIntent
import io.github.psd2live.core.SkeletonManualWeights
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.core.SkeletonWeightBrushMode

/**
 * Skeleton weights painted by hand (the desktop's skeleton draft "paint weights"): which bone moves each vertex of a
 * part's mesh, and how much. Strokes edit a draft [SkeletonSpec] through the desktop's own draft edits - no rebuild per
 * stroke - and [apply] builds the rig once with it, as every skeleton change does.
 */
object RigSkeletonWeights {
	/** A part mesh the skeleton skins, by drawable id and name. */
	class Mesh(val drawableId: String, val name: String)

	/** A bone that can move [Mesh]: its id and name. */
	class Bone(val id: String, val name: String)

	/** The meshes [spec]'s bones carry, in the rig's order. */
	fun meshes(e: RigEditing, spec: SkeletonSpec): List<Mesh> {
		val carried = io.github.psd2live.core.SkeletonRig.jointBones(spec).flatMap { it.drawableIds }.toSet()
		return e.model.rig.puppet.drawables.filter { it.id.raw in carried && it.mesh != null }.map { Mesh(it.id.raw, it.name) }
	}

	/** The bones that can move [drawableId] (its own bone's skeleton). */
	fun bones(spec: SkeletonSpec, drawableId: String): List<Bone> =
		SkeletonManualWeights.treeIds(spec, drawableId).mapNotNull { id -> spec.bone(id)?.let { Bone(it.id, it.name) } }

	/**
	 * [drawableId]'s weights under [spec] at rest: each vertex's canvas position and its weight for [boneId], and the
	 * mesh's triangles. Null when the skeleton doesn't skin it.
	 */
	fun weights(e: RigEditing, spec: SkeletonSpec, drawableId: String, boneId: String): WeightMesh? {
		val map = SkeletonManualWeights.capture(spec, e.model.rig.puppet, drawableId) ?: return null
		return WeightMesh(drawableId, map.positions.toFloatArray(), map.triangles.toIntArray(),
			FloatArray(map.weights.size) { map.weights[it][boneId] ?: 0f })
	}

	/** [spec] with one stroke ([points], canvas units) painted on [boneId]'s weights of [drawableId]. */
	fun paint(e: RigEditing, spec: SkeletonSpec, drawableId: String, boneId: String, points: List<Pair<Float, Float>>,
		radius: Float, strength: Float, mode: SkeletonWeightBrushMode): SkeletonSpec =
		if (points.isEmpty()) spec
		else SkeletonDraftEdits.apply(spec, e.model, SkeletonDraftIntent.PaintWeights(drawableId, boneId, points, radius, strength, mode)).spec

	/** [spec] with [drawableId]'s weights tidied: at most two bones a vertex, stray specks dropped (desktop's Clean up). */
	fun cleanup(e: RigEditing, spec: SkeletonSpec, drawableId: String): SkeletonSpec =
		SkeletonDraftEdits.apply(spec, e.model, SkeletonDraftIntent.CleanupWeights(drawableId)).spec

	/** [spec] with [drawableId] back on the automatic weights. */
	fun clear(e: RigEditing, spec: SkeletonSpec, drawableId: String): SkeletonSpec =
		SkeletonDraftEdits.apply(spec, e.model, SkeletonDraftIntent.ClearWeights(drawableId)).spec

	/** Builds the rig with [spec] (the painted weights) as one undoable step. Blocking. */
	fun apply(e: RigEditing, spec: SkeletonSpec) {
		val m = e.model
		SkeletonDraftEdits.validated(spec, m.rig.puppet.drawables.mapTo(HashSet()) { it.id.raw })
		e.rebuild(m.config.copy(rigEdits = m.config.rigEdits.copy(skeleton = spec)))
	}
}

/**
 * Simulation weights painted by hand (the desktop's weight brush and gradient on vertex groups): per mesh, how firmly
 * each vertex is pinned, how stiff, heavy, damped or wind-blown it is, and its goal pull. A stroke is the desktop's
 * `vertex_group_paint` edit, recorded in the journal on the quick replay - no rebuild, so it shows at once.
 */
object RigSimulationWeights {
	/** The kinds the brush paints, in the desktop's order. */
	val kinds = listOf(org.umamo.runtime.model.VertexGroupKind.PIN, org.umamo.runtime.model.VertexGroupKind.STIFFNESS,
		org.umamo.runtime.model.VertexGroupKind.GOAL, org.umamo.runtime.model.VertexGroupKind.MASS,
		org.umamo.runtime.model.VertexGroupKind.DAMPING, org.umamo.runtime.model.VertexGroupKind.WIND)

	/** Every art mesh, in the rig's order. */
	fun meshes(e: RigEditing): List<RigSkeletonWeights.Mesh> =
		e.model.rig.puppet.drawables.filter { it.mesh != null }.map { RigSkeletonWeights.Mesh(it.id.raw, it.name) }

	/** [drawableId] at [pose] (canvas positions) with its [kind] group's weights (0 where it has none). */
	fun weights(e: RigEditing, drawableId: String, kind: org.umamo.runtime.model.VertexGroupKind, pose: Map<String, Float> = emptyMap()): WeightMesh? {
		val puppet = e.model.rig.puppet
		val id = org.umamo.runtime.model.DrawableId(drawableId)
		val mesh = puppet.drawables.firstOrNull { it.id == id }?.mesh ?: return null
		val surface = io.github.psd2live.core.CanvasWeightAuthoring.surfaces(puppet, listOf(id), pose, false).single()
		val positions = FloatArray(surface.points.size * 2) { if (it % 2 == 0) surface.points[it / 2].x else surface.points[it / 2].y }
		val group = io.github.psd2live.core.CanvasWeightAuthoring.group(puppet, id, kind)?.weights?.takeIf { it.size == surface.points.size }
		return WeightMesh(drawableId, positions, mesh.indices, group ?: FloatArray(surface.points.size))
	}

	/** Whether [drawableId] has a [kind] group yet. */
	fun has(e: RigEditing, drawableId: String, kind: org.umamo.runtime.model.VertexGroupKind) =
		io.github.psd2live.core.CanvasWeightAuthoring.group(e.model.rig.puppet, org.umamo.runtime.model.DrawableId(drawableId), kind) != null

	/** One brush stroke ([points], canvas units at [pose]) on [kind] of [drawableId], as one undoable step. */
	fun paint(e: RigEditing, drawableId: String, kind: org.umamo.runtime.model.VertexGroupKind, points: List<Pair<Float, Float>>,
		radius: Float, hardness: Float, mode: io.github.psd2live.core.CanvasWeightAuthoring.Mode, strength: Float, pose: Map<String, Float> = emptyMap()) {
		if (points.isEmpty()) return
		record(e, kotlinx.serialization.json.buildJsonObject {
			put("action", kotlinx.serialization.json.JsonPrimitive("brush")); common(drawableId, kind, mode, strength, pose)
			put("points", kotlinx.serialization.json.JsonArray(points.map { (x, y) -> kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(x), kotlinx.serialization.json.JsonPrimitive(y))) }))
			put("radius", kotlinx.serialization.json.JsonPrimitive(radius)); put("hardness", kotlinx.serialization.json.JsonPrimitive(hardness))
		})
	}

	/** A linear gradient from [from] (full) to [to] (none) on [kind] of [drawableId], as one undoable step. */
	fun gradient(e: RigEditing, drawableId: String, kind: org.umamo.runtime.model.VertexGroupKind, from: Pair<Float, Float>, to: Pair<Float, Float>,
		mode: io.github.psd2live.core.CanvasWeightAuthoring.Mode, strength: Float, pose: Map<String, Float> = emptyMap()) {
		fun point(p: Pair<Float, Float>) = kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(p.first), kotlinx.serialization.json.JsonPrimitive(p.second)))
		record(e, kotlinx.serialization.json.buildJsonObject {
			put("action", kotlinx.serialization.json.JsonPrimitive("gradient")); common(drawableId, kind, mode, strength, pose)
			put("from", point(from)); put("to", point(to))
		})
	}

	/** [kind] of [drawableId] flipped (w -> 1 - w). */
	fun invert(e: RigEditing, drawableId: String, kind: org.umamo.runtime.model.VertexGroupKind) = record(e, kotlinx.serialization.json.buildJsonObject {
		put("action", kotlinx.serialization.json.JsonPrimitive("invert")); put("targets", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(drawableId))))
		put("kind", kotlinx.serialization.json.JsonPrimitive(kind.jsonName))
	})

	/** [kind] of [drawableId] set to [value] everywhere (0: cleared to nothing, 1: full). */
	fun fill(e: RigEditing, drawableId: String, kind: org.umamo.runtime.model.VertexGroupKind, value: Float) {
		val puppet = e.model.rig.puppet
		val id = org.umamo.runtime.model.DrawableId(drawableId)
		val mesh = puppet.drawables.firstOrNull { it.id == id }?.mesh ?: return
		e.journal(listOf(io.github.psd2live.core.VertexGroupJournal.encode(org.umamo.runtime.model.VertexGroup(
			io.github.psd2live.core.CanvasWeightAuthoring.name(puppet, id, kind), id, kind, FloatArray(mesh.vertexCount) { value.coerceIn(0f, 1f) }))))
	}

	/** [kind] of [drawableId] removed (the simulation's default applies again). */
	fun delete(e: RigEditing, drawableId: String, kind: org.umamo.runtime.model.VertexGroupKind) {
		val group = io.github.psd2live.core.CanvasWeightAuthoring.group(e.model.rig.puppet, org.umamo.runtime.model.DrawableId(drawableId), kind) ?: return
		e.journal(listOf(io.github.psd2live.core.VertexGroupJournal.delete(drawableId, group.name)))
	}

	private fun kotlinx.serialization.json.JsonObjectBuilder.common(drawableId: String, kind: org.umamo.runtime.model.VertexGroupKind,
		mode: io.github.psd2live.core.CanvasWeightAuthoring.Mode, strength: Float, pose: Map<String, Float>) {
		put("targets", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(drawableId))))
		put("kind", kotlinx.serialization.json.JsonPrimitive(kind.jsonName)); put("mode", kotlinx.serialization.json.JsonPrimitive(mode.name.lowercase()))
		put("strength", kotlinx.serialization.json.JsonPrimitive(strength.coerceIn(0f, 1f)))
		put("pose", kotlinx.serialization.json.JsonObject(pose.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
	}

	private fun record(e: RigEditing, edit: kotlinx.serialization.json.JsonObject) {
		val commands = io.github.psd2live.application.WorkspaceVertexGroupPaint.commands(e.model.rig.puppet, edit)
		if (commands.isNotEmpty()) e.journal(commands.map { it as kotlinx.serialization.json.JsonObject })
	}
}

/** One mesh as a weight canvas shows it: vertex canvas positions (x, y pairs), triangles and a 0..1 weight a vertex. */
class WeightMesh(val drawableId: String, val positions: FloatArray, val triangles: IntArray, val weights: FloatArray)
