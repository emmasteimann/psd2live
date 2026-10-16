package io.github.psd2live.ipad

import io.github.psd2live.application.WorkspaceGlueEdit
import io.github.psd2live.core.PoseTweaks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mesh glue (the desktop's Glue tool, `canvas_glue_edit`): welds two meshes' seams point to point so they move as one -
 * neck to head, a sleeve to its arm - instead of opening a gap when she moves. Welds are made where both meshes rest, so
 * gluing never changes the picture at rest. Each edit is one undoable step on the quick replay.
 */
object RigGlue {
	/** A weld seen on the canvas: the two meshes' vertex positions at a pose, and how much each side gives. */
	class Weld(val ax: Float, val ay: Float, val bx: Float, val by: Float, val weightA: Float)

	/** Every art mesh, by id and name. */
	fun meshes(e: RigEditing): List<Pair<String, String>> = e.puppet.drawables.filter { it.mesh != null }.map { it.id.raw to it.name }

	/** The mesh on top at canvas ([x], [y]) at [pose]. */
	fun meshAt(e: RigEditing, pose: Map<String, Float>, x: Float, y: Float): String? = PoseTweaks.meshAt(e.puppet, pose, x, y)

	/** The pairs of meshes glued together, by id. */
	fun pairs(e: RigEditing): List<Pair<String, String>> =
		e.puppet.glues.map { it.meshA.raw to it.meshB.raw }.distinctBy { setOf(it.first, it.second) }

	/** The welds between [a] and [b] at [pose]. */
	fun welds(e: RigEditing, a: String, b: String, pose: Map<String, Float> = emptyMap()): List<Weld> {
		val verts = PoseTweaks.vertices(e.puppet, pose).toMap()
		val pa = verts[a] ?: return emptyList(); val pb = verts[b] ?: return emptyList()
		return e.puppet.glues.filter { (it.meshA.raw == a && it.meshB.raw == b) || (it.meshA.raw == b && it.meshB.raw == a) }.flatMap { g ->
			val flipped = g.meshA.raw != a
			g.pairs.mapNotNull { p ->
				val ia = if (flipped) p.indexB else p.indexA; val ib = if (flipped) p.indexA else p.indexB
				if (ia * 2 + 1 >= pa.size || ib * 2 + 1 >= pb.size) null
				else Weld(pa[ia * 2], pa[ia * 2 + 1], pb[ib * 2], pb[ib * 2 + 1], if (flipped) p.weightB else p.weightA)
			}
		}
	}

	/** [mesh]'s vertices within [radius] of the canvas stroke [points] at [pose]. */
	fun under(e: RigEditing, mesh: String, points: List<Pair<Float, Float>>, radius: Float, pose: Map<String, Float> = emptyMap()): Set<Int> {
		val pts = PoseTweaks.vertices(e.puppet, pose).toMap()[mesh] ?: return emptySet()
		val r2 = radius * radius
		val hit = HashSet<Int>()
		for (i in 0 until pts.size / 2) for ((x, y) in points) {
			val dx = pts[i * 2] - x; val dy = pts[i * 2 + 1] - y
			if (dx * dx + dy * dy <= r2) { hit += i; break }
		}
		return hit
	}

	/** Welds the whole seam between [a] and [b]: both outlines, where they come within [distance] canvas px. */
	fun weldAll(e: RigEditing, a: String, b: String, distance: Float = 40f) = edit(e, a, b, "remerge", emptySet(), emptySet(), distance)

	/** Adds welds along a brushed stroke ([points], canvas) where the two meshes overlap. */
	fun brush(e: RigEditing, a: String, b: String, points: List<Pair<Float, Float>>, radius: Float, distance: Float = 40f) {
		val ha = under(e, a, points, radius); val hb = under(e, b, points, radius)
		require(ha.isNotEmpty() || hb.isNotEmpty()) { "Brush along where the two parts meet" }
		edit(e, a, b, "brush", ha, hb, distance)
	}

	/** Removes the welds under a stroke. */
	fun unglue(e: RigEditing, a: String, b: String, points: List<Pair<Float, Float>>, radius: Float) {
		val ha = under(e, a, points, radius); val hb = under(e, b, points, radius)
		if (ha.isEmpty() && hb.isEmpty()) return
		edit(e, a, b, "unglue", ha, hb, 40f)
	}

	/** Every weld between [a] and [b] removed. */
	fun removeAll(e: RigEditing, a: String, b: String) {
		val all = welds(e, a, b)
		if (all.isEmpty()) return
		val ha = e.puppet.glues.filter { (it.meshA.raw == a && it.meshB.raw == b) || (it.meshA.raw == b && it.meshB.raw == a) }
			.flatMap { g -> g.pairs.map { if (g.meshA.raw == a) it.indexA else it.indexB } }.toSet()
		edit(e, a, b, "unglue", ha, emptySet(), 40f)
	}

	/**
	 * Which side follows which under a stroke: "balance" (both meet in the middle), "a" (A follows B) or "b" (B follows
	 * A), by [delta] per stroke.
	 */
	fun pull(e: RigEditing, a: String, b: String, points: List<Pair<Float, Float>>, radius: Float, mode: String, delta: Float = 0.35f) {
		val ha = under(e, a, points, radius); val hb = under(e, b, points, radius)
		if (ha.isEmpty() && hb.isEmpty()) return
		edit(e, a, b, "weights", ha, hb, 40f, mode, delta)
	}

	private fun edit(e: RigEditing, a: String, b: String, action: String, hitsA: Set<Int>, hitsB: Set<Int>, distance: Float,
		mode: String = "balance", delta: Float = 0.35f) {
		val command = WorkspaceGlueEdit.command(e.puppet, buildJsonObject {
			put("mesh_a", a); put("mesh_b", b); put("action", action)
			put("hits_a", JsonArray(hitsA.sorted().map(::JsonPrimitive))); put("hits_b", JsonArray(hitsB.sorted().map(::JsonPrimitive)))
			put("distance", distance); put("weight_mode", mode); put("delta", delta)
		})
		e.journal(listOf(command))
	}
}
