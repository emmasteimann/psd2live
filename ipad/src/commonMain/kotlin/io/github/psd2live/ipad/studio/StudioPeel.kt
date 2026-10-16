package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Peel and Split apart, the web studio's `peel.js` and `split.js`. Peel's Split lifts the marked piece of a part into
 * a new part right above it (a soft mask edge shares pixels between the two, so nothing along it is lost); Split apart
 * makes one part per separate island of a piece (a pair of earrings, two ribbon loops). Both keep every image a
 * full-canvas PNG in its place, as the web does.
 */
object StudioPeel {
	/** The part's own art as it exports: its cutout with any hidden-art patches aimed at it underneath. */
	fun partArt(project: StudioProject, partId: String): IntArray? =
		StudioRender.layers(project, includeHidden = true).firstOrNull { it.partId == partId }?.layer?.full()

	class Cut(val lifted: String, val rest: String)

	/**
	 * Split without repainting: the part's art under its mask (by the mask's alpha) becomes the lifted piece, the rest
	 * stays. Throws a message the screen can show when there's nothing to lift.
	 */
	fun cut(project: StudioProject, partId: String): Cut {
		val w = project.width; val h = project.height
		val part = project.part(partId) ?: throw IllegalStateException("That part is gone.")
		val maskUrl = part.mask ?: throw IllegalStateException("Mark the piece to lift off first (Brush, Polygon or Edge brush).")
		val art = partArt(project, partId) ?: throw IllegalStateException("This part has no artwork yet.")
		val mask = StudioImages.layer(maskUrl, w, h)
		val lifted = IntArray(w * h); val rest = art.copyOf()
		var any = false
		for (row in 0 until mask.height) for (col in 0 until mask.width) {
			val ma = mask.argb[row * mask.width + col] ushr 24
			if (ma == 0) continue
			val i = (mask.y + row) * w + mask.x + col
			val p = art[i]; val a = p ushr 24
			if (a == 0) continue
			if (ma >= 128) any = true
			val up = (a * ma + 127) / 255
			lifted[i] = (up shl 24) or (p and 0xFFFFFF)
			rest[i] = ((a - up) shl 24) or (p and 0xFFFFFF)
		}
		if (!any) throw IllegalStateException("The mask doesn't cover any of this part's artwork.")
		return Cut(pngDataUrl(encodePng(w, h, lifted)), pngDataUrl(encodePng(w, h, rest)))
	}

	/** The project after a split: the lifted piece just above its part, the part keeping the rest and how to undo. */
	fun applySplit(project: StudioProject, partId: String, cut: Cut, name: String): Pair<StudioProject, String> {
		val part = project.part(partId) ?: return project to partId
		val id = uuid()
		val piece = StudioPart(JsonObject(mapOf(
			"id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "group" to JsonPrimitive(part.group), "visible" to JsonPrimitive(true),
			"mask" to JsonNull, "repairs" to JsonArray(emptyList()), "cutout" to JsonPrimitive(cut.lifted), "extractionMask" to JsonPrimitive(cut.lifted),
			"imported" to JsonPrimitive(true),
		)))
		val undo = JsonObject(mapOf("kind" to JsonPrimitive("split"), "before" to (part.cutout?.let { JsonPrimitive(it) } ?: JsonNull), "createdId" to JsonPrimitive(id)))
		val parts = project.parts.flatMap {
			if (it.id != partId) listOf(it)
			else listOf(it.with(mapOf("cutout" to JsonPrimitive(cut.rest), "mask" to JsonNull, "candidate" to JsonNull, "peelUndo" to undo)), piece)
		}
		val step = JsonObject(mapOf("kind" to JsonPrimitive("split"), "from" to JsonPrimitive(part.name), "name" to JsonPrimitive(name),
			"mask" to (part.mask?.let { JsonPrimitive(it) } ?: JsonNull), "at" to JsonPrimitive(nowIso())))
		val peels = ((project.json["peels"] as? JsonArray) ?: JsonArray(emptyList())).plus(step).takeLast(200)
		val next = project.with(mapOf("parts" to JsonArray(parts.map { it.json }), "peels" to JsonArray(peels), "handoffChecks" to JsonObject(emptyMap())),
			"Peeled \"$name\" off ${part.name}", partId)
		return next to id
	}

	/** "Undo last peel": the part's art as it was, and a split's lifted piece removed. */
	fun undo(project: StudioProject, partId: String): StudioProject {
		val part = project.part(partId) ?: return project
		val u = part.json["peelUndo"] as? JsonObject ?: throw IllegalStateException("Nothing to undo on this part.")
		val before = u.str("before")
		val created = if (u.str("kind") == "split") u.str("createdId") else null
		val parts = project.parts.filter { it.id != created }.map {
			if (it.id != partId) it
			else StudioPart(JsonObject(it.json.toMutableMap().apply { if (before != null) put("cutout", JsonPrimitive(before)) else remove("cutout"); put("peelUndo", JsonNull) }))
		}
		return project.withParts(parts, "Undid last peel", partId)
	}

	// ---- Split apart ----

	private const val CELL = 4

	/** A piece's islands, found on a 4 px grid: [label] per cell, [owner] the kept island each label goes to. */
	class Islands(val w: Int, val h: Int, val gw: Int, val gh: Int, val ink: IntArray, val label: IntArray, val owner: IntArray, val pieces: List<Piece>, val art: IntArray)
	class Piece(val cx: Double, val cy: Double, val share: Double)

	/**
	 * The separate islands of [art] (the whole canvas): bits within [gap] px are one island (the thin links of a dangly
	 * earring); islands under [minShare] of the ink join the nearest kept one, so nothing is lost.
	 */
	fun findIslands(art: IntArray, w: Int, h: Int, gap: Int = 12, minShare: Double = 0.02): Islands {
		val gw = (w + CELL - 1) / CELL; val gh = (h + CELL - 1) / CELL
		val ink = IntArray(gw * gh)
		for (y in 0 until h) for (x in 0 until w) if ((art[y * w + x] ushr 24) > 16) ink[(y / CELL) * gw + x / CELL]++
		val r = maxOf(0, kotlin.math.round(gap.toDouble() / CELL).toInt())
		val rowHit = BooleanArray(gw * gh); val hit = BooleanArray(gw * gh)
		for (y in 0 until gh) for (x in 0 until gw) { if (ink[y * gw + x] == 0) continue; for (dx in maxOf(0, x - r)..minOf(gw - 1, x + r)) rowHit[y * gw + dx] = true }
		for (y in 0 until gh) for (x in 0 until gw) { if (!rowHit[y * gw + x]) continue; for (dy in maxOf(0, y - r)..minOf(gh - 1, y + r)) hit[dy * gw + x] = true }
		val label = IntArray(gw * gh) { -1 }
		class Isle(val id: Int) { var px = 0L; var sx = 0.0; var sy = 0.0 }
		val isles = ArrayList<Isle>()
		val stack = IntArray(gw * gh)
		for (start in 0 until gw * gh) {
			if (!hit[start] || label[start] >= 0) continue
			val isle = Isle(isles.size); var top = 0
			label[start] = isle.id; stack[top++] = start
			while (top > 0) {
				val c = stack[--top]; val cx = c % gw; val cy = c / gw
				if (ink[c] > 0) { isle.px += ink[c]; isle.sx += cx.toDouble() * ink[c]; isle.sy += cy.toDouble() * ink[c] }
				for (dy in -1..1) for (dx in -1..1) {
					val nx = cx + dx; val ny = cy + dy
					if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
					val n = ny * gw + nx
					if (hit[n] && label[n] < 0) { label[n] = isle.id; stack[top++] = n }
				}
			}
			isles += isle
		}
		val total = isles.sumOf { it.px }.coerceAtLeast(1).toDouble()
		val big = isles.filter { it.px / total >= minShare }
		val keep = if (big.isNotEmpty()) big else isles.sortedByDescending { it.px }.take(1)
		val owner = IntArray(isles.size)
		for (i in isles) {
			val k = keep.indexOf(i)
			if (k >= 0) { owner[i.id] = k; continue }
			val cx = i.sx / maxOf(1L, i.px); val cy = i.sy / maxOf(1L, i.px)
			owner[i.id] = keep.indices.minByOrNull { j -> val kk = keep[j]; val kx = kk.sx / kk.px - cx; val ky = kk.sy / kk.px - cy; kx * kx + ky * ky } ?: 0
		}
		return Islands(w, h, gw, gh, ink, label, owner, keep.map { Piece(it.sx / it.px * CELL, it.sy / it.px * CELL, it.px / total) }, art)
	}

	/** One full-canvas image per island, holding only its pixels. */
	fun pieceImages(f: Islands): List<IntArray> = f.pieces.indices.map { j ->
		val out = IntArray(f.w * f.h)
		for (y in 0 until f.h) for (x in 0 until f.w) {
			val p = f.art[y * f.w + x]; if ((p ushr 24) == 0) continue
			val l = f.label[(y / CELL) * f.gw + x / CELL]
			if (l >= 0 && f.owner[l] == j) out[y * f.w + x] = p
		}
		out
	}

	/** Two pieces side by side are (right)/(left) as the character sees it; more are numbered left to right on screen. */
	fun splitNames(name: String, pieces: List<Piece>): List<String> {
		val base = name.replace(Regex("\\s*\\((left|right)\\)\\s*$", RegexOption.IGNORE_CASE), "").replace(Regex("^(left|right)\\s+", RegexOption.IGNORE_CASE), "").trim().ifEmpty { name }
		val order = pieces.indices.sortedBy { pieces[it].cx }
		val names = Array(pieces.size) { "" }
		if (pieces.size == 2) { names[order[0]] = "$base (right)"; names[order[1]] = "$base (left)" }
		else order.forEachIndexed { k, i -> names[i] = "$base ${k + 1}" }
		return names.toList()
	}

	val tints = listOf(0xFFFF5C8A, 0xFF4FC3F7, 0xFFFFD54F, 0xFF81C784, 0xFFBA68C8, 0xFFFF8A65, 0xFF4DB6AC, 0xFFF06292).map { it.toInt() }

	/** A small picture of the piece, one pixel per grid cell, each island in its own colour (cropped to the ink). */
	fun preview(f: Islands): Pixels? {
		var x0 = f.gw; var y0 = f.gh; var x1 = -1; var y1 = -1
		for (c in 0 until f.gw * f.gh) if (f.ink[c] > 0) { val x = c % f.gw; val y = c / f.gw; if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y }
		if (x1 < 0) return null
		val w = x1 - x0 + 1; val h = y1 - y0 + 1
		val out = IntArray(w * h)
		for (y in 0 until h) for (x in 0 until w) {
			val c = (y + y0) * f.gw + x + x0
			if (f.ink[c] == 0 || f.label[c] < 0) continue
			out[y * w + x] = tints[f.owner[f.label[c]] % tints.size]
		}
		return Pixels(w, h, out)
	}

	/**
	 * The part replaced by its pieces in the same place in the stack. The first keeps the part's id (glue and settings
	 * pointing at it keep working); each starts a fresh fit baseline and drops trims and mask.
	 */
	fun applySplitApart(project: StudioProject, partId: String, pieces: List<Pair<String, String>>): Pair<StudioProject, List<String>> {
		val base = project.part(partId) ?: return project to emptyList()
		val ids = pieces.indices.map { if (it == 0) base.id else uuid() }
		val made = pieces.mapIndexed { k, (url, name) ->
			base.with(mapOf<String, JsonElement?>("id" to JsonPrimitive(ids[k]), "name" to JsonPrimitive(name), "cutout" to JsonPrimitive(url),
				"extractionMask" to JsonPrimitive(url), "fitOriginal" to JsonPrimitive(url), "trimBase" to JsonNull, "trimMask" to JsonNull, "mask" to JsonNull,
				"repairs" to if (k == 0) (base.json["repairs"] ?: JsonArray(emptyList())) else JsonArray(emptyList())))
		}
		val at = project.parts.indexOfFirst { it.id == partId }
		val parts = project.parts.subList(0, at) + made + project.parts.subList(at + 1, project.parts.size)
		val next = project.with(mapOf("parts" to JsonArray(parts.map { it.json }), "handoffChecks" to JsonObject(emptyMap())), "Split ${base.name} into ${pieces.size}", partId)
		return next to ids
	}

	/** "Join back": the pieces of the last split replaced by the part as it was. */
	fun joinBack(project: StudioProject, before: StudioPart, ids: List<String>): StudioProject {
		val first = project.parts.indexOfFirst { it.id in ids }
		val parts = if (first < 0) project.parts + before
			else project.parts.filterIndexed { i, p -> i == first || p.id !in ids }.map { if (it.id in ids) before else it }
		return project.with(mapOf("parts" to JsonArray(parts.map { it.json }), "handoffChecks" to JsonObject(emptyMap())), "Joined ${before.name} back together", before.id)
	}
}
