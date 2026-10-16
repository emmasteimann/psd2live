package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The face and expression tools, the web studio's `OverlayPanel.jsx`, `eyes.js`, `expressions.js`, `keyforms.js`
 * and `underlying.js` (the on-device parts; generating art with the AI image edit waits for sign-in).
 */
object StudioFace {
	// ---- Face overlays: shadow (gloom) and blush ----

	class Overlay(val name: String, val rig: String, val param: String, val expression: String)
	val shadow = Overlay("Face shadow", "face detail", "ParamFaceShadow", "Gloom")
	val blush = Overlay("Blush", "blush", "ParamBlush", "Blush")
	private val facey = Regex("^(face|eyewhite|irides|eyelash|eyebrow|eye close|mouth|nose|ears)")

	fun overlayPart(project: StudioProject, o: Overlay): StudioPart? =
		project.parts.firstOrNull { ((it.json["toggle"] as? JsonObject)?.get("param") as? JsonPrimitive)?.content == o.param }

	private fun alpha(project: StudioProject, url: String?): IntArray {
		val w = project.width; val h = project.height; val out = IntArray(w * h)
		val l = StudioImages.layer(url ?: return out, w, h)
		for (r in 0 until l.height) for (c in 0 until l.width) out[(l.y + r) * w + l.x + c] = l.argb[r * l.width + c] ushr 24
		return out
	}

	private fun rows(a: IntArray, w: Int, h: Int): IntArray {
		var top = h; var bottom = -1
		for (y in 0 until h) { val b = y * w; for (x in 0 until w) if (a[b + x] > 128) { if (y < top) top = y; bottom = y; break } }
		return intArrayOf(top, bottom)
	}

	private fun box(a: IntArray, w: Int, h: Int): DoubleArray? {
		var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
		var y = 0; while (y < h) { var x = 0; while (x < w) { if (a[y * w + x] > 128) { x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) }; x += 2 }; y += 2 }
		return if (x1 < 0) null else doubleArrayOf(x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble())
	}

	private fun face(project: StudioProject) = project.parts.firstOrNull { StudioRig.rigName(project, it) == "face" && it.cutout != null }
		?: throw IllegalStateException("There's no face piece (a part that rigs as \"face\", e.g. named Face) to work from.")

	/** Draws an antialiased round-capped line into [out] (straight ARGB) in [rgb] at [a] (0..1): source-over, or atop. */
	private fun line(out: IntArray, w: Int, h: Int, x0: Double, y0: Double, x1: Double, y1: Double, width: Double, rgb: Int, a: Double, atop: Boolean) {
		val r = width / 2
		val bx0 = max(0, floor(min(x0, x1) - r - 1).toInt()); val bx1 = min(w - 1, (max(x0, x1) + r + 1).toInt())
		val by0 = max(0, floor(min(y0, y1) - r - 1).toInt()); val by1 = min(h - 1, (max(y0, y1) + r + 1).toInt())
		val vx = x1 - x0; val vy = y1 - y0; val len2 = vx * vx + vy * vy
		for (y in by0..by1) for (x in bx0..bx1) {
			val px = x + 0.5 - x0; val py = y + 0.5 - y0
			val t = if (len2 == 0.0) 0.0 else ((px * vx + py * vy) / len2).coerceIn(0.0, 1.0)
			val cov = (r - hypot(px - t * vx, py - t * vy) + 0.5).coerceIn(0.0, 1.0)
			if (cov <= 0) continue
			val i = y * w + x
			if (atop) {
				val d = out[i]; val da = d ushr 24; if (da == 0) continue
				val k = a * cov
				val nr = (((d ushr 16) and 0xFF) * (1 - k) + ((rgb ushr 16) and 0xFF) * k).toInt(); val ng = (((d ushr 8) and 0xFF) * (1 - k) + ((rgb ushr 8) and 0xFF) * k).toInt()
				val nb = ((d and 0xFF) * (1 - k) + (rgb and 0xFF) * k).toInt()
				out[i] = (da shl 24) or (nr shl 16) or (ng shl 8) or nb
			} else out[i] = StudioRender.blend(out[i], ((255 * a * cov).roundToInt() shl 24) or (rgb and 0xFFFFFF))
		}
	}

	private fun dim(rgb: Int, kr: Double, kg: Double, kb: Double) =
		(((((rgb ushr 16) and 0xFF) * kr).toInt()) shl 16) or (((((rgb ushr 8) and 0xFF) * kg).toInt()) shl 8) or ((rgb and 0xFF) * kb).toInt()

	/** The shadow: the face's outline, full strength down to the top of the eyes, fading a little below them. */
	fun makeShadow(project: StudioProject, rgb: Int, strength: Double, reach: Double, lines: Boolean): IntArray {
		val w = project.width; val h = project.height
		val a = alpha(project, face(project).cutout)
		val (y0, y1) = rows(a, w, h).let { it[0] to it[1] }
		val fh = max(1, y1 - y0).toDouble()
		var top = y0 + 0.5 * fh; var bottom = y0 + 0.7 * fh
		val eyes = project.parts.filter { Regex("^(eyewhite|eyelash)").containsMatchIn(StudioRig.rigName(project, it) ?: "") && it.cutout != null }
		if (eyes.isNotEmpty()) {
			var t = h; var b = -1
			for (e in eyes) { val r = rows(alpha(project, e.cutout), w, h); if (r[1] >= 0) { t = min(t, r[0]); b = max(b, r[1]) } }
			if (b >= 0) { top = t.toDouble(); bottom = b.toDouble() }
		}
		val full = top + reach * fh; val end = max(full + 1, bottom + 0.08 * fh + reach * fh)
		val out = IntArray(w * h)
		for (y in 0 until h) {
			var t = ((end - y) / (end - full)).coerceIn(0.0, 1.0); t = t * t * (3 - 2 * t)
			if (t <= 0) continue
			for (x in 0 until w) { val fa = a[y * w + x]; if (fa == 0) continue; out[y * w + x] = ((255 * (fa / 255.0) * t * strength).roundToInt() shl 24) or (rgb and 0xFFFFFF) }
		}
		if (lines) {
			var fx0 = w; var fx1 = -1
			var y = y0; while (y <= y1) { for (x in 0 until w) if (a[y * w + x] > 128) { fx0 = min(fx0, x); fx1 = max(fx1, x) }; y += 4 }
			val n = 9; val step = (fx1 - fx0) / (n + 1.0)
			for (k in 1..n) {
				val lx = fx0 + k * step; val len = (full - y0) * (0.55 + 0.45 * abs(sin(k * 2.3)))
				line(out, w, h, lx, y0.toDouble(), lx, y0 + len, max(2.0, w / 400.0), dim(rgb, 0.6, 0.6, 0.6), min(1.0, strength + 0.1), atop = true)
			}
		}
		return out
	}

	/** Blush: a soft oval on each cheek (below each eye, nudged outward), kept on the face, optional blush lines. */
	fun makeBlush(project: StudioProject, rgb: Int, strength: Double, size: Double, lines: Boolean): IntArray {
		val w = project.width; val h = project.height
		val a = alpha(project, face(project).cutout)
		val fb = box(a, w, h) ?: throw IllegalStateException("The face piece is empty.")
		val fcx = (fb[0] + fb[2]) / 2; val fw = fb[2] - fb[0]; val fh = fb[3] - fb[1]
		val eyeBoxes = mutableListOf<DoubleArray>()
		for (kind in listOf("eyewhite", "eyelash")) {
			for (p in project.parts.filter { (StudioRig.rigName(project, it) ?: "").startsWith(kind) && it.cutout != null }) box(alpha(project, p.cutout), w, h)?.let { eyeBoxes += it }
			if (eyeBoxes.size >= 2) break
		}
		val eyes = if (eyeBoxes.size >= 2) eyeBoxes.take(2) else listOf(
			doubleArrayOf(fb[0] + fw * 0.18, fb[1] + fh * 0.5, fb[0] + fw * 0.42, fb[1] + fh * 0.62),
			doubleArrayOf(fb[0] + fw * 0.58, fb[1] + fh * 0.5, fb[0] + fw * 0.82, fb[1] + fh * 0.62))
		val out = IntArray(w * h)
		for (e in eyes) {
			val ew = e[2] - e[0]; val cx0 = (e[0] + e[2]) / 2; val outward = if (cx0 >= fcx) 1 else -1
			val cx = cx0 + outward * ew * 0.12; val cy = e[3] + (e[3] - e[1]) * 0.55; val rx = ew * 0.55 * size; val ry = ew * 0.28 * size
			for (y in max(0, (cy - ry).toInt())..min(h - 1, (cy + ry).toInt() + 1)) for (x in max(0, (cx - rx).toInt())..min(w - 1, (cx + rx).toInt() + 1)) {
				val d = sqrt(((x + 0.5 - cx) / rx).let { it * it } + ((y + 0.5 - cy) / ry).let { it * it })
				if (d >= 1) continue
				val k = if (d < 0.55) 1 - 0.3 * d / 0.55 else 0.7 * (1 - d) / 0.45
				out[y * w + x] = StudioRender.blend(out[y * w + x], ((255 * strength * k).roundToInt() shl 24) or (rgb and 0xFFFFFF))
			}
			if (lines) for (i in -1..1) {
				val lx = cx + i * rx * 0.38
				line(out, w, h, lx + rx * 0.12, cy - ry * 0.5, lx - rx * 0.12, cy + ry * 0.5, max(1.5, rx / 14), dim(rgb, 0.75, 0.55, 0.6), min(1.0, strength + 0.25), atop = false)
			}
		}
		for (i in out.indices) { val fa = a[i]; if (fa < 255) { val p = out[i]; out[i] = ((((p ushr 24) * fa) / 255) shl 24) or (p and 0xFFFFFF) } }
		return out
	}

	/** Puts the overlay part right above the topmost face piece (the shadow above the blush), replacing an older one. */
	fun placeOverlay(project: StudioProject, o: Overlay, art: IntArray, settings: Map<String, JsonElement>, blend: String?, expressionParams: List<JsonObject>): StudioProject {
		val existing = overlayPart(project, o)
		val url = pngDataUrl(encodePng(project.width, project.height, art))
		val part = StudioPart(JsonObject(mapOf(
			"id" to JsonPrimitive(existing?.id ?: uuid()), "name" to JsonPrimitive(o.name), "group" to JsonPrimitive("Face"), "visible" to JsonPrimitive(true),
			"mask" to JsonNull, "repairs" to JsonArray(emptyList()), "cutout" to JsonPrimitive(url),
			// An overlay, not a piece cut from the drawing: no extraction mask, or it would punch a hole in the drawing.
			"extractionMask" to JsonNull, "imported" to JsonPrimitive(true), "seeThroughTag" to JsonPrimitive(o.rig),
			"toggle" to JsonObject(buildMap {
				put("param", JsonPrimitive(o.param)); if (blend != null) put("blend", JsonPrimitive(blend)); put("expression", JsonPrimitive(o.expression))
				if (expressionParams.isNotEmpty()) put("expressionParams", JsonArray(expressionParams))
			}),
			"overlaySettings" to JsonObject(settings),
		)))
		val rest = project.parts.filter { it.id != part.id }
		var at = -1
		rest.forEachIndexed { i, p ->
			val n = StudioRig.rigName(project, p) ?: ""
			if (facey.containsMatchIn(n) || (o === shadow && ((p.json["toggle"] as? JsonObject)?.get("param") as? JsonPrimitive)?.content == blush.param)) at = i + 1
		}
		if (at < 0) { at = rest.indexOfFirst { (StudioRig.rigName(project, it) ?: "").startsWith("front hair") }; if (at < 0) at = rest.size }
		return project.withParts(rest.subList(0, at) + part + rest.subList(at, rest.size), if (existing != null) "Remade the ${o.name.lowercase()}" else "Made a ${o.name.lowercase()}", part.id)
	}

	fun gloomParams(halfEyes: Boolean, eyeOpen: Double, lowBrows: Boolean, flatMouth: Boolean): List<JsonObject> = buildList {
		fun p(id: String, v: Double, blend: String) = JsonObject(mapOf("Id" to JsonPrimitive(id), "Value" to JsonPrimitive(v), "Blend" to JsonPrimitive(blend)))
		if (halfEyes) { add(p("ParamEyeLOpen", eyeOpen, "Multiply")); add(p("ParamEyeROpen", eyeOpen, "Multiply")) }
		if (lowBrows) { add(p("ParamBrowLY", -0.4, "Add")); add(p("ParamBrowRY", -0.4, "Add")) }
		if (flatMouth) add(p("ParamMouthForm", -0.6, "Add"))
	}

	// ---- Eye assembly (eyes.js) ----

	val eyeRoles = listOf(Triple("sclera", "Sclera", true), Triple("iris", "Iris", true), Triple("upperLid", "Upper Lid", true), Triple("lowerLid", "Lower Lid", false),
		Triple("pupil", "Pupil", false), Triple("lashes", "Lashes", false), Triple("highlight", "Highlight", false))

	/** Adds the eye slot parts and an eye plan assigning them (the web's `planEyeSlots`). */
	fun planEyeSlots(project: StudioProject): StudioProject {
		val withSlots = StudioEdits.addComponents(project, "Eyes")
		val sides = JsonObject(listOf("left", "right").associateWith { side ->
			val label = if (side == "left") "Left" else "Right"
			JsonObject(mapOf("roles" to JsonObject(eyeRoles.associate { (role, name, required) ->
				role to JsonObject(mapOf("partId" to (withSlots.parts.firstOrNull { it.name.trim().lowercase() == "$label $name".lowercase() }?.id?.let { JsonPrimitive(it) } ?: JsonNull),
					"required" to JsonPrimitive(required)))
			})))
		})
		return withSlots.with(mapOf("eyePlan" to JsonObject(mapOf("version" to JsonPrimitive(1), "sides" to sides)), "handoffChecks" to JsonObject(emptyMap())), "Planned eye component slots")
	}

	class Role(val key: String, val label: String, val partId: String?, val required: Boolean, val hasArt: Boolean)

	fun eyeRolesOf(project: StudioProject, side: String): List<Role>? {
		val roles = ((((project.json["eyePlan"] as? JsonObject)?.get("sides") as? JsonObject)?.get(side) as? JsonObject)?.get("roles") as? JsonObject) ?: return null
		fun hasArt(p: StudioPart?) = p != null && (p.cutout != null || project.parts.any { o -> o.repairs.any { it.str("targetPartId") == p.id } })
		return eyeRoles.map { (key, label, req) ->
			val r = roles[key] as? JsonObject
			val id = r?.str("partId")
			Role(key, label, id, (r?.get("required") as? JsonPrimitive)?.booleanOrNull ?: req, hasArt(project.part(id)))
		}
	}

	fun setEyeRole(project: StudioProject, side: String, role: String, partId: String?, required: Boolean? = null): StudioProject {
		val plan = project.json["eyePlan"] as? JsonObject ?: return project
		val sides = (plan["sides"] as JsonObject).toMutableMap()
		val s = (sides[side] as JsonObject); val roles = (s["roles"] as JsonObject).toMutableMap()
		val r = (roles[role] as JsonObject).toMutableMap()
		if (required == null) r["partId"] = partId?.let { JsonPrimitive(it) } ?: JsonNull else r["required"] = JsonPrimitive(required)
		roles[role] = JsonObject(r); sides[side] = JsonObject(mapOf("roles" to JsonObject(roles)))
		return project.with(mapOf("eyePlan" to JsonObject(plan + ("sides" to JsonObject(sides))), "handoffChecks" to JsonObject(emptyMap())), "Edited $side eye $role assignment")
	}

	/** The eye's assigned parts drawn together (whole canvas), and the box around them - to inspect the assembly. */
	fun assembledEye(project: StudioProject, side: String): Pair<IntArray, IntArray>? {
		val ids = eyeRolesOf(project, side)?.mapNotNull { it.partId }?.toSet() ?: return null
		val out = IntArray(project.width * project.height)
		for (l in StudioRender.layers(project, includeHidden = true)) if (l.partId in ids) StudioRender.over(out, project.width, l.layer)
		val b = FitGeometry.alphaBox(out, project.width, project.height) ?: return null
		return out to intArrayOf(b.x0.toInt(), b.y0.toInt(), b.x1.toInt(), b.y1.toInt())
	}

	// ---- Expression variants and keyform plans (expressions.js, keyforms.js) ----

	class Variant(val id: String, val name: String, val art: String)

	fun variants(part: StudioPart): List<Variant> = (part.json["variants"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { v -> Variant(v.str("id") ?: "", v.str("name") ?: "", v.str("art") ?: "") } } ?: emptyList()

	/** Adds an artist's PNG (full canvas, transparent, not empty) as an approved expression variant of the part. */
	fun importVariant(project: StudioProject, partId: String, png: ByteArray, name: String): StudioProject {
		val px = decodeImage(png) ?: throw IllegalStateException("That isn't an image.")
		if (px.width != project.width || px.height != project.height) throw IllegalStateException("The PNG must be ${project.width} × ${project.height} (the whole canvas). It won't be resized.")
		if (px.argb.none { (it ushr 24) != 0 }) throw IllegalStateException("This PNG is empty.")
		if (px.argb.all { (it ushr 24) == 255 }) throw IllegalStateException("This PNG has no transparency: export only the component, background hidden.")
		val part = project.part(partId) ?: throw IllegalStateException("That part is gone.")
		val now = nowIso()
		val v = JsonObject(mapOf("id" to JsonPrimitive(uuid()), "name" to JsonPrimitive(name.trim().ifEmpty { "Expression" }), "art" to JsonPrimitive(pngDataUrl(encodePng(px.width, px.height, px.argb))),
			"prompt" to JsonPrimitive("Artist-drawn variant imported on the iPad."), "model" to JsonPrimitive("artist"), "billing" to JsonNull,
			"createdAt" to JsonPrimitive(now), "approvedAt" to JsonPrimitive(now), "scaled" to JsonPrimitive(false)))
		val list = ((part.json["variants"] as? JsonArray) ?: JsonArray(emptyList())) + v
		return project.updatePart(partId, mapOf("variants" to JsonArray(list)), "Imported expression \"${name.trim()}\"")
	}

	fun deleteVariant(project: StudioProject, partId: String, variantId: String): StudioProject {
		val part = project.part(partId) ?: return project
		val list = (part.json["variants"] as? JsonArray)?.filter { (it as? JsonObject)?.str("id") != variantId } ?: return project
		val plan = part.json["keyformPlan"] as? JsonObject
		val newPlan = plan?.let { JsonObject(it + ("keys" to JsonArray(((it["keys"] as? JsonArray) ?: JsonArray(emptyList())).filter { k -> (k as? JsonObject)?.str("variantId") != variantId }))) }
		return project.updatePart(partId, buildMap { put("variants", JsonArray(list)); if (newPlan != null) put("keyformPlan", newPlan) }, "Deleted an expression variant")
	}

	/** The whole picture with part [partId] shown as variant [art]. */
	fun variantPreview(project: StudioProject, partId: String, art: String): IntArray =
		StudioRender.composite(project.withParts(project.parts.map { if (it.id == partId) it.with(mapOf("visible" to JsonPrimitive(true))) else it }), mapOf(partId to art))

	fun keyformPlan(part: StudioPart): Triple<String, Double, Map<String, Double>>? {
		val plan = part.json["keyformPlan"] as? JsonObject ?: return null
		val keys = (plan["keys"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { k -> k.str("variantId")?.let { id -> (k["value"] as? JsonPrimitive)?.doubleOrNull?.let { id to it } } }?.toMap() ?: emptyMap()
		return Triple(plan.str("parameterId") ?: return null, (plan["neutralValue"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, keys)
	}

	fun setKeyformPlan(project: StudioProject, partId: String, paramId: String?, neutral: Double? = null, keys: Map<String, Double>? = null): StudioProject {
		if (paramId == null) return project.updatePart(partId, mapOf("keyformPlan" to JsonNull), "Removed the keyform plan")
		val param = StudioChecks.keyformParameters.first { it.id == paramId }
		val current = project.part(partId)?.let { keyformPlan(it) }?.takeIf { it.first == paramId }
		val n = neutral ?: current?.second ?: param.default
		val k = keys ?: current?.third ?: emptyMap()
		val plan = JsonObject(mapOf("parameterId" to JsonPrimitive(paramId), "neutralValue" to JsonPrimitive(n),
			"keys" to JsonArray(k.map { (id, v) -> JsonObject(mapOf("variantId" to JsonPrimitive(id), "value" to JsonPrimitive(v))) })))
		return project.updatePart(partId, mapOf("keyformPlan" to plan), "Keyform plan: $paramId")
	}

	// ---- Underlying version (underlying.js): a reference picture, aligned, copied in under a part ----

	class Alignment(val x: Double, val y: Double, val scale: Double, val rotation: Double)
	class Reference(val art: String, val width: Int, val height: Int, val alignment: Alignment?)

	fun reference(project: StudioProject): Reference? {
		val r = project.json["underlyingReference"] as? JsonObject ?: return null
		val a = r["alignment"] as? JsonObject
		fun n(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
		return Reference(r.str("art") ?: return null, (r["width"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return null,
			(r["height"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return null, a?.let { Alignment(n(it, "x"), n(it, "y"), n(it, "scale"), n(it, "rotation")) })
	}

	/** Imports a picture (any size) as the underlying reference - e.g. the character drawn bald or without a hat. */
	fun importReference(project: StudioProject, bytes: ByteArray): StudioProject {
		val px = decodeImage(bytes) ?: throw IllegalStateException("That isn't an image.")
		if (px.width.toLong() * px.height > 16_000_000L) throw IllegalStateException("Use a picture under 16 megapixels.")
		val fit = min(project.width.toDouble() / px.width, project.height.toDouble() / px.height)
		val ref = JsonObject(mapOf("art" to JsonPrimitive(pngDataUrl(encodePng(px.width, px.height, px.argb))), "width" to JsonPrimitive(px.width), "height" to JsonPrimitive(px.height),
			"origin" to JsonPrimitive("imported"), "createdAt" to JsonPrimitive(nowIso()),
			"alignment" to align(Alignment(0.0, 0.0, fit, 0.0))))
		return project.with(mapOf("underlyingReference" to ref), "Imported an underlying reference picture")
	}

	private fun align(a: Alignment) = JsonObject(mapOf("x" to JsonPrimitive(a.x), "y" to JsonPrimitive(a.y), "scale" to JsonPrimitive(a.scale), "rotation" to JsonPrimitive(a.rotation)))

	fun setAlignment(project: StudioProject, a: Alignment): StudioProject {
		val r = project.json["underlyingReference"] as? JsonObject ?: return project
		return project.with(mapOf("underlyingReference" to JsonObject(r + ("alignment" to align(a)))))
	}

	/** The reference drawn onto the canvas: centred, moved by (x, y), turned and scaled (bilinear). */
	fun aligned(project: StudioProject, ref: Reference, a: Alignment): IntArray {
		val w = project.width; val h = project.height
		val src = StudioImages.layer(ref.art, ref.width, ref.height).full()
		val out = IntArray(w * h)
		val rad = a.rotation * kotlin.math.PI / 180; val c = cos(rad); val s = sin(rad)
		val cx = w / 2.0 + a.x; val cy = h / 2.0 + a.y
		for (y in 0 until h) for (x in 0 until w) {
			val dx = x + 0.5 - cx; val dy = y + 0.5 - cy
			val u = (c * dx + s * dy) / a.scale + ref.width / 2.0 - 0.5; val v = (-s * dx + c * dy) / a.scale + ref.height / 2.0 - 0.5
			if (u < -1 || v < -1 || u > ref.width || v > ref.height) continue
			val x0 = floor(u).toInt(); val y0 = floor(v).toInt(); val fx = u - x0; val fy = v - y0
			var aa = 0.0; var rr = 0.0; var gg = 0.0; var bb = 0.0
			for (k in 0 until 4) {
				val xx = x0 + (k and 1); val yy = y0 + (k shr 1)
				if (xx !in 0 until ref.width || yy !in 0 until ref.height) continue
				val wt = (if (k and 1 == 1) fx else 1 - fx) * (if (k shr 1 == 1) fy else 1 - fy)
				val p = src[yy * ref.width + xx]; val pa = (p ushr 24) * wt
				aa += pa; rr += ((p ushr 16) and 0xFF) * pa; gg += ((p ushr 8) and 0xFF) * pa; bb += (p and 0xFF) * pa
			}
			if (aa >= 0.5) out[y * w + x] = (aa.toInt().coerceIn(0, 255) shl 24) or ((rr / aa).toInt().coerceIn(0, 255) shl 16) or ((gg / aa).toInt().coerceIn(0, 255) shl 8) or (bb / aa).toInt().coerceIn(0, 255)
		}
		return out
	}

	/** The drawing with the aligned reference at half strength over it, to line them up by eye. */
	fun overlay(project: StudioProject, ref: Reference, a: Alignment): IntArray {
		val out = StudioImages.layer(project.source, project.width, project.height).full()
		val al = aligned(project, ref, a)
		for (i in out.indices) { val p = al[i]; if ((p ushr 24) != 0) out[i] = StudioRender.blend(out[i], (((p ushr 24) / 2) shl 24) or (p and 0xFFFFFF)) }
		return out
	}

	/** Copies the aligned reference under the selected part's mask into a new layer just behind the part. */
	fun copyUnder(project: StudioProject, partId: String, name: String, ref: Reference, a: Alignment): Pair<StudioProject, String> {
		val part = project.part(partId) ?: throw IllegalStateException("That part is gone.")
		if (part.mask == null) throw IllegalStateException("Paint the hidden region on ${part.name} first (its mask).")
		if (name.isBlank()) throw IllegalStateException("Name the new layer.")
		if (project.parts.size >= 100) throw IllegalStateException("This project already has 100 parts.")
		val al = aligned(project, ref, a); val m = alpha(project, part.mask)
		for (i in al.indices) { val p = al[i]; al[i] = ((((p ushr 24) * m[i]) / 255) shl 24) or (p and 0xFFFFFF) }
		if (al.none { (it ushr 24) != 0 }) throw IllegalStateException("The mask covers none of the aligned reference.")
		val id = uuid()
		val layer = StudioPart(JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name.trim()), "group" to JsonPrimitive(part.group), "visible" to JsonPrimitive(true),
			"mask" to JsonNull, "repairs" to JsonArray(emptyList()), "imported" to JsonPrimitive(true), "cutout" to JsonPrimitive(pngDataUrl(encodePng(project.width, project.height, al))),
			"underlyingFrom" to JsonObject(mapOf("origin" to JsonPrimitive("imported"), "createdAt" to JsonPrimitive(nowIso()), "alignment" to align(a))))))
		val index = project.parts.indexOfFirst { it.id == partId }
		val parts = project.parts.subList(0, index) + layer + project.parts.subList(index, project.parts.size)
		return project.withParts(parts, "Copied the underlying reference into \"${name.trim()}\" behind ${part.name}", id) to id
	}
}
