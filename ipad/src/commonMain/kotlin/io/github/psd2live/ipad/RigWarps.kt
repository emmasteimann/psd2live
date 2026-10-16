package io.github.psd2live.ipad

import io.github.psd2live.core.BezierHandleDir
import io.github.psd2live.core.RigBezierJournal
import io.github.psd2live.core.RigGeometryTools
import io.github.psd2live.core.RigWarpTopology
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId

/**
 * Warp editing after making a warp (the desktop's Bezier warp controls and grid settings): a warp's cage drawn as a few
 * curved anchors with handles - drag them for smooth bends with few points - its curve divisions, and the lattice's own
 * grid size. Edits land at the chosen key of the warp's parameters (at rest when it has none) as one undoable step.
 */
object RigWarps {
	/** A warp the brush and these tools can shape (sway and physics warps are the simulation's, left out). */
	class Warp(val id: String, val name: String, val rows: Int, val columns: Int)

	/** A control as the canvas shows it: canvas position, and what it is (an anchor, or a handle in a direction). */
	class Control(val row: Int, val column: Int, val direction: BezierHandleDir?, val x: Float, val y: Float, val localX: Float, val localY: Float)

	class View(val lattice: FloatArray, val latticeRows: Int, val latticeColumns: Int, val bezierRows: Int, val bezierColumns: Int,
		val controls: List<Control>, val axes: List<Pair<String, FloatArray>>)

	fun list(e: RigEditing): List<Warp> = e.puppet.deformers.filterIsInstance<Deformer.Warp>()
		.filter { !it.id.raw.endsWith("Physics") && !it.id.raw.contains("Swing") }
		.map { Warp(it.id.raw, it.name, it.rows, it.columns) }

	/** The warp under canvas ([x], [y]) at [pose]: the smallest whose cage holds the point. */
	fun at(e: RigEditing, pose: Map<String, Float>, x: Float, y: Float): String? = io.github.psd2live.core.PoseTweaks.warpUnder(e.puppet, pose, x, y)?.id

	/** [pose] reduced to the key an edit of [id] lands at: each of its axes at the key nearest the pose. */
	fun key(e: RigEditing, id: String, pose: Map<String, Float>): Map<String, Float> {
		val g = RigGeometryTools.geometry(e.puppet, "warp", id, pose)
		return g.axes.associate { a -> val v = pose[a.parameterId.raw] ?: 0f; a.parameterId.raw to a.keys.minBy { kotlin.math.abs(it - v) } }
	}

	/** [id] at [key] as the canvas shows it: its lattice, Bezier controls (canvas and parent-local), divisions and axes. */
	fun view(e: RigEditing, id: String, key: Map<String, Float>): View {
		val puppet = e.puppet
		val g = RigGeometryTools.geometry(puppet, "warp", id, key)
		val mapping = mapping(e, g.parent, key)
		val controls = RigBezierJournal.read(puppet, e.model.config.rigEdits, id, key)
		fun canvas(x: Float, y: Float): Pair<Float, Float> { val w = mapping.localToWorld(floatArrayOf(x, y)); return w[0] to -w[1] }
		val list = controls.state.anchors.values.map { a -> val (cx, cy) = canvas(a.x, a.y); Control(a.row, a.col, null, cx, cy, a.x, a.y) } +
			controls.state.handles.values.map { h -> val (cx, cy) = canvas(h.x, h.y); Control(h.row, h.col, h.dir, cx, cy, h.x, h.y) }
		val lattice = mapping.localToWorld(g.points).also { for (i in 1 until it.size step 2) it[i] = -it[i] }
		return View(lattice, g.rows ?: 0, g.columns ?: 0, controls.state.bezierRows, controls.state.bezierCols, list,
			g.axes.map { it.parameterId.raw to it.keys })
	}

	/**
	 * Moves control [c] of [id] at [key] by a canvas drag ([dx], [dy]): the drag converted to the warp's parent units
	 * through the local slope of the mapping at that control, so the point lands under the finger.
	 */
	fun drag(e: RigEditing, id: String, key: Map<String, Float>, c: Control, dx: Float, dy: Float, smooth: Boolean = true) {
		val g = RigGeometryTools.geometry(e.puppet, "warp", id, key)
		val m = mapping(e, g.parent, key)
		val eps = 1e-3f * (1f + kotlin.math.abs(c.localX) + kotlin.math.abs(c.localY))
		val p0 = m.localToWorld(floatArrayOf(c.localX, c.localY)); val px = m.localToWorld(floatArrayOf(c.localX + eps, c.localY)); val py = m.localToWorld(floatArrayOf(c.localX, c.localY + eps))
		// Canvas y is world y flipped.
		val a = (px[0] - p0[0]) / eps; val b = (py[0] - p0[0]) / eps; val cc = -(px[1] - p0[1]) / eps; val d = -(py[1] - p0[1]) / eps
		val det = a * d - b * cc
		require(kotlin.math.abs(det) > 1e-9f) { "This control can't move here" }
		val lx = (d * dx - b * dy) / det; val ly = (-cc * dx + a * dy) / det
		record(e, if (c.direction == null) "anchor" else "handle", id, key, buildJsonObject {
			put("row", c.row); put("column", c.column); put("x", c.localX + lx); put("y", c.localY + ly)
			c.direction?.let { put("direction", it.name.lowercase()); put("smooth", smooth) }
		})
	}

	/** How many curved sections the controls have across and down (1..16). */
	fun divisions(e: RigEditing, id: String, key: Map<String, Float>, rows: Int, columns: Int) =
		record(e, "divisions", id, key, buildJsonObject { put("rows", rows.coerceIn(1, 16)); put("columns", columns.coerceIn(1, 16)) })

	/** Straightens the curves back to the plain lattice shape at [key]. */
	fun reset(e: RigEditing, id: String, key: Map<String, Float>) = record(e, "reset", id, key, buildJsonObject {})

	/** The lattice's own grid (1..32 each way), resampled so the shape stays. */
	fun grid(e: RigEditing, id: String, rows: Int, columns: Int) {
		val warp = e.puppet.deformers.singleOrNull { it.id.raw == id } as? Deformer.Warp ?: return
		if (rows == warp.rows && columns == warp.columns) return
		e.journal(listOf(RigWarpTopology.prepare(e.puppet, id, rows.coerceIn(1, 32), columns.coerceIn(1, 32))))
	}

	private fun record(e: RigEditing, action: String, id: String, key: Map<String, Float>, fields: JsonObject) {
		val request = buildJsonObject {
			put("target", "warp:$id")
			putJsonObject("coordinate") { key.forEach { (k, v) -> put(k, v) } }
			putJsonObject("pose") { key.forEach { (k, v) -> put(k, v) } }
			fields.forEach { (k, v) -> put(k, v) }
		}
		val command = RigBezierJournal.prepare(e.puppet, e.model.config.rigEdits, action, request)
		e.journal(listOf(command))
	}

	private fun mapping(e: RigEditing, parent: String?, pose: Map<String, Float>): DrawableSpaceMapping {
		val puppet = e.puppet
		val defaults = puppet.parameters.associate { it.id to it.default }
		val worlds = buildDeformerWorlds(puppet.deformers, { pose[it.raw] ?: defaults[it] ?: 0f }, { defaults[it] ?: 0f })
		return DrawableSpaceMapping(parent?.let { worlds[DeformerId(it)] })
	}
}
