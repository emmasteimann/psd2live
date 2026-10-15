package io.github.psd2live.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/**
 * Layerloom patch (ONE-APP-PLAN S4.6): blend-shape weight limits as Cubism sets them, per blend-shape parameter. A
 * limit is a curve over another parameter that caps the blend shape's weight (several limits: the lowest cap wins).
 * The project keeps them by blend-shape parameter (`rigEdits.blendLimits`); after replay they're set on every
 * object's binding of that parameter, so objects keyed later get them too.
 */
object BlendLimits {
	/** `{"parameter": blend parameter, "limits": [{"parameter": constraint, "points": [[value, weight], ...]}]}`; no limits removes them. */
	fun put(current: Map<String, List<BlendWeightLimit>>, request: JsonObject): Map<String, List<BlendWeightLimit>> {
		val parameter = requireNotNull(request["parameter"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }) { "blend_limits_put needs a parameter" }
		val limits = (request["limits"] as? JsonArray).orEmpty().map { limitFromJson(it.jsonObject) }.filter { it.points.isNotEmpty() }
		require(limits.none { it.parameterId.raw == parameter }) { "A blend shape can't be limited by its own parameter" }
		require(limits.map { it.parameterId }.distinct().size == limits.size) { "One limit per constraint parameter" }
		return if (limits.isEmpty()) current - parameter else current + (parameter to limits)
	}

	fun limitFromJson(o: JsonObject): BlendWeightLimit = BlendWeightLimit(
		ParameterId(requireNotNull(o["parameter"]?.jsonPrimitive?.contentOrNull) { "A limit needs its parameter" }),
		(o["points"] as? JsonArray).orEmpty().mapNotNull { p ->
			val pair = p as? JsonArray ?: return@mapNotNull null
			val v = (pair.getOrNull(0) as? JsonPrimitive)?.floatOrNull; val w = (pair.getOrNull(1) as? JsonPrimitive)?.floatOrNull
			if (v == null || w == null || !v.isFinite() || !w.isFinite()) null else BlendWeightLimitPoint(v, w.coerceIn(0f, 1f))
		}.sortedBy { it.value }.distinctBy { it.value })

	fun limitToJson(limit: BlendWeightLimit): JsonObject = buildJsonObject {
		put("parameter", limit.parameterId.raw)
		putJsonArray("points") { limit.points.forEach { add(JsonArray(listOf(JsonPrimitive(it.value), JsonPrimitive(it.weight)))) } }
	}

	fun toJson(all: Map<String, List<BlendWeightLimit>>): JsonObject = JsonObject(all.toSortedMap().mapValues { (_, limits) -> JsonArray(limits.map(::limitToJson)) })

	fun fromJson(o: JsonObject?): Map<String, List<BlendWeightLimit>> =
		o.orEmpty().mapValues { (_, v) -> v.jsonArray.map { limitFromJson(it.jsonObject) } }.filterValues { it.isNotEmpty() }

	fun request(parameter: String, limits: List<BlendWeightLimit>): JsonObject = buildJsonObject {
		put("parameter", parameter); put("limits", JsonArray(limits.map(::limitToJson)))
	}

	/** [model] with each binding of a limited parameter carrying that parameter's limits (over parameters the model has). */
	fun apply(model: PuppetModel, all: Map<String, List<BlendWeightLimit>>): PuppetModel {
		if (all.isEmpty()) return model
		val present = model.parameters.mapTo(HashSet()) { it.id }
		val byParameter = all.mapKeys { ParameterId(it.key) }.mapValues { (_, limits) -> limits.filter { it.parameterId in present } }
		fun <F : Any> List<BlendShapeBinding<F>>.limited(): List<BlendShapeBinding<F>> =
			if (none { it.parameterId in byParameter }) this else map { b -> byParameter[b.parameterId]?.let { b.copy(limits = it) } ?: b }
		return model.copy(
			drawables = model.drawables.map { d -> if (d.blendShapes.isEmpty()) d else d.copy(blendShapes = d.blendShapes.limited()) },
			deformers = model.deformers.map { d ->
				when (d) {
					is Deformer.Warp -> if (d.blendShapes.isEmpty()) d else d.copy(blendShapes = d.blendShapes.limited())
					is Deformer.Rotation -> if (d.blendShapes.isEmpty()) d else d.copy(blendShapes = d.blendShapes.limited())
					else -> d
				}
			},
			parts = model.parts.map { p -> if (p.blendShapes.isEmpty()) p else p.copy(blendShapes = p.blendShapes.limited()) },
		)
	}

	/** The cap a set of limits puts on a weight at [pose]: the lowest curve value (piecewise linear, ends held). */
	fun cap(limits: List<BlendWeightLimit>, pose: Map<String, Float>, defaults: Map<String, Float>): Float =
		limits.minOfOrNull { limit ->
			val x = pose[limit.parameterId.raw] ?: defaults[limit.parameterId.raw] ?: 0f
			val pts = limit.points
			when {
				pts.isEmpty() -> 1f
				x <= pts.first().value -> pts.first().weight
				x >= pts.last().value -> pts.last().weight
				else -> {
					val i = pts.indexOfLast { it.value <= x }
					val a = pts[i]; val b = pts[i + 1]
					a.weight + (b.weight - a.weight) * (x - a.value) / (b.value - a.value)
				}
			}
		} ?: 1f
}
