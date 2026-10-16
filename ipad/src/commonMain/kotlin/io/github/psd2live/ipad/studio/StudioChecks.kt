package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The studio's checks, the web studio's `motion.js`, `inspection.js`, `preflight.js`, `keyforms.js` and the hand-off
 * checklist: descriptive pixel and structure checks, nothing that changes the art by itself.
 */
object StudioChecks {
	// ---- Movement coverage test (motion.js) ----

	class Motion(val moved: IntArray, val gaps: Int, val gapMask: String?)

	/**
	 * The project with part [partId] (shown, with its hidden art) moved by ([dx], [dy]): the newly exposed holes - alpha
	 * that was ≥240 and is now under 16 - are counted, tinted orange when [highlight], and given back as a mask.
	 */
	fun motion(project: StudioProject, partId: String, dx: Int, dy: Int, highlight: Boolean): Motion {
		val w = project.width; val h = project.height
		val shown = project.withParts(project.parts.map { if (it.id == partId) it.with(mapOf("visible" to JsonPrimitive(true))) else it })
		val before = IntArray(w * h); val after = IntArray(w * h)
		for (l in StudioRender.layers(shown)) {
			StudioRender.over(before, w, l.layer)
			if (l.partId == partId) overShifted(after, w, h, l.layer, dx, dy) else StudioRender.over(after, w, l.layer)
		}
		var gaps = 0
		val mask = IntArray(w * h)
		for (i in before.indices) if ((before[i] ushr 24) >= 240 && (after[i] ushr 24) < 16) {
			gaps++; mask[i] = MASK_ARGB
			if (highlight) after[i] = 0xFFFF8B40.toInt()
		}
		return Motion(after, gaps, if (gaps > 0) pngDataUrl(encodePng(w, h, mask)) else null)
	}

	private fun overShifted(dst: IntArray, w: Int, h: Int, l: Layer, dx: Int, dy: Int) {
		for (row in 0 until l.height) {
			val y = l.y + row + dy; if (y !in 0 until h) continue
			for (col in 0 until l.width) {
				val x = l.x + col + dx; if (x !in 0 until w) continue
				dst[y * w + x] = StudioRender.blend(dst[y * w + x], l.argb[row * l.width + col])
			}
		}
	}

	// ---- Component inspection (inspection.js) ----

	class Inspection(val visible: Int, val opaque: Int, val translucent: Int, val transparent: Int, val bounds: IntArray?, val edges: List<String>, val warnings: List<String>)

	fun inspect(argb: IntArray, w: Int, h: Int): Inspection {
		var visible = 0; var opaque = 0; var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
		val edge = IntArray(4)
		for (y in 0 until h) for (x in 0 until w) {
			val a = argb[y * w + x] ushr 24
			if (a == 0) continue
			visible++; if (a == 255) opaque++
			if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y
			if (a >= 16) { if (x == 0) edge[0]++; if (x == w - 1) edge[1]++; if (y == 0) edge[2]++; if (y == h - 1) edge[3]++ }
		}
		val touching = listOf("left", "right", "top", "bottom").filterIndexed { i, _ -> edge[i] > 0 }
		val warnings = buildList {
			if (visible == 0) add("The part's art is empty.")
			if (opaque == w * h) add("Fully opaque over the whole canvas: check for a baked-in background.")
			if (touching.isNotEmpty()) add("The art touches the canvas edge (${touching.joinToString()}). Check the cropping and room to move; it may be intended.")
		}
		return Inspection(visible, opaque, visible - opaque, w * h - visible, if (visible > 0) intArrayOf(x0, y0, x1 - x0 + 1, y1 - y0 + 1) else null, touching, warnings)
	}

	/** The part's art alone on [background] (null: transparent, or a colour), for the canvas. */
	fun isolated(project: StudioProject, partId: String, background: Int?): IntArray? {
		val art = StudioPeel.partArt(project, partId) ?: return null
		if (background == null) return art
		val out = IntArray(art.size) { background }
		for (i in art.indices) out[i] = StudioRender.blend(out[i], art[i])
		return out
	}

	// ---- Keyform reference plan (keyforms.js) ----

	class Param(val id: String, val name: String, val min: Double, val max: Double, val default: Double)
	val keyformParameters = listOf(
		Param("ParamEyeLOpen", "Left eye open", 0.0, 1.0, 1.0), Param("ParamEyeROpen", "Right eye open", 0.0, 1.0, 1.0),
		Param("ParamMouthOpenY", "Mouth open", 0.0, 1.0, 0.0), Param("ParamMouthForm", "Mouth form", -1.0, 1.0, 0.0),
	)

	/** A part's keyform plan issues (missing endpoint references, values used twice), or null without a plan. */
	fun keyformIssues(part: StudioPart): List<String>? {
		val plan = part.json["keyformPlan"] as? JsonObject ?: return null
		val param = keyformParameters.firstOrNull { it.id == plan.str("parameterId") } ?: throw IllegalStateException("Invalid keyform plan for ${part.name}.")
		val neutral = (plan["neutralValue"] as? JsonPrimitive)?.doubleOrNull ?: throw IllegalStateException("Invalid keyform plan for ${part.name}.")
		val values = listOf(neutral) + ((plan["keys"] as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("value") as? JsonPrimitive)?.doubleOrNull } ?: emptyList())
		return buildList {
			for (end in listOf(param.min, param.max)) if (end !in values) add("Missing reference at ${param.id} = ${fmt(end)}.")
			if (values.toSet().size != values.size) add("Multiple references use the same value; choose the intended artwork before authoring.")
		}
	}

	private fun fmt(v: Double) = if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()

	// ---- Preflight (preflight.js) ----

	class Issue(val code: String, val blocker: Boolean, val message: String, val partId: String?)

	private val coreParts = setOf("face skin", "left eye", "right eye", "mouth")
	fun requiredForRig(part: StudioPart) = (part.json["requiredForRig"] as? JsonPrimitive)?.booleanOrNull ?: (part.name.trim().lowercase() in coreParts)

	/** The structural pre-hand-off check: blockers and things to review. Not a mesh or rig check. */
	fun preflight(project: StudioProject): List<Issue> {
		val out = mutableListOf<Issue>()
		fun add(code: String, blocker: Boolean, message: String, partId: String? = null) { out += Issue(code, blocker, message, partId) }
		val parts = project.parts
		val ids = parts.map { it.id }.toSet()
		fun hasArt(p: StudioPart) = p.cutout != null || parts.any { o -> o.repairs.any { it.str("targetPartId") == p.id } }
		if (project.width <= 0 || project.height <= 0 || project.width.toLong() * project.height > 16_000_000L) add("dimensions", true, "Canvas dimensions must be valid and under 16 megapixels.")
		if (ids.size != parts.size) add("duplicate-id", true, "Component IDs are duplicated; restore a valid project before exporting.")
		val names = mutableSetOf<String>()
		for ((index, part) in parts.withIndex()) {
			val name = part.name.trim().lowercase()
			if (name.isEmpty() || name in names) add("layer-name", true, "Give ${part.name.ifEmpty { "this component" }} a unique, non-empty layer name.", part.id)
			names += name
			try { keyformIssues(part)?.forEach { add("keyform-reference", false, "${part.name}: $it", part.id) } }
			catch (e: IllegalStateException) { add("invalid-keyform-plan", true, e.message ?: "Invalid keyform plan.", part.id) }
			if (requiredForRig(part) && !hasArt(part)) add("missing-art", true, "Required component ${part.name} has no artwork yet.", part.id)
			if (part.json["candidate"] is JsonObject) add("pending-candidate", false, "Review or reject the pending candidate for ${part.name}. Draft candidates are not included in exports.", part.id)
			if (!part.visible && hasArt(part)) add("hidden-layer", false, "${part.name} is hidden in the PSD. Check visibility when importing into Cubism.", part.id)
			for (repair in part.repairs) {
				val target = repair.str("targetPartId")
				when {
					target == null -> add("detached-repair", false, "Assign ${part.name}'s hidden art to its underlying component, or place its separate patch manually.", part.id)
					target !in ids -> add("missing-target", true, "${part.name}'s hidden-art target no longer exists.", part.id)
					parts.indexOfFirst { it.id == target } >= index -> add("occlusion-order", true, "Place the hidden-art target behind ${part.name}.", part.id)
				}
			}
			val variants = (part.json["variants"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
			if (variants.isNotEmpty()) add("expression-review", false, "Review ${part.name}'s saved variants against its current neutral artwork, especially after cleanup or hidden-art changes.", part.id)
			val labels = mutableSetOf<String>()
			for (v in variants) {
				val label = v.str("name")?.trim()?.lowercase() ?: ""
				if (label.isEmpty() || label in labels) add("variant-name", false, "${part.name} has duplicate or empty expression labels.", part.id)
				labels += label
				if ((v["scaled"] as? JsonPrimitive)?.booleanOrNull == true) add("aligned-variant", false, "${part.name} / ${v.str("name")} was resized from the provider output. Inspect alignment and edges.", part.id)
			}
		}
		if (parts.none { hasArt(it) }) add("no-layers", true, "Separate at least one real component before a Live2D handoff.")
		val checks = project.json["handoffChecks"] as? JsonObject
		fun checked(k: String) = (checks?.get(k) as? JsonPrimitive)?.booleanOrNull == true
		if (!checked("separation")) add("remaining-art", false, "Inspect Remaining artwork: it may still contain unseparated moving parts and background pixels.")
		if (!checked("hiddenArt")) add("motion-coverage", false, "Review concealed artwork across the intended movement range; structural checks cannot prove coverage.")
		if (!checked("edges")) add("edge-review", false, "Check transparent edges, overlap and stray pixels.")
		if (!checked("cubism")) add("cubism-validation", false, "Open the actual PSD in Cubism and verify it. No Cubism import has been certified by this tool.")
		return out
	}

	/** The hand-off checklist (the web's five `handoffChecks`). */
	val handoff = listOf(
		"separation" to "Required moving parts are separated",
		"hiddenArt" to "Hidden art covers the intended movement",
		"features" to "Eye and mouth components are independently editable",
		"edges" to "Edges, overlap and transparency are cleaned up",
		"cubism" to "PSD has been checked in Live2D Cubism",
	)

	fun handoffChecked(project: StudioProject, key: String) = ((project.json["handoffChecks"] as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull == true

	fun setHandoff(project: StudioProject, key: String, on: Boolean): StudioProject {
		val now = (project.json["handoffChecks"] as? JsonObject)?.toMutableMap() ?: mutableMapOf<String, JsonElement>()
		now[key] = JsonPrimitive(on)
		return project.with(mapOf("handoffChecks" to JsonObject(now)), "${if (on) "Checked" else "Unchecked"}: ${handoff.first { it.first == key }.second}")
	}

}
