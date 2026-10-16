package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/**
 * The whole project through the upscaler (the web's `upscaleProject` / `cleanUpParts`, upscale.js): every full-canvas
 * PNG anywhere in the project - parts, masks, repair patches, variants, anything newer - found by walking the JSON,
 * so nothing is missed by name. Art goes through the anime model; masks only resample. The 2× result is a new copy
 * (new id and name) and the 1× project stays as "previous".
 */
object StudioProjectUpscale {
	/** Art at [scale]: the anime model (a test puts a stand-in here). */
	var art: (argb: IntArray, w: Int, h: Int, scale: Double) -> IntArray = { a, w, h, s -> StudioUpscale.upscale(a, w, h, s) }

	private const val PNG = "data:image/png;base64,"
	private fun isMaskKey(key: String) = key.contains("mask", ignoreCase = true)

	private fun walk(value: JsonElement, key: String, found: MutableMap<String, Boolean>) {
		when (value) {
			is JsonPrimitive -> value.contentOrNull?.takeIf { value.isString && it.startsWith(PNG) }?.let { found[(if (isMaskKey(key)) "m" else "a") + it] = isMaskKey(key) }
			is JsonArray -> value.forEach { walk(it, key, found) }
			is JsonObject -> for ((k, v) in value) if (k != "history") walk(v, k, found)
		}
	}

	private fun map(value: JsonElement, key: String, done: Map<String, String>): JsonElement = when (value) {
		is JsonPrimitive -> value.contentOrNull?.takeIf { value.isString && it.startsWith(PNG) }?.let { done[(if (isMaskKey(key)) "m" else "a") + it]?.let(::JsonPrimitive) } ?: value
		is JsonArray -> JsonArray(value.map { map(it, key, done) })
		is JsonObject -> JsonObject(value.mapValues { (k, v) -> if (k == "history") v else map(v, k, done) })
	}

	class Result(val project: StudioProject, val skipped: Int)

	/** The project 2× as a new copy; [progress] hears images done of total. Blocking (run it off the main thread). */
	fun upscaleProject(project: StudioProject, progress: (Int, Int) -> Unit = { _, _ -> }): Result {
		val w = project.width; val h = project.height
		val found = linkedMapOf<String, Boolean>()
		walk(project.json, "", found)
		val done = HashMap<String, String>(); var skipped = 0; var i = 0
		for ((key, mask) in found) {
			progress(i++, found.size)
			val px = decodeImage(dataUrlBytes(key.substring(1)))
			if (px == null || px.width != w || px.height != h) { skipped++; continue }
			val out = if (mask) StudioAI.resample(px.argb, w, h, 0, 0, w, h, w * 2, h * 2) else art(px.argb, w, h, 2.0)
			done[key] = pngDataUrl(encodePng(w * 2, h * 2, out))
		}
		progress(found.size, found.size)
		val json = (map(project.json, "", done) as JsonObject).toMutableMap()
		json["width"] = JsonPrimitive(w * 2); json["height"] = JsonPrimitive(h * 2)
		// Pixel positions saved on the project (fractions, like glue lines and swing pivots, need nothing).
		(project.json["armJoints"] as? JsonObject)?.let { arms ->
			json["armJoints"] = JsonObject(arms.mapValues { (_, joints) -> (joints as? JsonObject)?.let { j -> JsonObject(j.mapValues { (_, p) ->
				(p as? JsonArray)?.let { a -> JsonArray(a.map { v -> (v as? JsonPrimitive)?.doubleOrNull?.let { JsonPrimitive((it * 2).roundToInt()) } ?: v }) } ?: p }) } ?: joints })
		}
		val now = nowIso()
		json["id"] = JsonPrimitive(uuid()); json["name"] = JsonPrimitive("${project.name} (2×)"); json["updatedAt"] = JsonPrimitive(now)
		json["handoffChecks"] = JsonObject(emptyMap())
		val event = JsonObject(mapOf("at" to JsonPrimitive(now), "event" to JsonPrimitive("Upscaled 2× from ${w}×${h}")))
		json["history"] = JsonArray(((project.json["history"] as? JsonArray) ?: JsonArray(emptyList())).plus(event).takeLast(250))
		return Result(StudioProject(JsonObject(json)), skipped)
	}

	/** Every piece's art cleaned up at the same size (the drawing and masks are left alone); the old art kept as fitOriginal. */
	fun cleanUpParts(project: StudioProject, progress: (Int, Int) -> Unit = { _, _ -> }): StudioProject {
		val w = project.width; val h = project.height
		val jobs = project.parts.flatMap { p -> listOf("cutout", "trimBase").mapNotNull { k -> p.json.str(k)?.takeIf { it.startsWith(PNG) }?.let { Triple(p.id, k, it) } } }
		val done = HashMap<Pair<String, String>, String>()
		for ((i, job) in jobs.withIndex()) {
			progress(i, jobs.size)
			val px = decodeImage(dataUrlBytes(job.third)) ?: continue
			if (px.width != w || px.height != h) continue
			done[job.first to job.second] = pngDataUrl(encodePng(w, h, art(px.argb, w, h, 1.0)))
		}
		progress(jobs.size, jobs.size)
		return project.withParts(project.parts.map { p ->
			val changes = mutableMapOf<String, JsonElement?>()
			done[p.id to "cutout"]?.let { changes["cutout"] = JsonPrimitive(it); changes["fitOriginal"] = p.json["fitOriginal"]?.takeIf { f -> f is JsonPrimitive && f.isString } ?: JsonPrimitive(p.json.str("trimBase") ?: p.cutout!!) }
			done[p.id to "trimBase"]?.let { changes["trimBase"] = JsonPrimitive(it) }
			if (changes.isEmpty()) p else p.with(changes)
		}, "Cleaned up every piece")
	}
}
