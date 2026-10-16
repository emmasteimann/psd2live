package io.github.psd2live.ipad.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Glue (the web's `hairAnchor`): fractions across the piece's box; the glued end moves exactly with [target]. */
data class Glue(val from: Double, val to: Double, val pinTop: Boolean, val axisX: Boolean, val target: String?) {
	fun json(): JsonObject = JsonObject(buildMap {
		put("from", JsonPrimitive(from)); put("to", JsonPrimitive(to)); put("pinTop", JsonPrimitive(pinTop))
		if (axisX) put("axis", JsonPrimitive("x")); if (target != null) put("target", JsonPrimitive(target))
	})

	companion object {
		fun of(e: JsonElement?): Glue? = (e as? JsonObject)?.let {
			Glue(it.num("from") ?: 0.0, it.num("to") ?: 0.0, (it["pinTop"] as? JsonPrimitive)?.booleanOrNull ?: true, it.str("axis") == "x", it.str("target"))
		}
	}
}

/** Swing: where the piece hangs from (a fraction in from [edge]) and how its pendulum feels - the web's `swing`. */
data class Swing(val pivot: Double, val preset: String, val side: Double, val updown: Double, val softness: Double, val length: Double,
	val edge: String? = null, val tilt: Double? = null) {
	fun json(): JsonObject = JsonObject(buildMap {
		put("pivot", JsonPrimitive(pivot)); put("preset", JsonPrimitive(preset)); put("side", JsonPrimitive(side)); put("updown", JsonPrimitive(updown))
		put("softness", JsonPrimitive(softness)); put("length", JsonPrimitive(length))
		if (edge != null) put("edge", JsonPrimitive(edge)); if (tilt != null) put("tilt", JsonPrimitive(tilt))
	})

	companion object {
		fun of(e: JsonElement?): Swing? = (e as? JsonObject)?.let {
			Swing(it.num("pivot") ?: 0.0, it.str("preset") ?: "hair", it.num("side") ?: 0.22, it.num("updown") ?: 0.0, it.num("softness") ?: 0.6,
				it.num("length") ?: 0.0, it.str("edge"), it.num("tilt"))
		}

		/** A swing's starting values: pivot at the top, the preset's feel (PSD2Live SwingPresets). */
		fun fresh(rigAs: String): Swing {
			val accessory = Regex("^(objects|neckwear|earwear|headwear)").containsMatchIn(rigAs)
			val cloth = "wear" in rigAs && !accessory
			val preset = if (accessory) "accessory" else if (cloth) "cloth" else "hair"
			return Swing(0.0, preset, mapOf("hair" to 0.22, "accessory" to 0.3, "cloth" to 0.16)[preset]!!, 0.0,
				mapOf("hair" to 0.6, "accessory" to 0.1, "cloth" to 0.8)[preset]!!, 0.0)
		}
	}
}

private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

/**
 * One piece being fitted by hand (the web's `FitMode`): its full art ([base]) with a trim mask over it (nothing
 * trimmed is lost - Restore paints the mask away, now or in a later session), a warp cage over its box, and the
 * "copy from drawing" mask in canvas space. Pixel work happens off the main thread (one worker); the cage and the
 * settings are Compose state, changed on the main thread. Nothing changes the project until [bake] is applied.
 */
class FitSession private constructor(val project: StudioProject, val part: StudioPart, val w: Int, val h: Int,
	var base: IntArray, val trim: ByteArray, val drawing: IntArray, val below: IntArray, val above: IntArray, startBox: Box) {

	/** The piece as shown and saved: its art with the trim mask cut out. */
	var piece: IntArray = IntArray(w * h); private set
	/** Where the drawing's own pixels replace the piece's paint (canvas space). */
	val copy = ByteArray(w * h)

	var box by mutableStateOf(startBox)
	var grid by mutableStateOf(4); private set
	var dst by mutableStateOf(FitGeometry.gridPoints(startBox, 4, 4))
	val src: List<Pt> get() = FitGeometry.gridPoints(box, grid, grid)
	/** Bumped when pixels change (the piece, a mask), so the screen redraws. */
	var version by mutableStateOf(0L)
	var trimmed by mutableStateOf(false)
	var copyUsed by mutableStateOf(false)
	var glue by mutableStateOf(Glue.of(part.json["hairAnchor"]))
	var swing by mutableStateOf(Swing.of(part.json["swing"]))
	private val history = ArrayList<Undo>()
	var undoCount by mutableStateOf(0); private set

	private sealed class Undo { class Cage(val dst: List<Pt>) : Undo(); class Trim(val mask: ByteArray) : Undo() }

	init { refreshPiece() }

	val cageMoved: Boolean get() = dst.size == src.size && dst.indices.any { kotlin.math.abs(dst[it].x - src[it].x) > 0.01 || kotlin.math.abs(dst[it].y - src[it].y) > 0.01 }
	val anchorChanged: Boolean get() = glue?.json() != (part.json["hairAnchor"] as? JsonObject)?.let { Glue.of(it)?.json() } ||
		swing?.json() != (part.json["swing"] as? JsonObject)?.let { Swing.of(it)?.json() }
	val dirty: Boolean get() = undoCount > 0 || copyUsed || trimmed || cageMoved || anchorChanged

	/** Worker: the piece from its art and the trim mask. */
	fun refreshPiece() {
		val out = IntArray(w * h)
		for (i in out.indices) {
			val p = base[i]; val m = trim[i].toInt() and 0xFF
			out[i] = if (m == 0) p else (((p ushr 24) * (255 - m) / 255) shl 24) or (p and 0xFFFFFF)
		}
		piece = out
	}

	// ---- The cage (main thread) ----

	fun pushCage() { history += Undo.Cage(dst); trimHistory(); undoCount = history.size }
	fun pushTrim() { history += Undo.Trim(trim.copyOf()); trimHistory(); undoCount = history.size }
	private fun trimHistory() {
		while (history.size > 60) history.removeAt(0)
		// Mask snapshots are big: keep the last 20.
		while (history.count { it is Undo.Trim } > 20) history.removeAt(history.indexOfFirst { it is Undo.Trim })
	}

	/** The cage on an n×n grid, keeping the fit; warp undo steps belong to the old cage. */
	fun changeGrid(n: Int): List<Pt> {
		if (n == grid) return dst
		val pts = if (dst.size == (grid + 1) * (grid + 1)) FitGeometry.resampleCage(box, grid, dst, n) else FitGeometry.gridPoints(box, n, n)
		history.removeAll { it is Undo.Cage }; undoCount = history.size
		grid = n; dst = pts
		return pts
	}

	fun nudge(dx: Double = 0.0, dy: Double = 0.0, scale: Double = 1.0, degrees: Double = 0.0) {
		pushCage(); dst = FitGeometry.transformPoints(dst, dx, dy, scale, degrees, FitGeometry.centroid(dst))
	}

	/** Mirror left-right ([horizontal]) or flip upside down, about the middle of the box. */
	fun mirror(horizontal: Boolean) {
		pushCage()
		val b = FitGeometry.bounds(dst); val cx = b.x0 + b.x1; val cy = b.y0 + b.y1
		dst = dst.map { if (horizontal) Pt(cx - it.x, it.y) else Pt(it.x, cy - it.y) }
	}

	fun reset() { pushCage(); dst = src }

	/** Undoes one step: a cage move on the main thread; a trim needs [refreshPiece] after (returns true). */
	fun undo(): Boolean {
		val last = history.removeLastOrNull() ?: return false
		undoCount = history.size
		return when (last) {
			is Undo.Cage -> { if (last.dst.size == dst.size) dst = last.dst; false }
			is Undo.Trim -> { last.mask.copyInto(trim); true }
		}
	}

	// ---- Pixels (worker) ----

	/** The piece warped by the cage at 1:1 over the whole canvas. */
	fun warped(img: IntArray = piece): IntArray =
		FitRaster.warp(img, w, h, src, dst, grid, 0.0, 0.0, w.toDouble(), h.toDouble(), w, h)

	/**
	 * Settles the current fit into the art (as the web does before trimming, cleaning or finding specks), so a trim
	 * stays attached to the piece wherever it's moved afterwards. Returns the new box and cage for the main thread.
	 */
	fun settle(): Pair<Box, List<Pt>>? {
		if (!cageMoved) return null
		base = warped(base)
		val maskWarped = FitRaster.warp(FitRaster.tinted(trim, 0xFF2D55), w, h, src, dst, grid, 0.0, 0.0, w.toDouble(), h.toDouble(), w, h)
		for (i in trim.indices) trim[i] = (maskWarped[i] ushr 24).toByte()
		refreshPiece()
		val nb = FitGeometry.alphaBox(base, w, h) ?: box
		return nb to FitGeometry.gridPoints(nb, grid, grid)
	}

	/** Main thread: take a settled box and cage (warp undo steps belong to the old cage). */
	fun settled(result: Pair<Box, List<Pt>>?) {
		if (result == null) return
		history.removeAll { it is Undo.Cage }; undoCount = history.size
		box = result.first; dst = result.second; trimmed = true; version++
	}

	/** A trim / restore stroke into the mask over the art. */
	fun trimStroke(a: Pt, b: Pt, size: Double, feather: Double, restore: Boolean) {
		FitRaster.stroke(trim, w, h, a, b, size, feather, erase = restore) ?: return
		refreshPiece()
	}

	/** A "copy from drawing" (or uncopy) stroke. */
	fun copyStroke(a: Pt, b: Pt, size: Double, feather: Double, erase: Boolean) { FitRaster.stroke(copy, w, h, a, b, size, feather, erase) }

	fun clearTrims() { trim.fill(0); refreshPiece() }

	/** The wand's selection trimmed away (into the mask). */
	fun trimSelection(sel: ByteArray) { for (i in sel.indices) if (sel[i].toInt() != 0) trim[i] = 255.toByte(); refreshPiece() }

	fun removeSpecks(s: FitGeometry.Specks) { for (i in s.remove.indices) if (s.remove[i].toInt() != 0) trim[i] = 255.toByte(); refreshPiece() }

	/** Worker: the piece's full art cleaned up (the trim mask stays over it). */
	fun cleanUp(progress: (Int, Int) -> Unit): Box? {
		val out = StudioUpscale.upscale(base, w, h, 1.0, progress)
		val before = base.count { (it ushr 24) > 8 }; val after = out.count { (it ushr 24) > 8 }
		// A model that answers nothing (or wipes most of the piece) must not replace the art.
		if (after < before / 2) throw IllegalStateException("Clean up went wrong (it kept $after of $before pixels); the piece is unchanged. [${AnimeUpscaler.lastInfo}]")
		base = out; refreshPiece(); return FitGeometry.alphaBox(base, w, h)
	}

	/** Worker: new art for the piece (a redraw already carries its trims in its outline): a clean trim mask over it. */
	fun replaceArt(art: IntArray): Box? { base = art; trim.fill(0); refreshPiece(); return FitGeometry.alphaBox(base, w, h) }

	/** Back to the piece as it was before its first fit (its trims cleared). Returns the new box. */
	fun startOver(): Box? {
		val original = part.json.str("fitOriginal") ?: return null
		base = StudioImages.layer(original, w, h).full(); trim.fill(0); refreshPiece()
		return FitGeometry.alphaBox(base, w, h) ?: box
	}

	/** Main thread, after [startOver] or a new art: a fresh cage over [b]. */
	fun newBox(b: Box) { history.removeAll { it is Undo.Cage }; undoCount = history.size; box = b; dst = FitGeometry.gridPoints(b, grid, grid); trimmed = true; version++ }

	/** Snap: the move / turn / scale that lays the fitted piece's outline on the drawing's lines. */
	fun snap(from: List<Pt>): Pair<List<Pt>, FitGeometry.Snap> {
		val key = "${project.id}:${project.source.length}"
		if (edgeKey != key) { edgeDist = FitGeometry.distanceMap(FitGeometry.edgeMask(drawing, w, h), w, h); edgeKey = key }
		val fitted = FitRaster.warp(piece, w, h, src, from, grid, 0.0, 0.0, w.toDouble(), h.toDouble(), w, h)
		val pts = FitGeometry.outlinePoints(fitted, w, h)
		val centre = FitGeometry.centroid(from)
		val s = FitGeometry.snapToLines(pts, edgeDist!!, w, h, centre)
		return FitGeometry.transformPoints(from, s.change.dx, s.change.dy, s.change.scale, s.change.degrees, centre) to s
	}

	/**
	 * The fitted piece as the part's new art, and the fields saved with it - the web's `apply`: the warp, the
	 * drawing's own pixels where "copy from drawing" was painted (inside the piece's shape), the trim mask kept
	 * (warped) so Restore still works later, glue, swing, and the art from before the first fit.
	 */
	fun bake(): Pair<Map<String, JsonElement?>, String> {
		val out = warped()
		if (copyUsed) for (i in out.indices) {
			val c = copy[i].toInt() and 0xFF; if (c == 0) continue
			val d = out[i]; if ((d ushr 24) == 0) continue
			val s = drawing[i]; val sa = (s ushr 24) * c / 255
			// source-atop: the piece keeps its alpha; the drawing's colour goes over it.
			val r = (((s ushr 16) and 0xFF) * sa + ((d ushr 16) and 0xFF) * (255 - sa)) / 255
			val g = (((s ushr 8) and 0xFF) * sa + ((d ushr 8) and 0xFF) * (255 - sa)) / 255
			val b = ((s and 0xFF) * sa + (d and 0xFF) * (255 - sa)) / 255
			out[i] = (d and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
		}
		val cutout = JsonPrimitive(pngDataUrl(encodePng(w, h, out)))
		val anyTrim = trim.any { it.toInt() != 0 }
		val changes = mutableMapOf<String, JsonElement?>("cutout" to cutout, "extractionMask" to cutout)
		if (anyTrim) {
			changes["trimBase"] = JsonPrimitive(pngDataUrl(encodePng(w, h, warped(base))))
			changes["trimMask"] = JsonPrimitive(pngDataUrl(encodePng(w, h, warped(FitRaster.tinted(trim, 0xFF2D55)))))
		} else { changes["trimBase"] = JsonNull; changes["trimMask"] = JsonNull }
		changes["hairAnchor"] = glue?.json() ?: JsonNull
		changes["swing"] = swing?.json() ?: JsonNull
		if (part.json.str("fitOriginal") == null) (part.json.str("trimBase") ?: part.cutout)?.let { changes["fitOriginal"] = JsonPrimitive(it) }
		val notes = listOfNotNull("trimmed".takeIf { trimmed }, "drawing brushed back".takeIf { copyUsed }).joinToString("") { " ($it)" }
		return changes to "Fitted ${part.name} by hand$notes"
	}

	// ---- Glue and swing ----

	/** How far (0..1) [p] sits across [b]: down it ("y"/top), across it ("x"/left), in from the right or bottom. */
	fun across(p: Pt, b: Box, how: String): Double {
		val bw = max(1.0, b.w); val bh = max(1.0, b.h)
		val f = when (how) { "x", "left" -> (p.x - b.x0) / bw; "right" -> (b.x1 - p.x) / bw; "bottom" -> (b.y1 - p.y) / bh; else -> (p.y - b.y0) / bh }
		return f.coerceIn(0.0, 1.0)
	}

	val rigAs: String get() = StudioRig.rigName(project, part) ?: ""
	val glueTargets: List<StudioPart> get() = project.parts.filter { it.id != part.id && StudioRig.rigName(project, it) != null }
	val backHairId: String? get() = glueTargets.firstOrNull { (StudioRig.rigName(project, it) ?: "").startsWith("back hair") }?.id

	companion object {
		private var edgeKey: String? = null
		private var edgeDist: FloatArray? = null

		/** Loads part [partId] for fitting: its art (or its untrimmed art and saved trim mask) and the layers around it. */
		fun load(project: StudioProject, partId: String): FitSession {
			val w = project.width; val h = project.height
			val part = project.part(partId) ?: error("That part is gone.")
			val art = part.json.str("trimBase") ?: part.cutout ?: error("This part has no layer art to fit yet.")
			val base = StudioImages.layer(art, w, h).full()
			val box = FitGeometry.alphaBox(base, w, h) ?: error("This part's layer is empty.")
			val trim = ByteArray(w * h)
			if (part.json.str("trimBase") != null) part.json.str("trimMask")?.let { url ->
				val m = StudioImages.layer(url, w, h)
				for (row in 0 until m.height) for (col in 0 until m.width) trim[(m.y + row) * w + m.x + col] = (m.argb[row * m.width + col] ushr 24).toByte()
			}
			val index = project.parts.indexOfFirst { it.id == partId }
			fun stack(parts: List<StudioPart>): IntArray {
				val out = IntArray(w * h)
				for (p in parts) if (p.visible && p.cutout != null) StudioRender.over(out, w, StudioImages.layer(p.cutout, w, h))
				return out
			}
			return FitSession(project, part, w, h, base, trim, StudioImages.layer(project.source, w, h).full(),
				stack(project.parts.subList(0, index)), stack(project.parts.subList(index + 1, project.parts.size)), box)
		}

		/** The pieces Tab and Pick move between: visible parts with art, bottom to top. */
		fun order(project: StudioProject) = project.parts.filter { it.visible && it.cutout != null }

		fun round3(v: Double) = (v * 1000).roundToInt() / 1000.0
	}
}
