package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The Studio's hand-off to the rigger, the web's `rigLayers` + `swingSettings` (rig.js) and the Mac's `rig.py`: the
 * PSD the rigger wants - each part named by what PSD2Live should make of it ([StudioRig.rigName]), with its part's id as
 * the layer id, only what belongs in the rig, the open mouth under the closed one - and the rig job beside it
 * (`NAME.layerloom.json`: glue, swing, turn depths, toggles and their expressions, arms, hair turn depth) that
 * PSD2Live's own `LayerloomJob` reads. So the iPad builds the rig the Mac builds from the same project.
 */
object StudioRigHandoff {
	class Entry(val part: StudioPart, var name: String, val layer: Layer)
	class Handoff(val psd: ByteArray, val job: JsonObject, val layers: List<String>, val skipped: List<String>)

	private fun JsonObject.obj(k: String) = this[k] as? JsonObject
	private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
	private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

	// PSD2Live swing presets (RigSwingEdits.kt SwingPresets.physics), so a hand-set length keeps the preset's feel.
	private val swingPhysics = mapOf(
		"hair" to mapOf("mobility" to 0.92, "delay" to 0.9, "acceleration" to 1.3, "output_scale" to 1.6),
		"accessory" to mapOf("mobility" to 0.96, "delay" to 0.6, "acceleration" to 1.8, "output_scale" to 1.8),
		"cloth" to mapOf("mobility" to 0.85, "delay" to 1.2, "acceleration" to 0.9, "output_scale" to 1.3),
	)

	/** Fit's swing as PSD2Live `swing_put` settings: pinned at an edge, the pivot moved in to where it hangs from. */
	fun swingSettings(swing: JsonObject?): JsonObject? {
		swing ?: return null
		val preset = swing.s("preset")?.takeIf { it in swingPhysics } ?: "hair"
		val physics = swing.num("length")?.takeIf { it > 0 }?.let { len -> JsonObject(mapOf("length" to JsonPrimitive(len)) + swingPhysics.getValue(preset).mapValues { JsonPrimitive(it.value) }) }
		fun motion(kind: String, magnitude: Double) = JsonObject(buildMap {
			put("kind", JsonPrimitive(kind)); put("magnitude", JsonPrimitive(magnitude))
			swing["softness"]?.let { put("softness", it) }; physics?.let { put("physics", it) }
		})
		val motions = listOfNotNull(swing.num("side")?.takeIf { it > 0 }?.let { motion("lateral", it) }, swing.num("updown")?.takeIf { it > 0 }?.let { motion("vertical", it) })
		if (motions.isEmpty()) return null
		val fulcrum = swing.s("edge")?.takeIf { it in listOf("left", "right", "bottom") } ?: "top"
		val tilt = (swing.num("tilt") ?: 0.0).coerceIn(-75.0, 75.0)
		return JsonObject(buildMap {
			put("preset", JsonPrimitive(preset)); put("fulcrum", JsonPrimitive(fulcrum)); put("offset_along", JsonPrimitive((swing.num("pivot") ?: 0.0).coerceIn(0.0, 0.95)))
			if (tilt != 0.0) put("tilt", JsonPrimitive(tilt)); put("motions", JsonArray(motions))
		})
	}

	/** The part's uuid as a PSD layer id (rig.py's `layer_id`: the uuid's number mod 2^31 - 2, plus 1), unique in [used]. */
	fun layerId(partId: String, used: MutableSet<Int>): Int {
		val hex = partId.replace("-", "")
		var n = if (hex.isNotEmpty() && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
			var m = 0L; for (c in hex) m = (m * 16 + c.digitToInt(16)) % 2147483646L; (m + 1).toInt()
		} else used.size + 1
		while (n in used) n++
		used += n; return n
	}

	fun build(project: StudioProject): Handoff {
		val w = project.width; val h = project.height
		val rendered = StudioRender.layers(project, includeHidden = true)
		val entries = mutableListOf<Entry>(); val skipped = mutableListOf<String>()
		for (part in project.parts) {
			val name = StudioRig.rigName(project, part)
			val layer = rendered.firstOrNull { it.partId == part.id }?.layer
			if (name == null || layer == null) { if (part.json.str("expressionRole") != null || part.visible) skipped += part.name; continue }
			entries += Entry(part, name, layer)
		}
		// Glue targets a layer by name, so glued, glue-target, swinging and toggled layers get a name no other layer has
		// ("headwear" twice becomes "headwear" + "headwear 2"; PSD2Live reads both the same way). A glue without a target
		// is from before glue could pick one: it meant the back hair.
		val backHair = entries.firstOrNull { it.name.startsWith("back hair") }
		fun anchor(p: StudioPart) = p.json.obj("hairAnchor")
		fun targetOf(p: StudioPart) = anchor(p)?.let { it.s("target") ?: backHair?.part?.id }
		val involved = entries.flatMap { e -> val j = e.part.json
			if (anchor(e.part) != null || j.obj("swing") != null || j.obj("toggle") != null || j["turnDepth"] is JsonPrimitive || j["turnDepthY"] is JsonPrimitive) listOfNotNull(e.part.id, targetOf(e.part)) else emptyList() }.toSet()
		val used = entries.map { it.name }.toMutableSet(); val seen = mutableSetOf<String>()
		for (e in entries) {
			if (e.name in seen && e.part.id in involved) {
				val stem = e.name.replace(Regex("\\s+\\d+$"), ""); var n = 2
				while ("$stem $n" in used) n++
				e.name = "$stem $n"; used += e.name
			}
			seen += e.name
		}
		val nameOf = entries.associate { it.part.id to it.name }
		// PSD2Live wants the open mouth under the closed one (it squeezes the open one shut and fades the closed in).
		val open = entries.indexOfFirst { it.name == "mouth" }; val closed = entries.indexOfFirst { it.name == "mouth close" }
		if (open > closed && closed >= 0) entries.add(closed, entries.removeAt(open))

		val ids = mutableSetOf<Int>()
		val merged = IntArray(w * h) { -1 }
		for (e in entries) StudioRender.over(merged, w, e.layer)
		val psd = StudioPsd.write(w, h, entries.map { StudioPsd.PsdLayer(it.name, it.layer, false, layerId(it.part.id, ids)) }, merged)

		// The rig job (rig.py's rig_job), keyed by those layer names.
		val glues = mutableMapOf<String, JsonElement>(); val swings = mutableMapOf<String, JsonElement>()
		val depths = mutableMapOf<String, JsonElement>(); val depthsY = mutableMapOf<String, JsonElement>()
		val toggles = mutableMapOf<String, JsonElement>(); val expressions = mutableListOf<JsonElement>()
		for (e in entries) {
			val j = e.part.json
			anchor(e.part)?.let { a ->
				val target = targetOf(e.part)?.let(nameOf::get)
				val from = a.num("from"); val to = a.num("to")
				if (target != null && target != e.name && from != null && to != null) glues[e.name] = JsonObject(buildMap {
					put("target", JsonPrimitive(target.take(80))); put("from", JsonPrimitive(from.coerceIn(0.0, 1.0))); put("to", JsonPrimitive(to.coerceIn(0.0, 1.0)))
					put("glueTop", JsonPrimitive((a["pinTop"] as? JsonPrimitive)?.booleanOrNull != false)); if (a.s("axis") == "x") put("axis", JsonPrimitive("x"))
				})
			}
			swingSettings(j.obj("swing"))?.let { swings[e.name] = it }
			j.num("turnDepth")?.let { depths[e.name] = JsonPrimitive(it.coerceIn(0.0, 3.0)) }
			j.num("turnDepthY")?.let { depthsY[e.name] = JsonPrimitive(it.coerceIn(0.0, 3.0)) }
			j.obj("toggle")?.let { t ->
				val param = t.s("param")?.filter { it.isLetterOrDigit() || it == '_' }?.take(64)?.takeIf { it.isNotEmpty() } ?: return@let
				toggles[e.name] = JsonPrimitive(param)
				t.s("expression")?.trim()?.takeIf { it.isNotEmpty() }?.let { ex ->
					val extra = (t["expressionParams"] as? JsonArray)?.take(12)?.filterIsInstance<JsonObject>()?.filter { p ->
						p.s("Id")?.matches(Regex("[A-Za-z0-9_]{1,64}")) == true && p.num("Value") != null && p.s("Blend") in listOf("Add", "Multiply", "Overwrite")
					}.orEmpty()
					expressions += JsonObject(mapOf("name" to JsonPrimitive(ex.take(40)), "fadeIn" to JsonPrimitive(0.4), "fadeOut" to JsonPrimitive(0.4),
						"parameters" to JsonArray(listOf(JsonObject(mapOf("Id" to JsonPrimitive(param), "Value" to JsonPrimitive(1), "Blend" to JsonPrimitive("Add")))) + extra)))
				}
			}
		}
		val pj = project.json
		val job = JsonObject(buildMap {
			if (glues.isNotEmpty()) put("glues", JsonObject(glues)); if (swings.isNotEmpty()) put("swings", JsonObject(swings))
			if (depths.isNotEmpty()) put("turnDepths", JsonObject(depths)); if (depthsY.isNotEmpty()) put("turnDepthsY", JsonObject(depthsY))
			if (toggles.isNotEmpty()) put("toggles", JsonObject(toggles)); if (expressions.isNotEmpty()) put("expressions", JsonArray(expressions))
			for (k in listOf("hairTurnDepth", "hairTurnDepthY")) pj.num(k)?.takeIf { it > 0 }?.let { put(k, JsonPrimitive(it.coerceAtMost(3.0))) }
			if ((pj["rigArms"] as? JsonPrimitive)?.booleanOrNull == true) {
				put("skeleton", JsonPrimitive("arms"))
				pj.obj("armJoints")?.let { joints -> put("armJoints", JsonObject(joints.filterKeys { it == "L" || it == "R" }.mapValues { (_, side) ->
					JsonObject(((side as? JsonObject) ?: JsonObject(emptyMap())).filterKeys { it in listOf("shoulder", "elbow", "wrist", "tip") }) })) }
			}
		})
		return Handoff(psd, job, entries.map { it.name }, skipped)
	}
}
