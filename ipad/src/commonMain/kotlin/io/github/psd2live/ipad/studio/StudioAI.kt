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
	internal suspend fun edit(context: IntArray, region: ByteArray, w: Int, h: Int, box: IntArray, prompt: String, kind: String): IntArray {
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

/**
 * Redraw a piece cleanly (the web's `redraw-browser.js`, from the Mac's redraw-piece.py): its box goes on a flat green
 * sheet at the editor's size, the answer is keyed, put back, nudged into line, and keeps the piece's OWN alpha - so
 * masking, trims and reshaping stay exactly as they were and only the paint is redrawn. A redraw whose shape no
 * longer matches (overlap under 0.85) is refused.
 */
object StudioRedraw {
	class Prepared(val sheet: IntArray, val sw: Int, val sh: Int, val box: IntArray, val fit: IntArray, val piece: IntArray, val w: Int, val h: Int)

	/** Where redraws go: the private studio's /redraw (a test puts a stand-in here). */
	var transport: suspend (sheetPng: ByteArray, size: String) -> Pixels = { png, size -> send(png, size) }

	fun prepare(piece: IntArray, w: Int, h: Int): Prepared {
		var bx0 = w; var by0 = h; var bx1 = -1; var by1 = -1
		for (y in 0 until h) for (x in 0 until w) if ((piece[y * w + x] ushr 24) != 0) { if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y }
		if (bx1 < 0) throw IllegalStateException("The piece is empty.")
		val bw = bx1 + 1 - bx0; val bh = by1 + 1 - by0; val m = max(16, (0.08 * max(bw, bh)).toInt())
		val x0 = max(0, bx0 - m); val y0 = max(0, by0 - m); val x1 = min(w, bx1 + 1 + m); val y1 = min(h, by1 + 1 + m)
		val cw = x1 - x0; val ch = y1 - y0; val aspect = cw.toDouble() / ch
		val (SW, SH) = if (aspect > 1.25) 1536 to 1024 else if (aspect < 0.8) 1024 to 1536 else 1024 to 1024
		val s = min(SW.toDouble() / cw, SH.toDouble() / ch); val fw = (cw * s).roundToInt(); val fh = (ch * s).roundToInt()
		val ox = (SW - fw) / 2; val oy = (SH - fh) / 2
		val sheet = IntArray(SW * SH) { 0xFF00FF00.toInt() }
		val scaled = StudioAI.resample(piece, w, h, x0, y0, cw, ch, fw, fh)
		for (y in 0 until fh) for (x in 0 until fw) { val i = (oy + y) * SW + ox + x; sheet[i] = StudioRender.blend(sheet[i], scaled[y * fw + x]) }
		return Prepared(sheet, SW, SH, intArrayOf(x0, y0, x1, y1), intArrayOf(ox, oy, fw, fh), piece, w, h)
	}

	/** The editor's green-screen answer to straight ARGB (gen-snap-bench.py key_green). */
	fun keyGreen(src: IntArray, w: Int, h: Int): IntArray {
		val n = w * h
		fun r(i: Int) = (src[i] ushr 16) and 0xFF
		fun g(i: Int) = (src[i] ushr 8) and 0xFF
		fun b(i: Int) = src[i] and 0xFF
		val greenness = FloatArray(n) { (g(it) - max(r(it), b(it))).toFloat() }
		var sr = 0.0; var sg = 0.0; var sb = 0.0; var strong = 0
		for (i in 0 until n) if (greenness[i] > 120) { sr += r(i); sg += g(i); sb += b(i); strong++ }
		val screen = if (strong > 100) doubleArrayOf(sr / strong, sg / strong, sb / strong) else doubleArrayOf(0.0, 255.0, 0.0)
		val excess = max(1.0, screen[1] - max(screen[0], screen[2]))
		val t = FloatArray(n) { (greenness[it] / excess).coerceIn(0.0, 1.0).toFloat() }
		val bright = screen.max()
		val greenish = BooleanArray(n) { i ->
			val rr = r(i) / 255.0; val gg = g(i) / 255.0; val bb = b(i) / 255.0
			val mx = maxOf(rr, gg, bb); val mn = minOf(rr, gg, bb); val sat = if (mx > 0) (mx - mn) / mx else 0.0
			var hue = 0.0
			if (mx != mn) hue = when (mx) { gg -> 60 * ((bb - rr) / (mx - mn)) + 120; rr -> 60 * (((gg - bb) / (mx - mn)) % 6); else -> 60 * ((rr - gg) / (mx - mn)) + 240 }
			if (hue < 0) hue += 360
			hue in 80.0..164.0 && sat >= 90 / 255.0 && greenness[i] > 0 && mx * 255 < 0.85 * bright
		}
		val seen = BooleanArray(n); val queue = IntArray(n); var head = 0; var tail = 0
		for (i in 0 until n) if (t[i] > 0.6f) { seen[i] = true; queue[tail++] = i }
		fun nbrs(i: Int): IntArray { val x = i % w; val y = i / w; return intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1) }
		while (head < tail) {
			val i = queue[head++]
			for (j in nbrs(i)) { if (j < 0 || seen[j] || !(greenish[j] || t[j] > 0.6f)) continue; seen[j] = true; queue[tail++] = j; if (greenish[j]) t[j] = 1f }
		}
		val alpha = FloatArray(n); val oc = Array(3) { FloatArray(n) }
		for (i in 0 until n) {
			val a = 1 - t[i]; alpha[i] = a
			val obs = intArrayOf(r(i), g(i), b(i))
			for (c in 0 until 3) oc[c][i] = (if (a > 0.02f) (obs[c] - t[i] * screen[c]) / max(a.toDouble(), 0.02) else obs[c].toDouble()).toFloat().coerceIn(0f, 255f)
			oc[1][i] = min(oc[1][i], max(oc[0][i], oc[2][i]))
		}
		val depth = FloatArray(n) { Float.POSITIVE_INFINITY }; head = 0; tail = 0
		for (i in 0 until n) if (alpha[i] <= 0.08f) { depth[i] = 0f; queue[tail++] = i }
		while (head < tail) { val i = queue[head++]; for (j in nbrs(i)) { if (j < 0 || depth[j] <= depth[i] + 1) continue; depth[j] = depth[i] + 1; queue[tail++] = j } }
		return IntArray(n) { i ->
			val band = (1 - depth[i] / 10).coerceIn(0f, 1f)
			val rr = oc[0][i]; val gg = oc[1][i]; val bb = oc[2][i]
			val g2 = gg - band * max(0f, gg - (rr + bb) / 2 - 2)
			((alpha[i] * 255).roundToInt().coerceIn(0, 255) shl 24) or (rr.toInt() shl 16) or (g2.toInt().coerceIn(0, 255) shl 8) or bb.toInt()
		}
	}

	private fun overlap(a: IntArray, b: IntArray): Double {
		var both = 0; var either = 0
		for (i in a.indices) { val x = (a[i] ushr 24) > 128; val y = (b[i] ushr 24) > 128; if (x && y) both++; if (x || y) either++ }
		return both.toDouble() / max(1, either)
	}

	/** Puts the answer back onto the piece. Returns the full-canvas piece and the overlap, or throws when refused. */
	fun finish(p: Prepared, answer: Pixels): Pair<IntArray, Double> {
		val (x0, y0, x1, y1) = p.box.let { listOf(it[0], it[1], it[2], it[3]) }
		val (ox, oy, fw, fh) = p.fit.let { listOf(it[0], it[1], it[2], it[3]) }
		val sheet = StudioAI.resample(answer.argb, answer.width, answer.height, 0, 0, answer.width, answer.height, p.sw, p.sh)
		val keyed = keyGreen(sheet, p.sw, p.sh)
		val w = x1 - x0; val h = y1 - y0
		var theirs = StudioAI.resample(keyed, p.sw, p.sh, ox, oy, fw, fh, w, h)
		val mine = IntArray(w * h) { p.piece[(y0 + it / w) * p.w + x0 + it % w] }
		// Best shift within ±12 px (a redraw is nudged, not reshaped).
		var best = Triple(0, 0, -1.0); val step = max(1, max(w, h) / 256)
		for (dy in -12..12) for (dx in -12..12) {
			var both = 0; var either = 0
			var y = 0; while (y < h) { var x = 0; while (x < w) {
				val sx = x - dx; val sy = y - dy
				val m = (mine[y * w + x] ushr 24) > 128
				val t = sx in 0 until w && sy in 0 until h && (theirs[sy * w + sx] ushr 24) > 128
				if (m && t) both++; if (m || t) either++
				x += step }; y += step }
			val score = both.toDouble() / max(1, either)
			if (score > best.third) best = Triple(dx, dy, score)
		}
		if (best.first != 0 || best.second != 0) {
			val src = theirs; val (dx, dy) = best.first to best.second
			theirs = IntArray(w * h) { i -> val sx = i % w - dx; val sy = i / w - dy; if (sx in 0 until w && sy in 0 until h) src[sy * w + sx] else 0 }
		}
		val iou = overlap(mine, theirs)
		if (iou < 0.85) throw IllegalStateException("The redraw changed the piece's shape too much (overlap ${(iou * 100).roundToInt() / 100.0}); nothing was applied.")
		// Paint from the redraw wherever it has paint; elsewhere inside the piece, the nearest redrawn colour.
		val n = w * h; val rgb = IntArray(n); val has = BooleanArray(n); val queue = IntArray(n); var head = 0; var tail = 0
		for (i in 0 until n) if ((theirs[i] ushr 24) > 32) { has[i] = true; queue[tail++] = i; rgb[i] = theirs[i] and 0xFFFFFF }
		if (tail == 0) for (i in 0 until n) rgb[i] = mine[i] and 0xFFFFFF
		while (head < tail) {
			val i = queue[head++]; val x = i % w; val y = i / w
			for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
				if (j < 0 || has[j]) continue; has[j] = true; queue[tail++] = j; rgb[j] = rgb[i]
			}
		}
		val full = IntArray(p.w * p.h)
		for (i in 0 until n) full[(y0 + i / w) * p.w + x0 + i % w] = (mine[i] and 0xFF000000.toInt()) or rgb[i] // the piece's own outline
		return full to iou
	}

	/** One /redraw call: the green sheet as a multipart upload; the answer's pixels or a readable error. */
	private suspend fun send(png: ByteArray, size: String): Pixels {
		val boundary = "layerloom-${uuid()}"
		val body = okio.Buffer().apply {
			writeUtf8("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"piece.png\"\r\nContent-Type: image/png\r\n\r\n"); write(png)
			writeUtf8("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"size\"\r\n\r\n$size\r\n--$boundary--\r\n")
		}.readByteArray()
		val (status, text) = suspendCancellableCoroutine { done -> PrivateStudio.post("/redraw", "multipart/form-data; boundary=$boundary", body) { s, t -> done.resume(s to t) } }
		val json = try { Json.parseToJsonElement(text).jsonObject } catch (_: Throwable) { null }
		val out = json?.str("png")
		if (status !in 200..299 || out == null) throw IllegalStateException(json?.str("error") ?: if (status == 0) "Your private studio could not be reached." else "Sign in to your private studio first (it answered $status).")
		return decodeImage(dataUrlBytes(out)) ?: throw IllegalStateException("The redraw came back unreadable.")
	}

	/** Redraws [piece] (the whole canvas): one paid edit. Returns the redrawn piece and the shape overlap. */
	suspend fun redraw(piece: IntArray, w: Int, h: Int): Pair<IntArray, Double> {
		val p = prepare(piece, w, h)
		val answer = transport(encodePng(p.sw, p.sh, p.sheet), "${p.sw}x${p.sh}")
		return finish(p, answer)
	}
}

/**
 * AI hidden-art repair and AI expressions (the web's `repair` in App.jsx, `repairInputs`, `constrainCandidate`,
 * `createVariant`): the drawing (or, for an expression, the character with this part shown) and an edit mask - white
 * with the part's mask punched out - go to the private studio's /repair; the answer is clipped to the mask. A repair
 * becomes a patch under the part it belongs to; an expression becomes a variant. /repair/check is free.
 */
object StudioRepairAI {
	class Candidate(val partId: String, val operation: String, val patch: String, val variantArt: String?, val preview: IntArray, val prompt: String, val model: String?, val mask: String, val scaled: Boolean)

	var transport: suspend (path: String, body: ByteArray, contentType: String) -> Pair<Int, String> = { path, body, type ->
		suspendCancellableCoroutine { done -> PrivateStudio.post(path, type, body) { s, t -> done.resume(s to t) } }
	}

	private fun inputs(project: StudioProject, partId: String, operation: String): Triple<ByteArray, ByteArray, String> {
		val w = project.width; val h = project.height
		val part = project.part(partId) ?: throw IllegalStateException("That part is gone.")
		val maskUrl = part.mask ?: throw IllegalStateException("Paint a mask over the area first.")
		val image = if (operation == "expression") {
			val art = StudioPeel.partArt(project, partId) ?: throw IllegalStateException("Extract or import the part's neutral art first.")
			StudioFace.variantPreview(project, partId, pngDataUrl(encodePng(w, h, art)))
		} else StudioImages.layer(project.source, w, h).full()
		val m = StudioImages.layer(maskUrl, w, h)
		val edit = IntArray(w * h) { -1 }
		for (r in 0 until m.height) for (c in 0 until m.width) { val a = m.argb[r * m.width + c] ushr 24; if (a > 0) edit[(m.y + r) * w + m.x + c] = ((255 - a) shl 24) or 0xFFFFFF }
		return Triple(encodePng(w, h, image), encodePng(w, h, edit), maskUrl)
	}

	private fun multipart(image: ByteArray, mask: ByteArray, prompt: String, operation: String): Pair<ByteArray, String> {
		val b = "layerloom-${uuid()}"
		val body = okio.Buffer().apply {
			fun file(name: String, data: ByteArray) { writeUtf8("--$b\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$name.png\"\r\nContent-Type: image/png\r\n\r\n"); write(data); writeUtf8("\r\n") }
			fun field(name: String, v: String) { writeUtf8("--$b\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n"); writeUtf8(v); writeUtf8("\r\n") }
			file("image", image); file("mask", mask); field("prompt", prompt); field("operation", operation); writeUtf8("--$b--\r\n")
		}.readByteArray()
		return body to "multipart/form-data; boundary=$b"
	}

	private fun parse(status: Int, text: String): JsonObject {
		val json = try { Json.parseToJsonElement(text).jsonObject } catch (_: Throwable) { null }
		if (status !in 200..299 || json == null) throw IllegalStateException(json?.str("error") ?: if (status == 0) "Your private studio could not be reached." else "Sign in to your private studio first (it answered $status).")
		return json
	}

	/** The free check: the upload is accepted and today's attempts left, without calling the image editor. */
	suspend fun check(project: StudioProject, partId: String, prompt: String, operation: String): String {
		val (img, mask, _) = inputs(project, partId, operation)
		val (body, type) = multipart(img, mask, prompt.ifBlank { "check" }, operation)
		val (s, t) = transport("/repair/check", body, type)
		val q = parse(s, t)["quota"] as? JsonObject
		return "Request check passed (no image edit sent, nothing spent). Attempts left today: ${q?.get("remainingConnection") ?: "?"} on this connection, ${q?.get("remainingStudio") ?: "?"} for the studio."
	}

	/** One paid edit: the candidate clipped to the mask, ready to review. */
	suspend fun generate(project: StudioProject, partId: String, prompt: String, operation: String): Candidate {
		val w = project.width; val h = project.height
		if (prompt.isBlank()) throw IllegalStateException(if (operation == "expression") "Name and describe the expression first." else "Describe the hidden art first.")
		val (img, mask, maskUrl) = inputs(project, partId, operation)
		val (body, type) = multipart(img, mask, prompt, operation)
		val (s, t) = transport("/repair", body, type)
		val json = parse(s, t)
		val cand = decodeImage(dataUrlBytes(json.str("candidate") ?: throw IllegalStateException("The private studio returned no image."))) ?: throw IllegalStateException("The image came back unreadable.")
		val scaled = cand.width != w || cand.height != h
		val full = if (scaled) StudioAI.resample(cand.argb, cand.width, cand.height, 0, 0, cand.width, cand.height, w, h) else cand.argb
		// Clip to the mask (destination-in by its alpha).
		val m = IntArray(w * h).also { a -> val l = StudioImages.layer(maskUrl, w, h); for (r in 0 until l.height) for (c in 0 until l.width) a[(l.y + r) * w + l.x + c] = l.argb[r * l.width + c] ushr 24 }
		val patch = IntArray(w * h) { i -> val p = full[i]; ((((p ushr 24) * m[i]) / 255) shl 24) or (p and 0xFFFFFF) }
		val patchUrl = pngDataUrl(encodePng(w, h, patch))
		return if (operation == "expression") {
			val art = StudioPeel.partArt(project, partId)!!
			val variant = IntArray(w * h) { i -> val p = art[i]; ((((p ushr 24) * (255 - m[i])) / 255) shl 24) or (p and 0xFFFFFF) }
			for (i in variant.indices) variant[i] = StudioRender.blend(variant[i], patch[i])
			val vUrl = pngDataUrl(encodePng(w, h, variant))
			Candidate(partId, operation, patchUrl, vUrl, StudioFace.variantPreview(project, partId, vUrl), prompt, json.str("model"), maskUrl, scaled)
		} else {
			val preview = StudioImages.layer(project.source, w, h).full()
			for (i in preview.indices) preview[i] = StudioRender.blend(preview[i], patch[i])
			Candidate(partId, operation, patchUrl, null, preview, prompt, json.str("model"), maskUrl, scaled)
		}
	}

	/** Approves a candidate: a repair patch (belonging to [targetId], or a separate layer), or an expression variant named [name]. */
	fun approve(project: StudioProject, c: Candidate, targetId: String?, name: String): StudioProject {
		val part = project.part(c.partId) ?: return project
		val now = nowIso()
		return if (c.operation == "expression") {
			val v = JsonObject(mapOf("id" to JsonPrimitive(uuid()), "name" to JsonPrimitive(name.trim().ifEmpty { "Expression" }), "art" to JsonPrimitive(c.variantArt!!),
				"prompt" to JsonPrimitive(c.prompt), "model" to JsonPrimitive(c.model ?: "openai"), "billing" to kotlinx.serialization.json.JsonNull,
				"createdAt" to JsonPrimitive(now), "approvedAt" to JsonPrimitive(now), "scaled" to JsonPrimitive(c.scaled)))
			project.updatePart(c.partId, mapOf("variants" to kotlinx.serialization.json.JsonArray(((part.json["variants"] as? kotlinx.serialization.json.JsonArray) ?: kotlinx.serialization.json.JsonArray(emptyList())) + v)), "Approved expression variant")
		} else {
			val r = JsonObject(mapOf("id" to JsonPrimitive(uuid()), "kind" to JsonPrimitive("repair"), "patch" to JsonPrimitive(c.patch), "prompt" to JsonPrimitive(c.prompt),
				"model" to JsonPrimitive(c.model ?: "openai"), "createdAt" to JsonPrimitive(now), "mask" to JsonPrimitive(c.mask), "scaled" to JsonPrimitive(c.scaled),
				"targetPartId" to (targetId?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)))
			project.updatePart(c.partId, mapOf("repairs" to kotlinx.serialization.json.JsonArray(part.repairs + r), "repairTargetId" to (targetId?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)), "Approved reconstructed hidden art")
		}
	}
}
