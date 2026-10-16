package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Match the drawing, the web studio's `match.js`: compares the stacked parts with the drawing and, where the wrong
 * part ends up on top, proposes a fix to review - move the part up, or split off the piece that belongs in front;
 * where two parts that move differently both hold the same drawn piece, asks which one it belongs to.
 */
object StudioMatch {
	private const val MAX = 720 // analysis size; fixes are cut at full size
	private const val FAR = 52 // colour distance beyond which a part doesn't look like the drawing
	private const val GAP = 18 // how much closer a lower part must be to count as the one that should show
	private const val SAME = 22 // two parts this close to the drawing both "drew" the pixel

	enum class Kind { MOVE, SPLIT, OWNER }

	class Proposal(
		val key: String, val kind: Kind,
		/** move/split: the part that should show, and the one covering it; owner: the upper and lower holder. */
		val a: String, val b: String, val aName: String, val bName: String,
		val aMoves: String, val bMoves: String,
		val pixels: Int, val bounds: IntArray, val map: ByteArray, val scale: Double, val w: Int, val h: Int,
		var waitsFor: String? = null,
	)

	class Result(val proposals: List<Proposal>, val waiting: List<Proposal>, val overlay: IntArray, val missing: Int, val checked: Int)

	private val moves = mapOf("headwear" to "the head", "face" to "the head", "ears" to "the head", "front hair" to "the head, swinging",
		"back hair" to "the head, swinging", "topwear" to "the body", "neck" to "the body", "neckwear" to "the body", "bottomwear" to "the body",
		"handwear" to "the arm", "objects" to "the body, swinging")
	private fun movesWith(m: String) = moves[m] ?: "its own motion"

	/** [argb] (a full canvas [W] wide) averaged down to [w] x [h], alpha-weighted. */
	private fun shrink(argb: IntArray, W: Int, H: Int, w: Int, h: Int): IntArray {
		val out = IntArray(w * h)
		for (y in 0 until h) {
			val sy0 = y * H / h; val sy1 = max(sy0 + 1, (y + 1) * H / h)
			for (x in 0 until w) {
				val sx0 = x * W / w; val sx1 = max(sx0 + 1, (x + 1) * W / w)
				var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
				for (sy in sy0 until min(H, sy1)) for (sx in sx0 until min(W, sx1)) {
					val p = argb[sy * W + sx]; val pa = (p ushr 24).toLong()
					a += pa; r += ((p ushr 16) and 0xFF) * pa; g += ((p ushr 8) and 0xFF) * pa; b += (p and 0xFF) * pa; n++
				}
				if (n > 0 && a > 0) out[y * w + x] = (((a / n).toInt()) shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
			}
		}
		return out
	}

	private fun dist(p: Int, q: Int) = max(abs(((p ushr 16) and 0xFF) - ((q ushr 16) and 0xFF)), max(abs(((p ushr 8) and 0xFF) - ((q ushr 8) and 0xFF)), abs((p and 0xFF) - (q and 0xFF))))

	/** Square max filter: grows a 0/1 map by [r]. */
	fun dilate(map: ByteArray, w: Int, h: Int, r: Int): ByteArray {
		val tmp = ByteArray(map.size); val out = ByteArray(map.size)
		for (y in 0 until h) for (x in 0 until w) { var v: Byte = 0; var k = max(0, x - r); while (k <= min(w - 1, x + r) && v.toInt() == 0) { v = map[y * w + k]; k++ }; tmp[y * w + x] = v }
		for (x in 0 until w) for (y in 0 until h) { var v: Byte = 0; var k = max(0, y - r); while (k <= min(h - 1, y + r) && v.toInt() == 0) { v = tmp[k * w + x]; k++ }; out[y * w + x] = v }
		return out
	}

	private fun open(map: ByteArray, w: Int, h: Int, r: Int): ByteArray {
		val inv = ByteArray(map.size) { if (map[it].toInt() != 0) 0 else 1 }
		val grown = dilate(inv, w, h, r)
		val eroded = ByteArray(map.size) { if (grown[it].toInt() != 0) 0 else 1 }
		return dilate(eroded, w, h, r)
	}

	private fun dropSmall(map: ByteArray, w: Int, min: Int): ByteArray {
		val seen = BooleanArray(map.size); val out = ByteArray(map.size); val stack = IntArray(map.size); val blob = IntArray(map.size)
		for (start in map.indices) {
			if (map[start].toInt() == 0 || seen[start]) continue
			var top = 0; var n = 0; stack[top++] = start; seen[start] = true
			while (top > 0) {
				val p = stack[--top]; blob[n++] = p; val x = p % w
				for (q in intArrayOf(p - w, p + w, if (x > 0) p - 1 else -1, if (x < w - 1) p + 1 else -1))
					if (q >= 0 && q < map.size && map[q].toInt() != 0 && !seen[q]) { seen[q] = true; stack[top++] = q }
			}
			if (n >= min) for (i in 0 until n) out[blob[i]] = 1
		}
		return out
	}

	private fun boundsOf(map: ByteArray, w: Int, h: Int): IntArray? {
		var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
		for (y in 0 until h) for (x in 0 until w) if (map[y * w + x].toInt() != 0) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
		return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
	}

	/** Looks for places where the stack doesn't show what the drawing shows. */
	fun compare(project: StudioProject): Result {
		val W = project.width; val H = project.height
		val s = min(1.0, MAX.toDouble() / max(W, H)); val w = (W * s).roundToInt(); val h = (H * s).roundToInt(); val n = w * h
		val src = shrink(StudioImages.layer(project.source, W, H).full(), W, H, w, h)
		class L(val partId: String?, val px: IntArray)
		val layers = StudioRender.layers(project).filter { it.visible }.map { L(it.partId, shrink(it.layer.full(), W, H, w, h)) }
		val names = project.parts.associate { it.id to it.name }
		val motion = project.parts.associate { it.id to (StudioRig.rigName(project, it) ?: "").replace(Regex("-[lr]$|\\s\\d+$"), "") }
		val pairs = mutableMapOf<String, ByteArray>(); val dupes = mutableMapOf<String, ByteArray>(); val correct = mutableMapOf<String, Int>()
		val missing = ByteArray(n); val wrong = ByteArray(n)
		var checked = 0
		for (p in 0 until n) {
			if ((src[p] ushr 24) < 200) continue
			checked++
			var top = -1
			for (k in layers.indices.reversed()) if ((layers[k].px[p] ushr 24) >= 200) { top = k; break }
			val dTop = if (top >= 0) dist(layers[top].px[p], src[p]) else 999
			var best = -1; var bestD = 999
			for (k in top - 1 downTo 0) {
				if ((layers[k].px[p] ushr 24) < 200 || layers[k].partId == null) continue
				val d = dist(layers[k].px[p], src[p]); if (d < bestD) { bestD = d; best = k }
			}
			if (!(best >= 0 && bestD <= FAR && bestD + GAP < dTop)) {
				if (dTop > FAR * 1.5) { missing[p] = 1; wrong[p] = 1; continue }
				val topId = if (top >= 0) layers[top].partId else null
				if (topId != null) for (k in top - 1 downTo 0) {
					val kid = layers[k].partId ?: continue
					if ((layers[k].px[p] ushr 24) < 200) continue
					val d = dist(layers[k].px[p], src[p])
					if (d > dTop + GAP) correct["$topId>$kid"] = (correct["$topId>$kid"] ?: 0) + 1
					else if (d <= SAME && dTop <= SAME && motion[topId] != motion[kid]) dupes.getOrPut("$topId>$kid") { ByteArray(n) }[p] = 1
				}
				continue
			}
			wrong[p] = 1
			val topId = layers[top].partId ?: continue
			pairs.getOrPut("${layers[best].partId}>$topId") { ByteArray(n) }[p] = 1
		}
		val minPx = max(40.0, checked * 0.0015)
		fun full(b: IntArray) = intArrayOf(floor(b[0] / s).toInt(), floor(b[1] / s).toInt(), ceil(b[2] / s).toInt(), ceil(b[3] / s).toInt())
		val proposals = mutableListOf<Proposal>()
		for ((key, raw) in pairs) {
			val map = dropSmall(open(raw, w, h, 1), w, 30); val count = map.count { it.toInt() != 0 }
			if (count < minPx) continue
			val (winner, cover) = key.split(">")
			val conflict = correct["$cover>$winner"] ?: 0
			proposals += Proposal(key, if (conflict < count * 0.08) Kind.MOVE else Kind.SPLIT, winner, cover, names[winner] ?: "?", names[cover] ?: "?", "", "",
				(count / s / s).roundToInt(), full(boundsOf(map, w, h)!!), map, s, w, h)
		}
		for ((key, raw) in dupes) {
			val map = dropSmall(open(raw, w, h, 1), w, 30); val count = map.count { it.toInt() != 0 }
			if (count < minPx) continue
			val (upper, lower) = key.split(">")
			// The same drawing, not just the same colour: the lower copy repeats the drawing's linework.
			val lowerPx = layers.first { it.partId == lower }.px
			var shared = 0
			for (p in 0 until n) {
				if (map[p].toInt() == 0 || p % w == w - 1 || p + w >= n) continue
				val gs = max(dist(src[p], src[p + 1]), dist(src[p], src[p + w])); if (gs < 28) continue
				val gl = max(dist(lowerPx[p], lowerPx[p + 1]), dist(lowerPx[p], lowerPx[p + w])); if (gl >= 14) shared++
			}
			if (shared < 20 || shared.toDouble() / count < 0.02) continue
			proposals += Proposal("dupe:$key", Kind.OWNER, upper, lower, names[upper] ?: "?", names[lower] ?: "?", movesWith(motion[upper] ?: ""), movesWith(motion[lower] ?: ""),
				(count / s / s).roundToInt(), full(boundsOf(map, w, h)!!), map, s, w, h)
		}
		proposals.sortByDescending { it.pixels }
		val owners = proposals.filter { it.kind == Kind.OWNER }
		for (p in proposals) {
			if (p.kind == Kind.OWNER) continue
			val near = dilate(p.map, w, h, 6)
			owners.firstOrNull { o -> (o.a == p.a || o.a == p.b || o.b == p.a || o.b == p.b) && o.map.indices.any { o.map[it].toInt() != 0 && near[it].toInt() != 0 } }
				?.let { p.waitsFor = "${it.aName} / ${it.bName}" }
		}
		// The drawing dimmed, each problem tinted (red: wrong layer in front; blue: in two layers; yellow: in none).
		val clean = open(missing, w, h, 1)
		val small = IntArray(n); var missingCount = 0
		for (p in 0 until n) {
			val q = src[p]; val a = (q ushr 24) / 255.0
			val grey = (0.3 * ((q ushr 16) and 0xFF) + 0.59 * ((q ushr 8) and 0xFF) + 0.11 * (q and 0xFF)) * a + 255 * (1 - a)
			val v = (grey * 0.55 + 115).toInt().coerceIn(0, 255)
			small[p] = when {
				clean[p].toInt() != 0 -> { missingCount++; 0xFFF5BE1E.toInt() }
				wrong[p].toInt() != 0 && proposals.any { it.kind != Kind.OWNER && it.map[p].toInt() != 0 } -> 0xFFE6285A.toInt()
				proposals.any { it.kind == Kind.OWNER && it.map[p].toInt() != 0 } -> 0xFF3C8CF0.toInt()
				else -> (0xFF shl 24) or (v shl 16) or (v shl 8) or v
			}
		}
		val overlay = IntArray(W * H)
		for (y in 0 until H) { val sy = min(h - 1, (y * s).toInt()); for (x in 0 until W) overlay[y * W + x] = small[sy * w + min(w - 1, (x * s).toInt())] }
		return Result(proposals.filter { it.waitsFor == null }, proposals.filter { it.waitsFor != null }, overlay, (missingCount / s / s).roundToInt(), (checked / s / s).roundToInt())
	}

	/** The drawing with the proposal's region tinted blue, to show which piece is meant (whole canvas). */
	fun highlight(project: StudioProject, p: Proposal): IntArray {
		val W = project.width; val H = project.height
		val out = IntArray(W * H) { -1 }
		val src = StudioImages.layer(project.source, W, H).full()
		for (i in out.indices) { val q = src[i]; out[i] = StudioRender.blend(out[i], ((((q ushr 24) * 0.6).toInt()) shl 24) or (q and 0xFFFFFF)) }
		for (y in 0 until H) { val sy = min(p.h - 1, (y * p.scale).toInt()); for (x in 0 until W) if (p.map[sy * p.w + min(p.w - 1, (x * p.scale).toInt())].toInt() != 0) out[y * W + x] = StudioRender.blend(out[y * W + x], 0x96287FFF.toInt()) }
		return out
	}

	/** The part's art split by the proposal's region (grown a little, soft-edged, at full size): inside, outside. */
	private fun cut(project: StudioProject, part: StudioPart, p: Proposal): Pair<String, String> {
		val W = project.width; val H = project.height
		val region = dilate(p.map, p.w, p.h, 2)
		val alpha = IntArray(W * H)
		for (y in 0 until H) { val sy = min(p.h - 1, (y * p.scale).toInt()); for (x in 0 until W) if (region[sy * p.w + min(p.w - 1, (x * p.scale).toInt())].toInt() != 0) alpha[y * W + x] = 255 }
		StudioRender.blurAlpha(alpha, W, H, max(2, (2.4 / p.scale).roundToInt()))
		val art = StudioImages.layer(part.cutout, W, H).full()
		val inside = IntArray(W * H); val outside = IntArray(W * H)
		for (i in art.indices) {
			val q = art[i]; val a = q ushr 24; if (a == 0) continue
			val ina = (a * alpha[i] + 127) / 255
			inside[i] = (ina shl 24) or (q and 0xFFFFFF); outside[i] = ((a - ina) shl 24) or (q and 0xFFFFFF)
		}
		return pngDataUrl(encodePng(W, H, inside)) to pngDataUrl(encodePng(W, H, outside))
	}

	private fun peelStep(project: StudioProject, fix: String, owner: String?, names: List<String>) = JsonObject(buildMap {
		put("kind", JsonPrimitive("match")); put("fix", JsonPrimitive(fix)); if (owner != null) put("owner", JsonPrimitive(owner))
		put("parts", JsonArray(names.map { JsonPrimitive(it) })); put("at", JsonPrimitive(nowIso()))
	}).let { step -> JsonArray(((project.json["peels"] as? JsonArray) ?: JsonArray(emptyList())).plus(step).takeLast(200)) }

	class Applied(val project: StudioProject, val note: String, val select: String?)

	/** The project with a proposal applied. For a shared piece, [upperOwns] says which part keeps it. */
	fun apply(project: StudioProject, p: Proposal, upperOwns: Boolean = true): Applied {
		val parts = project.parts.toMutableList()
		fun idx(id: String) = parts.indexOfFirst { it.id == id }
		fun withArt(part: StudioPart, url: String, undo: JsonObject) = part.with(buildMap {
			put("cutout", JsonPrimitive(url)); if (part.extractionMask != null) put("extractionMask", JsonPrimitive(url)); put("peelUndo", undo)
		})
		fun erase(before: String?) = JsonObject(mapOf("kind" to JsonPrimitive("erase"), "before" to (before?.let { JsonPrimitive(it) } ?: JsonNull)))
		fun split(before: String?, id: String) = JsonObject(mapOf("kind" to JsonPrimitive("split"), "before" to (before?.let { JsonPrimitive(it) } ?: JsonNull), "createdId" to JsonPrimitive(id)))
		fun front(of: StudioPart, url: String, id: String) = StudioPart(JsonObject(of.json.toMutableMap().apply {
			put("id", JsonPrimitive(id)); put("name", JsonPrimitive("${of.name} Front")); put("cutout", JsonPrimitive(url))
			if (of.extractionMask != null) put("extractionMask", JsonPrimitive(url)) else remove("extractionMask")
			put("mask", JsonNull); put("repairs", JsonArray(emptyList())); remove("variants"); put("peelUndo", JsonNull)
		}))
		fun done(note: String, select: String?, fix: String, owner: String? = null) =
			Applied(project.with(mapOf("parts" to JsonArray(parts.map { it.json }), "peels" to peelStep(project, fix, owner, listOf(p.aName, p.bName)),
				"handoffChecks" to JsonObject(emptyMap())), note), note, select)
		if (p.kind == Kind.OWNER) {
			val ui = idx(p.a); val li = idx(p.b)
			if (ui < 0 || li < 0) throw IllegalStateException("Those parts have changed; compare again.")
			val lower = parts[li]
			if (upperOwns) {
				val (_, rest) = cut(project, lower, p)
				parts[li] = withArt(lower, rest, erase(lower.cutout))
				return done("Removed ${p.bName}'s copy of what ${p.aName} shows", null, "owner", "upper")
			}
			val upper = parts[ui]
			val (frontUrl, rest) = cut(project, lower, p); val (_, upperRest) = cut(project, upper, p)
			val id = uuid()
			parts[li] = withArt(lower, rest, split(lower.cutout, id)); parts[ui] = withArt(upper, upperRest, erase(upper.cutout))
			parts.add(ui + 1, front(lower, frontUrl, id))
			return done("Gave it to ${lower.name}: \"${lower.name} Front\" above ${upper.name}, ${upper.name}'s copy removed", id, "owner", "lower")
		}
		val wi = idx(p.a); val ci = idx(p.b)
		if (wi < 0 || ci < 0) throw IllegalStateException("Those parts have changed; compare again.")
		if (wi > ci) return Applied(project, "Already above.", null)
		if (p.kind == Kind.MOVE) {
			val moved = parts.removeAt(wi)
			parts.add(idx(p.b) + 1, moved)
			return done("Moved ${moved.name} above ${p.bName}", null, "move")
		}
		val winner = parts[wi]
		val (frontUrl, rest) = cut(project, winner, p)
		val id = uuid()
		parts[wi] = withArt(winner, rest, split(winner.cutout, id))
		parts.add(idx(p.b) + 1, front(winner, frontUrl, id))
		return done("Split ${winner.name} Front out of ${winner.name}, above ${p.bName}", id, "split")
	}
}
