package io.github.psd2live.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.PuppetModel

/**
 * Layerloom patch (ONE-APP-PLAN S4.7): Cubism's multiple-keys editing. A deformation edit of a mesh or warp at one
 * key becomes the same change, as an offset per point, at every key of the object's grid, so a fix made facing
 * forward holds when the head turns. Structure edits, rotations and objects without a grid pass through unchanged.
 */
object MultiKeyEdit {
	/** Grids with more keys than this are left to one key at a time. */
	const val MAX_KEYS = 81

	/** [commands] with each deformation `canvas_geometry` edit of a mesh or warp repeated at every key of its grid. */
	fun expand(model: PuppetModel, commands: List<JsonObject>): List<JsonObject> = commands.flatMap { expandOne(model, it) }

	private fun expandOne(model: PuppetModel, command: JsonObject): List<JsonObject> {
		if (command["op"]?.jsonPrimitive?.contentOrNull != "canvas_geometry") return listOf(command)
		if (command["preserve_image"]?.jsonPrimitive?.booleanOrNull == true) return listOf(command)
		val kind = command["kind"]?.jsonPrimitive?.contentOrNull ?: return listOf(command)
		if (kind != "mesh" && kind != "warp") return listOf(command)
		val id = command["id"]?.jsonPrimitive?.contentOrNull ?: return listOf(command)
		val key = (command["key"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.float } ?: return listOf(command)
		val points = (command["points"] as? JsonArray)?.map { it.jsonPrimitive.float }?.toFloatArray() ?: return listOf(command)
		val defaults = model.parameters.associate { it.id.raw to it.default }
		val geometry = RigGeometryTools.geometry(model, kind, id, defaults + key)
		val axes = geometry.axes
		if (axes.isEmpty() || geometry.points.size != points.size) return listOf(command)
		val count = axes.fold(1) { n, a -> n * a.keys.size }
		if (count <= 1 || count > MAX_KEYS) return listOf(command)
		val delta = FloatArray(points.size) { points[it] - geometry.points[it] }
		// Every combination of the grid's keys, the edited key first (it keeps exactly the points it was given).
		var combos = listOf(emptyMap<String, Float>())
		for (axis in axes) combos = combos.flatMap { c -> axis.keys.map { k -> c + (axis.parameterId.raw to k) } }
		val edited = axes.associate { it.parameterId.raw to (key[it.parameterId.raw] ?: defaults[it.parameterId.raw] ?: 0f) }
		val others = combos.filter { c -> c.any { (p, v) -> kotlin.math.abs(v - (edited[p] ?: v)) > 1e-5f } }
		return listOf(command) + others.map { combo ->
			val at = RigGeometryTools.geometry(model, kind, id, defaults + key + combo).points
			JsonObject(command + mapOf(
				"key" to JsonObject((key + combo).mapValues { JsonPrimitive(it.value) }),
				"points" to JsonArray(at.indices.map { JsonPrimitive(at[it] + delta[it]) }),
			))
		}
	}
}
