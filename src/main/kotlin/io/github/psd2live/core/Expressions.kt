package io.github.psd2live.core

import io.github.psd2live.format.model.ExpressionBlend
import io.github.psd2live.format.model.ExpressionIR
import io.github.psd2live.format.model.ExpressionParameter
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
import org.umamo.runtime.model.PuppetModel

/** One parameter of an expression: added to, multiplied into or overwriting the value under it (Cubism's exp3). */
data class ExpressionEntry(val parameter: String, val blend: ExpressionBlend, val value: Float)

/**
 * Layerloom patch (ONE-APP-PLAN S4.1): an authored facial expression, as Cubism's exp3 holds one. Exported to the
 * Cubism model (exp3 + model3 `Expressions`), VTube Studio (a hotkey each) and the PSD2Live runtime.
 */
data class ExpressionEdit(
	val id: String,
	val name: String,
	val fadeIn: Float = DEFAULT_FADE,
	val fadeOut: Float = DEFAULT_FADE,
	val entries: List<ExpressionEntry> = emptyList(),
) {
	init {
		require(id.isNotBlank()) { "An expression needs an ID" }
		require(fadeIn >= 0f && fadeOut >= 0f) { "Fades can't be negative" }
		require(entries.map { it.parameter }.distinct().size == entries.size) { "An expression sets each parameter once" }
	}

	companion object { const val DEFAULT_FADE = 0.5f }
}

object Expressions {
	fun newId(existing: List<ExpressionEdit>): String {
		val taken = existing.mapTo(HashSet()) { it.id }
		return generateSequence(1) { it + 1 }.map { "expression_$it" }.first { it !in taken }
	}

	fun uniqueName(existing: List<ExpressionEdit>, wanted: String, except: String? = null): String {
		val taken = existing.filter { it.id != except }.mapTo(HashSet()) { it.name.lowercase() }
		if (wanted.lowercase() !in taken) return wanted
		return generateSequence(2) { it + 1 }.map { "$wanted $it" }.first { it.lowercase() !in taken }
	}

	fun blendName(blend: ExpressionBlend): String = when (blend) {
		ExpressionBlend.ADD -> "Add"; ExpressionBlend.MULTIPLY -> "Multiply"; ExpressionBlend.OVERWRITE -> "Overwrite"
	}

	fun parseBlend(text: String): ExpressionBlend = ExpressionBlend.entries.firstOrNull { blendName(it).equals(text, true) || it.name.equals(text, true) }
		?: throw IllegalArgumentException("Blend must be Add, Multiply or Overwrite: $text")

	fun toJson(e: ExpressionEdit): JsonObject = buildJsonObject {
		put("id", e.id); put("name", e.name); put("fade_in", e.fadeIn); put("fade_out", e.fadeOut)
		putJsonArray("parameters") {
			e.entries.forEach { add(buildJsonObject { put("parameter", it.parameter); put("blend", blendName(it.blend)); put("value", it.value) }) }
		}
	}

	fun fromJson(o: JsonObject): ExpressionEdit = ExpressionEdit(
		id = requireNotNull(o["id"]?.jsonPrimitive?.contentOrNull) { "An expression needs an id" },
		name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
		fadeIn = o["fade_in"]?.jsonPrimitive?.floatOrNull ?: ExpressionEdit.DEFAULT_FADE,
		fadeOut = o["fade_out"]?.jsonPrimitive?.floatOrNull ?: ExpressionEdit.DEFAULT_FADE,
		entries = (o["parameters"] as? JsonArray).orEmpty().map { p ->
			val e = p.jsonObject
			ExpressionEntry(requireNotNull(e["parameter"]?.jsonPrimitive?.contentOrNull) { "An expression entry needs a parameter" },
				parseBlend(e["blend"]?.jsonPrimitive?.contentOrNull ?: "Add"), e["value"]?.jsonPrimitive?.floatOrNull ?: 0f)
		},
	)

	/**
	 * The document edits: `put` creates or replaces one whole expression (`expression`: its JSON; a missing id makes a
	 * new one, a taken name gets a number), `delete` removes one by `id`.
	 */
	fun edit(current: List<ExpressionEdit>, mode: String, request: JsonObject): List<ExpressionEdit> = when (mode) {
		"put" -> {
			val body = requireNotNull(request["expression"] as? JsonObject) { "expression_put needs an expression" }
			val id = body["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: newId(current)
			val parsed = fromJson(JsonObject(body + ("id" to JsonPrimitive(id))))
			val named = parsed.copy(name = uniqueName(current, parsed.name.ifBlank { "Expression" }, except = id))
			val at = current.indexOfFirst { it.id == id }
			if (at < 0) current + named else current.toMutableList().also { it[at] = named }
		}
		"delete" -> {
			val id = requireNotNull(request["id"]?.jsonPrimitive?.contentOrNull) { "expression_delete needs an id" }
			require(current.any { it.id == id }) { "Expression not found: $id" }
			current.filter { it.id != id }
		}
		else -> throw IllegalArgumentException("Unknown expression edit: $mode")
	}

	/** What the model exports: the project's expressions over the parameters it has, or the standard set when it has none. */
	fun forExport(authored: List<ExpressionEdit>, model: PuppetModel): List<ExpressionIR> {
		if (authored.isEmpty()) return RuntimeExtras.expressions(model)
		val present = model.parameters.mapTo(HashSet()) { it.id.raw }
		return authored.map { e ->
			ExpressionIR(e.id, e.name, e.fadeIn, e.fadeOut, e.entries.filter { it.parameter in present }.map { ExpressionParameter(it.parameter, it.blend, it.value) })
		}
	}

	/** The standard set as editable expressions, for "add starter set". */
	fun starters(model: PuppetModel, existing: List<ExpressionEdit>): List<ExpressionEdit> {
		var list = existing
		for (e in RuntimeExtras.expressions(model)) {
			list = list + ExpressionEdit(newId(list), uniqueName(list, e.name), e.fadeIn, e.fadeOut,
				e.parameters.map { ExpressionEntry(it.parameter, it.blend, it.value) })
		}
		return list
	}

	/** [entries] over [base] as a runtime applies them, clamped to each parameter's range. */
	fun apply(base: Map<String, Float>, entries: List<ExpressionEntry>, ranges: Map<String, ClosedFloatingPointRange<Float>>): Map<String, Float> =
		entries.mapNotNull { e ->
			val range = ranges[e.parameter] ?: return@mapNotNull null
			val under = base[e.parameter] ?: range.start.coerceAtLeast(0f).coerceAtMost(range.endInclusive)
			val value = when (e.blend) { ExpressionBlend.ADD -> under + e.value; ExpressionBlend.MULTIPLY -> under * e.value; ExpressionBlend.OVERWRITE -> e.value }
			e.parameter to value.coerceIn(range.start, range.endInclusive)
		}.toMap()

	/**
	 * A Layerloom rig job's expressions: `[{"name", "fadeIn"?, "fadeOut"?, "parameters": [{"Id", "Value", "Blend"}]}]`,
	 * exp3's own keys for the parameters (lowercase keys work too).
	 */
	fun fromJob(items: JsonArray): List<ExpressionEdit> {
		var list = emptyList<ExpressionEdit>()
		for (item in items) {
			val o = item as? JsonObject ?: continue
			fun text(vararg keys: String) = keys.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull }
			fun num(vararg keys: String) = keys.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.floatOrNull }
			val entries = (o["parameters"] as? JsonArray).orEmpty().mapNotNull { p ->
				val e = p as? JsonObject ?: return@mapNotNull null
				val id = (e["Id"] ?: e["id"] ?: e["parameter"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				ExpressionEntry(id, parseBlend((e["Blend"] ?: e["blend"])?.jsonPrimitive?.contentOrNull ?: "Add"),
					(e["Value"] ?: e["value"])?.jsonPrimitive?.floatOrNull ?: 0f)
			}.distinctBy { it.parameter }
			if (entries.isEmpty()) continue
			list = list + ExpressionEdit(newId(list), uniqueName(list, text("name", "Name")?.takeIf { it.isNotBlank() } ?: "Expression"),
				(num("fadeIn", "FadeInTime") ?: ExpressionEdit.DEFAULT_FADE).coerceAtLeast(0f),
				(num("fadeOut", "FadeOutTime") ?: ExpressionEdit.DEFAULT_FADE).coerceAtLeast(0f), entries)
		}
		return list
	}

}

/**
 * Layerloom patch (ONE-APP-PLAN S4.3): Cubism's pose groups, parts of which one shows at a time (arm A / arm B),
 * exported as pose3.
 */
object PoseGroups {
	const val DEFAULT_FADE = 0.5f

	/** `pose_put`: `{"groups": [[part ids]], "fade_in": seconds}` replaces them; empty groups and repeats are dropped. */
	fun put(request: JsonObject): Pair<List<List<String>>, Float> {
		val seen = HashSet<String>()
		val groups = (request["groups"] as? JsonArray).orEmpty().map { group ->
			(group as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.isNotBlank() && seen.add(id) } }
		}.filter { it.isNotEmpty() }
		val fade = (request["fade_in"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it >= 0f } ?: DEFAULT_FADE
		return groups to fade
	}

	fun request(groups: List<List<String>>, fadeIn: Float): JsonObject = buildJsonObject {
		putJsonArray("groups") { groups.forEach { g -> add(JsonArray(g.map(::JsonPrimitive))) } }
		put("fade_in", fadeIn)
	}

	/** What the model exports: groups over the parts it has, each with two or more; null when none. */
	fun forExport(groups: List<List<String>>, fadeIn: Float, model: PuppetModel): io.github.psd2live.format.model.PoseIR? {
		val present = model.parts.mapTo(HashSet()) { it.id.raw }
		val kept = groups.map { g -> g.filter { it in present } }.filter { it.size >= 2 }
		return if (kept.isEmpty()) null else io.github.psd2live.format.model.PoseIR(fadeIn, kept.map { g -> g.map { io.github.psd2live.format.model.PoseEntry(it) } })
	}
}

/**
 * Layerloom patch: hit areas as the owner names them, each on one art mesh (Cubism's model3 HitAreas: what an app
 * hit-tests for taps and clicks). With none set, the generated Head and Body are used.
 */
object HitAreas {
	/** `hit_areas_put`: `{"areas": [{"name", "mesh"}]}` replaces them; blank names or meshes and repeated names are dropped. */
	fun put(request: JsonObject): List<Pair<String, String>> {
		val seen = HashSet<String>()
		return (request["areas"] as? JsonArray).orEmpty().mapNotNull { a ->
			val o = a as? JsonObject ?: return@mapNotNull null
			val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && seen.add(it.lowercase()) } ?: return@mapNotNull null
			val mesh = (o["mesh"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
			name to mesh
		}
	}

	fun request(areas: List<Pair<String, String>>): JsonObject = buildJsonObject {
		putJsonArray("areas") { areas.forEach { (n, m) -> add(buildJsonObject { put("name", n); put("mesh", m) }) } }
	}

	/** The authored areas over meshes the model has, or [generated] when none are set. */
	fun forExport(authored: List<Pair<String, String>>, generated: List<io.github.psd2live.format.model.HitAreaIR>, model: PuppetModel): List<io.github.psd2live.format.model.HitAreaIR> {
		if (authored.isEmpty()) return generated
		val meshes = model.drawables.mapTo(HashSet()) { it.id.raw }
		return authored.filter { it.second in meshes }.map { (name, mesh) -> io.github.psd2live.format.model.HitAreaIR("HitArea$name".filter { it.isLetterOrDigit() }, name, listOf(mesh)) }
	}
}

