package io.github.psd2live.ipad.studio

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The AI image edits, through the private studio: Peel's repaint-underneath and Reveal (the web's `peel.js`). The
 * studio prepares everything on the iPad - the context square on magenta, the mask - and only the edit itself goes to
 * the worker (one paid call, inside its daily limit, never retried).
 */
object StudioAI {
	private const val SIDE = 1024

	/** Where image edits go: the private studio (a test puts a stand-in here). */
	var transport: suspend (image: ByteArray, mask: ByteArray?, prompt: String, kind: String) -> Pixels = { i, m, p, k -> generate(i, m, p, k) }

	/** One image edit: {image, mask?, prompt, kind} to /generate; the candidate PNG's pixels, or a readable error. */
	suspend fun generate(image: ByteArray, mask: ByteArray?, prompt: String, kind: String): Pixels {
		val body = JsonObject(buildMap {
			put("image", JsonPrimitive(pngDataUrl(image))); if (mask != null) put("mask", JsonPrimitive(pngDataUrl(mask)))
			put("prompt", JsonPrimitive(prompt)); put("kind", JsonPrimitive(kind)); put("provider", JsonPrimitive("openai"))
		}).toString().encodeToByteArray()
		val (status, text) = suspendCancellableCoroutine { done -> PrivateStudio.post("/generate", "application/json", body) { s, t -> done.resume(s to t) } }
		val json = try { Json.parseToJsonElement(text).jsonObject } catch (_: Throwable) { null }
		val candidate = json?.str("candidate")
		if (status !in 200..299 || candidate == null) {
			val why = json?.str("error") ?: when (status) { 401, 302, 303 -> "Sign in to your private studio first."; 0 -> "Your private studio could not be reached."; else -> "The private studio answered $status." }
			throw IllegalStateException(if (status == 200 && json?.str("error") == null) "Your sign-in has run out: sign in again." else why)
		}
		return decodeImage(dataUrlBytes(candidate)) ?: throw IllegalStateException("The image came back unreadable.")
	}

	/** [argb] ([w] wide) region (x, y, side × side) resized to out × out: box average shrinking, bilinear growing. */
	fun resample(argb: IntArray, w: Int, h: Int, x: Int, y: Int, sw: Int, sh: Int, ow: Int, oh: Int): IntArray {
		val out = IntArray(ow * oh)
		val kx = sw.toDouble() / ow; val ky = sh.toDouble() / oh
		for (oy in 0 until oh) for (ox in 0 until ow) {
			var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var n = 0.0
			if (kx > 1 || ky > 1) {
				val x0 = x + floor(ox * kx).toInt(); val x1 = max(x0 + 1, x + floor((ox + 1) * kx).toInt())
				val y0 = y + floor(oy * ky).toInt(); val y1 = max(y0 + 1, y + floor((oy + 1) * ky).toInt())
				for (yy in y0 until min(h, y1)) for (xx in x0 until min(w, x1)) {
					if (xx < 0 || yy < 0) continue
					val p = argb[yy * w + xx]; val pa = (p ushr 24).toDouble()
					a += pa; r += ((p ushr 16) and 0xFF) * pa; g += ((p ushr 8) and 0xFF) * pa; b += (p and 0xFF) * pa; n++
				}
			} else {
				val u = x + (ox + 0.5) * kx - 0.5; val v = y + (oy + 0.5) * ky - 0.5
				val x0 = floor(u).toInt(); val y0 = floor(v).toInt(); val fx = u - x0; val fy = v - y0
				for (k in 0 until 4) {
					val xx = (x0 + (k and 1)).coerceIn(0, w - 1); val yy = (y0 + (k shr 1)).coerceIn(0, h - 1)
					val wt = (if (k and 1 == 1) fx else 1 - fx) * (if (k shr 1 == 1) fy else 1 - fy)
					val p = argb[yy * w + xx]; val pa = (p ushr 24) * wt
					a += pa; r += ((p ushr 16) and 0xFF) * pa; g += ((p ushr 8) and 0xFF) * pa; b += (p and 0xFF) * pa; n += wt
				}
			}
			if (n > 0 && a > 0) out[oy * ow + ox] = ((a / n).roundToInt().coerceIn(0, 255) shl 24) or ((r / a).toInt().coerceIn(0, 255) shl 16) or ((g / a).toInt().coerceIn(0, 255) shl 8) or (b / a).toInt().coerceIn(0, 255)
		}
		return out
	}

	/** The square the model works on: around the region with context, inside the canvas. */
	fun squareAround(b: IntArray, w: Int, h: Int): IntArray {
		var side = (max(b[2] - b[0], b[3] - b[1]) * 2.2).roundToInt() + 64
		side = min(side, min(w, h))
		val cx = (b[0] + b[2]) / 2.0; val cy = (b[1] + b[3]) / 2.0
		return intArrayOf((cx - side / 2.0).coerceIn(0.0, (w - side).toDouble()).roundToInt(), (cy - side / 2.0).coerceIn(0.0, (h - side).toDouble()).roundToInt(), side)
	}

	private fun onMagenta(a: IntArray) = IntArray(a.size) { StudioRender.blend(0xFFFF00FF.toInt(), a[it]) }
	private fun keyAlpha(p: Int): Double { val d = abs(((p ushr 16) and 0xFF) - 255) + abs((p ushr 8) and 0xFF) + abs((p and 0xFF) - 255); return ((d - 60) / 120.0).coerceIn(0.0, 1.0) }
	private fun dist(p: Int, q: Int) = abs(((p ushr 16) and 0xFF) - ((q ushr 16) and 0xFF)) + abs(((p ushr 8) and 0xFF) - ((q ushr 8) and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))

	/** Sends the [box] square of [context] with [region] editable, and returns the answer at the square's own size. */
	private suspend fun edit(context: IntArray, region: ByteArray, w: Int, h: Int, box: IntArray, prompt: String, kind: String): IntArray {
		val (bx, by, side) = Triple(box[0], box[1], box[2])
		val img = resample(context, w, h, bx, by, side, side, SIDE, SIDE)
		val m = IntArray(side * side) { i -> if (region[(by + i / side) * w + bx + i % side].toInt() != 0) 0x00FFFFFF else -1 }
		val mask = resample(m, side, side, 0, 0, side, side, SIDE, SIDE).map { if ((it ushr 24) < 128) 0x00FFFFFF else -1 }.toIntArray()
		val out = transport(encodePng(SIDE, SIDE, img), encodePng(SIDE, SIDE, mask), prompt, kind)
		return resample(out.argb, out.width, out.height, 0, 0, out.width, out.height, side, side)
	}

	class Split(val cut: StudioPeel.Cut, val redrawnShare: Double)

	/**
	 * Peel → Split with repaint: lifts the masked piece (as [StudioPeel.cut]) and has the model show what was under it on
	 * the part. Where the answer matches what the layers below already show, the part gets nothing; what looks like the
	 * removed piece is dropped.
	 */
	suspend fun peelSplit(project: StudioProject, partId: String, name: String, behind: String): Split {
		val w = project.width; val h = project.height
		val part = project.part(partId) ?: throw IllegalStateException("That part is gone.")
		val maskUrl = part.mask ?: throw IllegalStateException("Mark the piece to lift off first.")
		val art = StudioPeel.partArt(project, partId) ?: throw IllegalStateException("This part has no artwork yet.")
		val ma = IntArray(w * h).also { a -> val m = StudioImages.layer(maskUrl, w, h); for (r in 0 until m.height) for (c in 0 until m.width) a[(m.y + r) * w + m.x + c] = m.argb[r * m.width + c] ushr 24 }
		val region = ByteArray(w * h) { if (ma[it] >= 128 && (art[it] ushr 24) > 0) 1 else 0 }
		val b = boundsOf(region, w, h) ?: throw IllegalStateException("The mask doesn't cover any of this part's artwork.")
		val lifted = IntArray(w * h); val rest = art.copyOf()
		for (i in art.indices) { val a = art[i] ushr 24; if (a == 0) continue; val up = (a * ma[i] + 127) / 255; lifted[i] = (up shl 24) or (art[i] and 0xFFFFFF); rest[i] = ((a - up) shl 24) or (art[i] and 0xFFFFFF) }
		val grow = StudioMatch.dilate(region, w, h, 6)
		val box = squareAround(b, w, h)
		val index = project.parts.indexOfFirst { it.id == partId }
		val shown = StudioRender.composite(project)
		val belowProject = project.withParts(project.parts.mapIndexed { i, p -> if (i >= index) p.with(mapOf("visible" to JsonPrimitive(false))) else p })
		val below = onMagenta(StudioRender.composite(belowProject))
		val prompt = "Remove the $name completely. Inside the masked area show what is underneath it ($behind), continuing exactly from the surrounding artwork with the same colours, pattern, shading and outline style. Do not draw any part of the $name and add nothing new. The background is flat magenta (#FF00FF); keep it flat magenta wherever the character isn't."
		val sq = edit(onMagenta(shown), grow, w, h, box, prompt, "peel-split")
		val (bx, by, side) = Triple(box[0], box[1], box[2])
		var redrawn = 0
		for (y in 0 until side) for (x in 0 until side) {
			val i = (by + y) * w + bx + x
			if (grow[i].toInt() == 0) continue
			val q = sq[y * side + x]
			var a = keyAlpha(q)
			a *= ((dist(q, below[i]) - 30) / 50.0).coerceIn(0.0, 1.0) // already shown by the layers below: not this part's
			if ((lifted[i] ushr 24) > 64) {
				val same = ((70 - dist(q, art[i])) / 40.0).coerceIn(0.0, 1.0) // looks like the removed piece: dropped
				if (same > 0.5 && a > 0.5) redrawn++
				a *= 1 - same
			}
			if (region[i].toInt() == 0 && (rest[i] ushr 24) > 0) continue // outside the piece the part's own pixels stay
			rest[i] = ((a * 255).roundToInt() shl 24) or (q and 0xFFFFFF)
		}
		val covered = region.count { it.toInt() != 0 }
		return Split(StudioPeel.Cut(pngDataUrl(encodePng(w, h, lifted)), pngDataUrl(encodePng(w, h, rest))), if (covered > 0) redrawn.toDouble() / covered else 0.0)
	}

	/**
	 * Peel → Reveal: paints hidden art ([what]) into the part inside its mask, behind what it has. The model sees the
	 * character as shown (hide what's in front first); only pixels it changed are kept.
	 */
	suspend fun peelReveal(project: StudioProject, partId: String, what: String): String {
		val w = project.width; val h = project.height
		val part = project.part(partId) ?: throw IllegalStateException("That part is gone.")
		val maskUrl = part.mask ?: throw IllegalStateException("Mark where the hidden art should go first.")
		val m = StudioImages.layer(maskUrl, w, h)
		val region = ByteArray(w * h)
		for (r in 0 until m.height) for (c in 0 until m.width) if ((m.argb[r * m.width + c] ushr 24) >= 128) region[(m.y + r) * w + m.x + c] = 1
		val b = boundsOf(region, w, h) ?: throw IllegalStateException("The mask is empty.")
		val context = onMagenta(StudioRender.composite(project))
		val box = squareAround(b, w, h)
		val prompt = "Inside the masked area, paint $what, as it would look behind everything else, matching this character's art style, palette, line weight and shading exactly. Paint only that; anywhere inside the mask where it would not be, fill with flat magenta (#FF00FF). Leave everything outside the mask unchanged."
		val sq = edit(context, region, w, h, box, prompt, "peel-reveal")
		val (bx, by, side) = Triple(box[0], box[1], box[2])
		val out = IntArray(w * h)
		for (y in 0 until side) for (x in 0 until side) {
			val i = (by + y) * w + bx + x
			if (region[i].toInt() == 0) continue
			val q = sq[y * side + x]
			val keep = keyAlpha(q) * ((dist(q, context[i]) - 30) / 60.0).coerceIn(0.0, 1.0) // a copy of what's visible isn't hidden art
			out[i] = ((keep * 255).roundToInt() shl 24) or (q and 0xFFFFFF)
		}
		StudioPeel.partArt(project, partId)?.let { existing -> for (i in out.indices) out[i] = StudioRender.blend(out[i], existing[i]) } // under the part's own art
		return pngDataUrl(encodePng(w, h, out))
	}

	/** The project after a Reveal: the part's art replaced, how to undo it, and the step recorded. */
	fun applyReveal(project: StudioProject, partId: String, art: String, what: String): StudioProject {
		val part = project.part(partId) ?: return project
		val undo = JsonObject(mapOf("kind" to JsonPrimitive("reveal"), "before" to (part.cutout?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)))
		val step = JsonObject(mapOf("kind" to JsonPrimitive("reveal"), "into" to JsonPrimitive(part.name), "what" to JsonPrimitive(what), "at" to JsonPrimitive(nowIso())))
		val peels = ((project.json["peels"] as? kotlinx.serialization.json.JsonArray) ?: kotlinx.serialization.json.JsonArray(emptyList())).plus(step).takeLast(200)
		val next = project.updatePart(partId, mapOf("cutout" to JsonPrimitive(art), "mask" to kotlinx.serialization.json.JsonNull, "peelUndo" to undo), "Revealed hidden art in ${part.name}")
		return next.with(mapOf("peels" to kotlinx.serialization.json.JsonArray(peels)))
	}

	private fun boundsOf(map: ByteArray, w: Int, h: Int): IntArray? {
		var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
		for (y in 0 until h) for (x in 0 until w) if (map[y * w + x].toInt() != 0) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
		return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
	}
}
