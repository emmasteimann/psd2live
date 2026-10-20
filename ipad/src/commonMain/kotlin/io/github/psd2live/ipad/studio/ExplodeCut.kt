package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Explode into pieces (the Mac's `parts-sheet.py`): one paid image draws every piece of the character apart on green,
 * so they share a style; it is keyed and cut into numbered pieces here. Which piece is which part, and where it sits
 * on her, stay by hand (tap to map, then Fit by hand): generation and cutting are the automatic half, never the rest.
 */
object ExplodeCut {
	/** A part to ask for: its name, a one-line description, and whether it is hidden in the drawing (drawn underneath). */
	class Part(val name: String, val desc: String, val under: Boolean = false) {
		fun json() = JsonObject(mapOf("name" to JsonPrimitive(name), "desc" to JsonPrimitive(desc), "under" to JsonPrimitive(under)))
		companion object {
			fun of(o: JsonObject) = Part(o.str("name") ?: "", o.str("desc") ?: "", (o["under"] as? JsonPrimitive)?.booleanOrNull == true)
		}
	}

	/** A cut piece: its number, its box on the sheet (x0, y0, x1, y1) and its pixels (box-sized, straight ARGB). */
	class Piece(val n: Int, val box: IntArray, val argb: IntArray) {
		val w get() = box[2] - box[0]
		val h get() = box[3] - box[1]
	}

	class Cut(val width: Int, val height: Int, val keyed: IntArray, val pieces: List<Piece>)

	/** The parts the list starts from: the project's own part names (one line each, to describe), else a starter set. */
	fun starter(project: StudioProject): List<Part> {
		val names = project.parts.map { it.name.trim() }.filter { it.isNotEmpty() && !it.equals("Remaining artwork", true) }.distinct()
		return if (names.size >= 3) names.map { Part(it, it.lowercase()) } else listOf(
			Part("Front hair", "the front hair and bangs"), Part("Back hair", "the hair behind the head"), Part("Head", "the face and head, with the neck"),
			Part("Body", "the body and clothes"), Part("Left arm", "the arm on the image's left"), Part("Right arm", "the arm on the image's right"),
			Part("Neck", "the neck, long enough to stay covered when the head tilts", under = true))
	}

	fun parts(project: StudioProject): List<Part> =
		(project.json["explode"] as? JsonObject)?.get("parts")?.let { it as? JsonArray }?.mapNotNull { (it as? JsonObject)?.let(Part::of) } ?: starter(project)

	fun withParts(project: StudioProject, parts: List<Part>): StudioProject =
		project.with(mapOf("explode" to JsonObject(((project.json["explode"] as? JsonObject) ?: JsonObject(emptyMap())) + ("parts" to JsonArray(parts.map { it.json() })))))

	/** The sheet generated earlier (a PNG data URL), so re-cutting costs nothing. */
	fun sheet(project: StudioProject): String? = (project.json["explode"] as? JsonObject)?.str("sheet")

	fun withSheet(project: StudioProject, png: ByteArray): StudioProject =
		project.with(mapOf("explode" to JsonObject(((project.json["explode"] as? JsonObject) ?: JsonObject(emptyMap())) + ("sheet" to JsonPrimitive(pngDataUrl(png))))), "Generated a parts sheet")

	/** The Mac's prompt, word for word, so both ask alike. */
	fun prompt(parts: List<Part>): String {
		val outer = parts.filter { !it.under }.map { it.desc.ifBlank { it.name } }
		val under = parts.filter { it.under }.map { it.desc.ifBlank { it.name } }
		return "Make a character PARTS SHEET for rigging this exact character, like an exploded view of a Live2D model. " +
			"Draw each part below SEPARATELY, whole and complete, not overlapping any other part, with clear space " +
			"between parts, all at the same scale as in the reference drawing. Match the reference's art style, line " +
			"weight, colours and shading exactly. Draw every part whole, including the areas other parts cover in the " +
			"drawing. No text, no labels, no arrows. Flat pure bright green (#00FF00) background everywhere else.\n\n" +
			"Top rows, the visible parts, left to right:\n" + outer.mapIndexed { i, d -> "${i + 1}. $d" }.joinToString("\n") +
			(if (under.isEmpty()) "" else "\n\nBottom row, the UNDERNEATH parts that are hidden in the drawing but needed when things move:\n" +
				under.mapIndexed { i, d -> "${i + 1 + outer.size}. $d" }.joinToString("\n"))
	}

	/** The drawing over white, as the generator is shown it (transparent areas would read as black). */
	fun onWhite(argb: IntArray): IntArray = IntArray(argb.size) { StudioRender.blend(0xFFFFFFFF.toInt(), argb[it]) }

	/**
	 * Keys the green and splits the sheet into pieces: connected shapes after closing gaps up to [join] px (a tassel's
	 * threads, a cord), pieces over [minArea] px (a hair clip or thin glasses are real parts), numbered row by row.
	 */
	fun cut(sheet: Pixels, join: Int = 9, minArea: Int = 200): Cut {
		val w = sheet.width; val h = sheet.height
		val keyed = StudioRedraw.keyGreen(sheet.argb, w, h)
		val alpha = ByteArray(w * h) { if ((keyed[it] ushr 24) > 40) 1 else 0 }
		val joined = if (join > 1) StudioMatch.dilate(alpha, w, h, join / 2) else alpha
		val labels = IntArray(w * h); val queue = IntArray(w * h)
		class Blob(val label: Int, var area: Int = 0, var x0: Int = Int.MAX_VALUE, var y0: Int = Int.MAX_VALUE, var x1: Int = -1, var y1: Int = -1)
		val blobs = mutableListOf<Blob>()
		for (start in 0 until w * h) {
			if (joined[start].toInt() == 0 || labels[start] != 0) continue
			val b = Blob(blobs.size + 1); blobs += b
			var head = 0; var tail = 0; queue[tail++] = start; labels[start] = b.label
			while (head < tail) {
				val i = queue[head++]; val x = i % w; val y = i / w
				b.area++
				if (alpha[i].toInt() != 0) { b.x0 = min(b.x0, x); b.y0 = min(b.y0, y); b.x1 = max(b.x1, x + 1); b.y1 = max(b.y1, y + 1) }  // its own pixels, not the joining margin
				for (dy in -1..1) for (dx in -1..1) {
					val nx = x + dx; val ny = y + dy
					if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
					val j = ny * w + nx
					if (joined[j].toInt() != 0 && labels[j] == 0) { labels[j] = b.label; queue[tail++] = j }
				}
			}
		}
		val kept = blobs.filter { it.area > minArea && it.x1 > 0 }.sortedWith(compareBy({ (it.y0 / 220.0).roundToInt() }, { it.x0 }))
		val pieces = kept.mapIndexed { k, b ->
			val bw = b.x1 - b.x0; val bh = b.y1 - b.y0
			Piece(k, intArrayOf(b.x0, b.y0, b.x1, b.y1), IntArray(bw * bh) { i ->
				val j = (b.y0 + i / bw) * w + b.x0 + i % bw
				if (labels[j] == b.label && alpha[j].toInt() != 0) keyed[j] else keyed[j] and 0x00FFFFFF
			})
		}
		return Cut(w, h, keyed, pieces)
	}

	/**
	 * A piece that touched its neighbour on the sheet (so it cut as one) split at its emptiest column (or row), looked
	 * for in the middle 20-80 %. The halves are numbered after the last piece.
	 */
	fun split(cut: Cut, n: Int, rows: Boolean = false): Cut {
		val p = cut.pieces.first { it.n == n }
		val len = if (rows) p.h else p.w
		val counts = IntArray(len) { k -> (0 until if (rows) p.w else p.h).count { m -> (p.argb[if (rows) k * p.w + m else m * p.w + k] ushr 24) > 128 } }
		val lo = (len * 0.2).toInt(); val hi = max(lo + 1, (len * 0.8).toInt())
		val at = (lo until hi).minBy { counts[it] }
		val next = cut.pieces.maxOf { it.n } + 1
		fun part(x0: Int, y0: Int, x1: Int, y1: Int, k: Int) = Piece(next + k, intArrayOf(p.box[0] + x0, p.box[1] + y0, p.box[0] + x1, p.box[1] + y1),
			IntArray((x1 - x0) * (y1 - y0)) { i -> p.argb[(y0 + i / (x1 - x0)) * p.w + x0 + i % (x1 - x0)] })
		val halves = if (rows) listOf(part(0, 0, p.w, at, 0), part(0, at, p.w, p.h, 1)) else listOf(part(0, 0, at, p.h, 0), part(at, 0, p.w, p.h, 1))
		return Cut(cut.width, cut.height, cut.keyed, cut.pieces.filter { it.n != n } + halves)
	}

	/** The pieces [nums] together as one picture, where they sat on the sheet, cropped to what they cover: (argb, w, h). */
	fun combine(cut: Cut, nums: List<Int>): Triple<IntArray, Int, Int>? {
		val chosen = cut.pieces.filter { it.n in nums }.takeIf { it.isNotEmpty() } ?: return null
		val x0 = chosen.minOf { it.box[0] }; val y0 = chosen.minOf { it.box[1] }
		val w = chosen.maxOf { it.box[2] } - x0; val h = chosen.maxOf { it.box[3] } - y0
		val out = IntArray(w * h)
		for (p in chosen) for (i in p.argb.indices) {
			val o = (p.box[1] - y0 + i / p.w) * w + p.box[0] - x0 + i % p.w
			out[o] = StudioRender.blend(out[o], p.argb[i])
		}
		return Triple(out, w, h)
	}

	/** Where [argb]'s visible pixels are (x0, y0, x1, y1), or null when none are. */
	fun bounds(argb: IntArray, w: Int, h: Int): IntArray? {
		var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
		for (y in 0 until h) for (x in 0 until w) if ((argb[y * w + x] ushr 24) > 128) { x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y) }
		return if (x1 < 0) null else intArrayOf(x0, y0, x1 + 1, y1 + 1)
	}

	/**
	 * The rough placement (the bench's `place`): [piece] scaled, keeping its shape, and centred in [box] on a
	 * [cw] x [ch] canvas. The generator re-frames pieces, so this only starts near the answer; Fit by hand finishes it.
	 */
	fun place(piece: IntArray, pw: Int, ph: Int, box: IntArray, cw: Int, ch: Int): IntArray {
		val out = IntArray(cw * ch)
		val b = bounds(piece, pw, ph) ?: return out
		val bw = b[2] - b[0]; val bh = b[3] - b[1]
		val sc = 0.5 * ((box[2] - box[0]).toDouble() / bw + (box[3] - box[1]).toDouble() / bh)
		val ow = max(1, (pw * sc).roundToInt()); val oh = max(1, (ph * sc).roundToInt())
		val scaled = StudioAI.resample(piece, pw, ph, 0, 0, pw, ph, ow, oh)
		val ox = ((box[0] + box[2]) / 2.0 - sc * (b[0] + b[2]) / 2.0).roundToInt()
		val oy = ((box[1] + box[3]) / 2.0 - sc * (b[1] + b[3]) / 2.0).roundToInt()
		for (y in 0 until oh) {
			val ty = oy + y; if (ty < 0 || ty >= ch) continue
			for (x in 0 until ow) { val tx = ox + x; if (tx in 0 until cw) out[ty * cw + tx] = scaled[y * ow + x] }
		}
		return out
	}

	/** A box for [name]: the outline of the project's part with that name, when it has art; null when she must draw one. */
	fun boxFor(project: StudioProject, name: String): IntArray? {
		val part = project.parts.firstOrNull { it.name.equals(name.trim(), true) && it.cutout != null } ?: return null
		val px = decodeImage(dataUrlBytes(part.cutout!!)) ?: return null
		return bounds(px.argb, px.width, px.height)
	}

	/** The placed pieces as new parts, each right above the part it stands for (or on top), visible, ready to Fit by hand. */
	fun addParts(project: StudioProject, placed: List<Pair<String, IntArray>>): StudioProject {
		val parts = project.parts.toMutableList()
		for ((name, argb) in placed) {
			val part = StudioPart.blank(name, project.parts.firstOrNull { it.name.equals(name, true) }?.group ?: "Custom")
				.with(mapOf("cutout" to JsonPrimitive(pngDataUrl(encodePng(project.width, project.height, argb))), "imported" to JsonPrimitive(true),
					"explodedFrom" to JsonPrimitive(name)))
			val at = parts.indexOfLast { it.name.equals(name, true) }
			if (at >= 0) parts.add(at + 1, part) else parts.add(part)
		}
		return project.withParts(parts, "Exploded ${placed.size} piece${if (placed.size == 1) "" else "s"} onto the drawing")
	}
}
