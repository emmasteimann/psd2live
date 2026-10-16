package io.github.psd2live.ipad.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.ipad.CanvasTouch
import io.github.psd2live.ipad.CanvasView
import io.github.psd2live.ipad.canvasTouch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val Panel = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val CageColour = Color(0xFF7C4DFF)
private val GlueBlue = Color(0xFF3D8BFF)
private val SwingYellow = Color(0xFFFFD23D)

/** Fit mode's tools, as the web's (`TOOLS` in FitMode.jsx). */
private enum class FitTool(val label: String) {
	MOVE("Move"), HANDLES("Handles"), DISTORT("Distort"), BOX("Mark & snap"), PICK("Pick piece"),
	BRUSH("Copy from drawing"), ERASE("Uncopy"), TRIM("Trim"), RESTORE("Restore"), WAND("Wand"), GLUE("Glue"), SWING("Swing");
	val brushes get() = this == BRUSH || this == ERASE || this == TRIM || this == RESTORE
	val pixels get() = this == TRIM || this == RESTORE || this == WAND
}

private val tips = mapOf(
	FitTool.MOVE to "Drag the piece; corners scale (Free stretch: freely), sides stretch. Drag outside the box (or the dot) to turn it: start farther out for a finer turn.",
	FitTool.HANDLES to "Bend the piece with the cage's handles.",
	FitTool.DISTORT to "Pull any of the four corners on its own (perspective, skew); sides move two corners; drag inside to move. Keeps any bends.",
	FitTool.BOX to "Drag a box where the piece belongs: it drops in and snaps to your drawn lines.",
	FitTool.PICK to "Tap a piece on the canvas to fit it instead (this one is saved first).",
	FitTool.BRUSH to "Paint where YOUR DRAWING's own pixels should replace the piece's paint (the piece's shape doesn't change).",
	FitTool.ERASE to "Take back 'Copy from drawing' where you painted it (doesn't erase the piece).",
	FitTool.TRIM to "Hide part of the PIECE behind a mask (a toe poking out, a stray bit). Nothing is deleted: Restore brings it back, even after saving.",
	FitTool.RESTORE to "Paint the trim mask away: brings hidden parts of the piece back.",
	FitTool.WAND to "Tap to select an area of similar colour in the piece, then Trim selection.",
	FitTool.GLUE to "Pick the layer to glue to, tap the piece and drag the two lines. The glued end moves and sways exactly with that layer; the rest hangs from it.",
	FitTool.SWING to "Tap where the piece hangs from (the pivot line); below it, it sways on its own pendulum.",
)

private enum class GhostStyle(val label: String) { LINES("Lines only"), INVERTED("Inverted"), BLUE("Blue"), NORMAL("Normal") }

/** What's on screen around the piece (only redrawn when the view moves) and the piece itself (redrawn as it's fitted). */
private class StaticFrame(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val below: ImageBitmap?, val above: ImageBitmap?, val ghost: ImageBitmap?, val outlines: ImageBitmap?)
private class PieceFrame(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val piece: ImageBitmap, val overlay: ImageBitmap?)

/** A drag in progress, as the web's `drag.current`. */
private sealed class Drag {
	class Handle(val i: Int) : Drag()
	class Move(var last: Pt) : Drag()
	class Scale(val snapshot: List<Pt>, val start: Pt, val anchor: Pt, val axis: Char?) : Drag()
	class Rotate(val centre: Pt, val snapshot: List<Pt>, val a0: Double) : Drag()
	class Distort(val quad: List<Pt>, val snapshot: List<Pt>, val start: Pt, val moves: List<Int>) : Drag()
	class BoxDraft(val a: Pt) : Drag()
	class Trim(var last: Pt, val restore: Boolean) : Drag()
	class Copy(var last: Pt, val erase: Boolean) : Drag()
	class GlueLine(val which: String) : Drag()
	object SwingPivot : Drag()
}

/**
 * Fit by hand, native: move, scale, turn and bend a piece onto the drawing, distort it, mark a box and snap it to the
 * lines, trim it (masked, never deleted), copy the drawing's own pixels back, glue and swing it - then save it into
 * its layer. Same edits and the same saved fields as the web studio's Fit mode.
 */
@OptIn(ExperimentalLayoutApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun FitScreen(project: StudioProject, partId: String, pencilOnly: Boolean, onPencilOnly: (Boolean) -> Unit,
	onApply: (partId: String, changes: Map<String, JsonElement?>, event: String, stay: Boolean) -> Unit,
	onSelect: (String) -> Unit, onClose: () -> Unit) {
	val projectNow by rememberUpdatedState(project)
	var session by remember { mutableStateOf<FitSession?>(null) }
	var error by remember { mutableStateOf<String?>(null) }
	var tool by remember { mutableStateOf(FitTool.MOVE) }
	var ghost by remember { mutableStateOf(45f) }
	var ghostStyle by remember { mutableStateOf(GhostStyle.LINES) }
	var brush by remember { mutableStateOf(24f) }
	var feather by remember { mutableStateOf(0f) }
	var isolate by remember { mutableStateOf(false) }
	var outlines by remember { mutableStateOf(false) }
	var fromMiddle by remember { mutableStateOf(false) }
	var freeStretch by remember { mutableStateOf(false) }
	var wandMode by remember { mutableStateOf("new") }
	var wandTol by remember { mutableStateOf(28f) }
	var wandSel by remember { mutableStateOf<ByteArray?>(null) }
	var specks by remember { mutableStateOf<FitGeometry.Specks?>(null) }
	var specksSize by remember { mutableStateOf(40f) }
	var specksHaze by remember { mutableStateOf(true) }
	var draft by remember { mutableStateOf<Box?>(null) }
	var status by remember { mutableStateOf("") }
	var busy by remember { mutableStateOf<String?>(null) }
	var view by remember { mutableStateOf(CanvasView()) }
	var box by remember { mutableStateOf(0f to 0f) }
	var staticFrame by remember { mutableStateOf<StaticFrame?>(null) }
	var pieceFrame by remember { mutableStateOf<PieceFrame?>(null) }
	var ghostArt by remember { mutableStateOf<IntArray?>(null) }
	var outlineArt by remember { mutableStateOf<IntArray?>(null) }
	var hover by remember { mutableStateOf<Pt?>(null) }
	// The angle while the turn dot is dragged (shown on the canvas), and whether it settles on 15° steps.
	var turning by remember { mutableStateOf<Double?>(null) }
	var snapTurn by remember { mutableStateOf(true) }
	// What a handle drag is doing (shown on the canvas): "Scale 120%", "Height 90%"...
	var scaling by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	val safe = remember { CoroutineExceptionHandler { _, e -> scope.launch(Dispatchers.Main) { busy = null; status = "Something went wrong: ${e.message ?: e::class.simpleName}" } } }
	val text = rememberTextMeasurer()

	LaunchedEffect(partId) {
		session = null; error = null; specks = null; wandSel = null; outlineArt = null; staticFrame = null; pieceFrame = null
		try { session = withContext(Dispatchers.Default) { FitSession.load(projectNow, partId) }; status = "" }
		catch (e: Throwable) { error = e.message ?: "Couldn't open the piece." }
	}
	val s = session
	val sNow by rememberUpdatedState(s)
	val toolNow by rememberUpdatedState(tool)

	fun bump() { scope.launch(Dispatchers.Main) { sNow?.let { it.version++ } } }
	fun layout(w: Float, h: Float, v: CanvasView): FloatArray = v.layout(w, h, projectNow.width.toFloat(), projectNow.height.toFloat())

	/** Pixel tools (trim, restore, wand) work on the piece as it sits now: the fit is settled in first. */
	fun settleThen(block: () -> Unit = {}) {
		val f = sNow ?: return
		scope.launch(safe) {
			val r = withContext(worker) { f.settle() }
			f.settled(r); block()
		}
	}

	fun snap(from: List<Pt>) {
		val f = sNow ?: return
		busy = "Snapping to the drawn lines…"
		scope.launch(safe) {
			val (pts, result) = withContext(worker) { f.snap(from) }
			f.dst = pts; busy = null
			status = "Snapped: ${(result.before * 10).roundToInt() / 10.0} → ${(result.after * 10).roundToInt() / 10.0} px from the drawn lines. Nudge or bend what's left; Undo goes back."
		}
	}

	fun apply(stay: Boolean, then: () -> Unit = {}) {
		val f = sNow ?: return
		busy = "Saving ${f.part.name}"
		scope.launch(safe) {
			val (changes, event) = withContext(worker) { f.bake() }
			busy = null
			onApply(f.part.id, changes, event, stay); then()
		}
	}

	fun goTo(id: String?) {
		val f = sNow ?: return
		if (id == null || id == f.part.id) return
		if (f.dirty) apply(true) { onSelect(id) } else onSelect(id)
	}

	fun step(dir: Int) {
		val f = sNow ?: return
		val order = FitSession.order(projectNow); if (order.isEmpty()) return
		val i = order.indexOfFirst { it.id == f.part.id }
		goTo(order[((i + dir) % order.size + order.size) % order.size].id)
	}

	fun undoStep() {
		val f = sNow ?: return
		if (f.undo()) scope.launch(worker + safe) { f.refreshPiece(); bump() }
	}

	// The drawing on top, in the chosen style (computed once per style).
	LaunchedEffect(s, ghostStyle) {
		val f = s ?: return@LaunchedEffect
		ghostArt = null
		ghostArt = withContext(Dispatchers.Default) {
			when (ghostStyle) {
				GhostStyle.LINES -> { val e = FitGeometry.lineArt(f.drawing, f.w, f.h); IntArray(e.size) { if (e[it].toInt() != 0) 0xFF00C8FF.toInt() else 0 } }
				GhostStyle.INVERTED -> IntArray(f.drawing.size) { val p = f.drawing[it]; (p and 0xFF000000.toInt()) or (p.inv() and 0xFFFFFF) }
				GhostStyle.BLUE -> IntArray(f.drawing.size) { val p = f.drawing[it]
					val g = (0.299 * ((p ushr 16) and 0xFF) + 0.587 * ((p ushr 8) and 0xFF) + 0.114 * (p and 0xFF)).toInt()
					(p and 0xFF000000.toInt()) or ((g * 0.25).toInt() shl 16) or ((g * 0.7).toInt() shl 8) or minOf(255, (g * 1.1).toInt() + 30) }
				GhostStyle.NORMAL -> f.drawing
			}
		}
	}

	// Every other piece's outline, in magenta (O).
	LaunchedEffect(s, outlines) {
		val f = s ?: return@LaunchedEffect
		if (!outlines || outlineArt != null) return@LaunchedEffect
		outlineArt = withContext(Dispatchers.Default) {
			val out = IntArray(f.w * f.h)
			for (p in FitSession.order(f.project)) {
				if (p.id == f.part.id) continue
				val l = StudioImages.layer(p.cutout, f.w, f.h)
				for (row in 0 until l.height) for (col in 0 until l.width) {
					if ((l.argb[row * l.width + col] ushr 24) == 0) continue
					val x = l.x + col; val y = l.y + row
					for ((dx, dy) in listOf(-2 to 0, 2 to 0, 0 to -2, 0 to 2)) {
						val xx = x + dx; val yy = y + dy
						if (xx in 0 until f.w && yy in 0 until f.h && (l.at(xx, yy) ushr 24) == 0) out[yy * f.w + xx] = 0xFFFF3DB4.toInt()
					}
				}
			}
			out
		}
	}

	fun region(w: Float, h: Float, l: FloatArray, budget: Float): FloatArray? {
		val p = projectNow
		val scale = l[0]; val left = l[1]; val top = l[2]
		val x0 = maxOf(0f, -left / scale); val y0 = maxOf(0f, -top / scale)
		val x1 = minOf(p.width.toFloat(), (w - left) / scale); val y1 = minOf(p.height.toFloat(), (h - top) / scale)
		if (x1 - x0 < 1f || y1 - y0 < 1f) return null
		var outW = ((x1 - x0) * scale).coerceAtLeast(1f); var outH = ((y1 - y0) * scale).coerceAtLeast(1f)
		val shrink = minOf(1f, kotlin.math.sqrt(budget / (outW * outH)))
		outW = (outW * shrink).coerceAtLeast(1f); outH = (outH * shrink).coerceAtLeast(1f)
		return floatArrayOf(x0, y0, x1, y1, outW.roundToInt().toFloat(), outH.roundToInt().toFloat())
	}

	// Around the piece: redrawn when the view rests.
	LaunchedEffect(s) {
		val f = s ?: return@LaunchedEffect
		snapshotFlow { listOf(view, box, ghostArt, outlineArt, outlines, isolate) }.collectLatest {
			val (w, h) = box; if (w < 2f || h < 2f) return@collectLatest
			if (staticFrame != null) delay(120)
			val r = region(w, h, layout(w, h, view), 2_400_000f) ?: return@collectLatest
			val g = ghostArt; val o = if (outlines) outlineArt else null; val iso = isolate
			staticFrame = withContext(Dispatchers.Default) {
				fun bm(a: IntArray) = argbRegionBitmap(a, f.w, f.h, r[0], r[1], r[2] - r[0], r[3] - r[1], r[4].toInt(), r[5].toInt())
				StaticFrame(r[0], r[1], r[2], r[3], if (iso) null else bm(f.below), if (iso) null else bm(f.above), g?.let { bm(it) }, o?.let { bm(it) })
			}
		}
	}

	// The piece: redrawn at once as it's fitted (a lighter draft while things move, sharp once they rest).
	LaunchedEffect(s) {
		val f = s ?: return@LaunchedEffect
		var lastView: CanvasView? = null
		snapshotFlow { listOf(view, box, f.dst, f.grid, f.box, f.version, tool, wandSel, specks) }.collectLatest {
			val (w, h) = box; if (w < 2f || h < 2f) return@collectLatest
			val viewOnly = lastView != null && lastView != view; lastView = view
			if (viewOnly) delay(120)
			val l = layout(w, h, view)
			val src = f.src; val dst = f.dst; val n = f.grid; val t = tool; val sel = wandSel; val sp = specks
			suspend fun render(budget: Float): PieceFrame? = withContext(Dispatchers.Default) {
				val r = region(w, h, l, budget) ?: return@withContext null
				val ow = r[4].toInt(); val oh = r[5].toInt()
				val rw = (r[2] - r[0]).toDouble(); val rh = (r[3] - r[1]).toDouble()
				val pieceArgb = FitRaster.warp(f.piece, f.w, f.h, src, dst, n, r[0].toDouble(), r[1].toDouble(), rw, rh, ow, oh, nearest = l[0] >= 6f)
				// What the tool shows over the piece: the copy mask (green), what's trimmed (red, faint), the wand's selection, specks.
				val masks = buildList {
					if (t == FitTool.BRUSH || t == FitTool.ERASE) add(Triple(f.copy, 0x3DD68C, 0.45))
					if (t == FitTool.TRIM || t == FitTool.RESTORE) add(Triple(f.trim, 0xFF2D55, 0.3))
					if (sel != null) add(Triple(sel, 0xFF28C8, 0.6))
					if (sp != null) add(Triple(sp.remove, 0xFF283C, 0.9))
				}
				val overlay = if (masks.isEmpty()) null else {
					val o = IntArray(ow * oh)
					for (row in 0 until oh) {
						val sy = (r[1] + (row + 0.5f) * (r[3] - r[1]) / oh).toInt().coerceIn(0, f.h - 1)
						for (col in 0 until ow) {
							val sx = (r[0] + (col + 0.5f) * (r[2] - r[0]) / ow).toInt().coerceIn(0, f.w - 1)
							for ((m, rgb, op) in masks) {
								val a = m[sy * f.w + sx].toInt() and 0xFF
								val av = if (m === sel || (sp != null && m === sp.remove)) (if (a != 0) 255 else 0) else a
								if (av != 0) o[row * ow + col] = StudioRender.blend(o[row * ow + col], ((av * op).toInt() shl 24) or rgb)
							}
						}
					}
					argbRegionBitmap(o, ow, oh, 0f, 0f, ow.toFloat(), oh.toFloat(), ow, oh)
				}
				PieceFrame(r[0], r[1], r[2], r[3], argbRegionBitmap(pieceArgb, ow, oh, 0f, 0f, ow.toFloat(), oh.toFloat(), ow, oh), overlay)
			}
			pieceFrame = render(500_000f)
			delay(140)
			pieceFrame = render(2_400_000f)
		}
	}

	Column(Modifier.fillMaxSize().background(Color(0xFF14141C))) {
		// ---- Tools ----
		FlowRow(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 8.dp),
			horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			for (t in FitTool.entries) Chip(t.label, tool == t) {
				if (t == tool) return@Chip
				tool = t; wandSel = null
				if (t.pixels) settleThen()
				status = tips[t] ?: ""
			}
		}
		FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp)) {
			val ready = s != null && busy == null
			Chip("Snap", enabled = ready) { s?.let { it.pushCage(); snap(it.dst) } }
			Chip(if (specks != null) "Hide specks" else "Specks", specks != null, enabled = ready) {
				val f = s ?: return@Chip
				if (specks != null) { specks = null; return@Chip }
				scope.launch(safe) {
					busy = "Finding specks…"
					val r = withContext(worker) { f.settle() }; f.settled(r)
					specks = withContext(worker) { FitGeometry.findSpecks(f.piece, f.w, f.h, specksSize.roundToInt(), specksHaze) }
					busy = null
				}
			}
			if (s?.part?.json?.str("fitOriginal") != null) Chip("Start over from original", enabled = ready) {
				val f = s
				scope.launch(safe) { val b = withContext(worker) { f.startOver() }; if (b != null) { f.newBox(b); status = "Back to the original piece (its trims too). Fit it again; Discard changes keeps the last fit instead." } }
			}
			Chip("−") { s?.nudge(scale = 0.98) }
			Chip("＋") { s?.nudge(scale = 1.02) }
			Chip("⟲") { s?.nudge(degrees = -1.0) }
			Chip("⟳") { s?.nudge(degrees = 1.0) }
			Chip("⇋ Mirror") { s?.mirror(true) }
			Chip("⇅ Flip") { s?.mirror(false) }
			Chip("Isolate", isolate) { isolate = !isolate }
			Chip("Outlines", outlines) { outlines = !outlines }
			Chip(if (pencilOnly) "Pencil only · fingers pan" else "Fingers draw", pencilOnly) { onPencilOnly(!pencilOnly) }
		}
		Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Column(Modifier.weight(1f).fillMaxHeight()) {
				// ---- The canvas ----
				BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF101017))) {
					val density = LocalDensity.current
					val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
					if (box != (w to h)) box = w to h
					val f = s
					if (f == null) {
						Text(error ?: "Loading the piece and the drawing…", color = Muted, modifier = Modifier.align(Alignment.Center))
						return@BoxWithConstraints
					}
					Canvas(Modifier.fillMaxSize().canvasTouch(listOf(f, w, h), fingersPan = { pencilOnly }, onStylus = { if (!pencilOnly) onPencilOnly(true) }) {
						object : CanvasTouch {
							private var drag: Drag? = null
							private var historyAtStart = 0
							private fun toCanvas(o: Offset): Pt { val l = layout(w, h, view); return Pt(((o.x - l[1]) / l[0]).toDouble(), ((o.y - l[2]) / l[0]).toDouble()) }
							private fun radius() = 22.0 / layout(w, h, view)[0]
							override fun start(at: Offset, pressure: Float) {
								if (busy != null) return
								val p = toCanvas(at); val r = radius(); val dst = f.dst
								historyAtStart = f.undoCount
								if (toolNow.brushes) hover = p
								drag = when (toolNow) {
									FitTool.PICK -> { scope.launch(safe) { val id = withContext(worker) { StudioEdits.partAt(projectNow, p.x.toInt(), p.y.toInt()) }
										if (id != null) goTo(id) else status = "No piece there." }; null }
									FitTool.GLUE -> {
										val axisX = f.glue?.axisX ?: false
										val fr = f.across(p, FitGeometry.bounds(dst), if (axisX) "x" else "y")
										val g = f.glue ?: Glue(maxOf(0.0, fr - 0.1), minOf(1.0, fr + 0.1), true, axisX, f.backHairId)
										if (f.glue == null) f.glue = g
										Drag.GlueLine(if (kotlin.math.abs(fr - g.from) <= kotlin.math.abs(fr - g.to)) "from" else "to")
									}
									FitTool.SWING -> {
										val cur = f.swing ?: Swing.fresh(f.rigAs)
										f.swing = cur.copy(pivot = FitSession.round3(minOf(0.95, f.across(p, FitGeometry.bounds(dst), cur.edge ?: "top"))))
										Drag.SwingPivot
									}
									FitTool.HANDLES -> FitGeometry.nearestHandle(dst, p, r).takeIf { it >= 0 }?.let { f.pushCage(); Drag.Handle(it) }
									FitTool.MOVE -> {
										val scale = layout(w, h, view)[0]
										val hs = transformHandles(dst, TURN_LIFT_PX / scale)
										val mid = Pt((hs.b.x0 + hs.b.x1) / 2, (hs.b.y0 + hs.b.y1) / 2)
										f.pushCage()
										// The nearest handle under the finger wins (corners get the biggest reach and win close calls), so a
										// small piece's crowded handles don't steal each other's drags.
										fun d(q: Pt) = hypot(p.x - q.x, p.y - q.y) * scale
										val ci = hs.corners.indices.minByOrNull { d(hs.corners[it]) }!!; val ei = hs.edges.indices.minByOrNull { d(hs.edges[it]) }!!
										val dc = d(hs.corners[ci]) * 0.75; val de = d(hs.edges[ei]); val dr = d(hs.rotate)
										val best = listOf(Triple('c', dc, CORNER_HIT_PX * 0.75), Triple('e', de, EDGE_HIT_PX), Triple('r', dr, TURN_HIT_PX))
											.filter { it.second <= it.third }.minByOrNull { it.second }?.first
										when {
											best == 'r' -> { val c = FitGeometry.centroid(dst); Drag.Rotate(c, dst, atan2(p.y - c.y, p.x - c.x)) }
											best == 'c' -> Drag.Scale(dst, hs.corners[ci], if (fromMiddle) mid else hs.corners[(ci + 2) % 4], null)
											best == 'e' -> Drag.Scale(dst, hs.edges[ei], if (fromMiddle) mid else hs.edges[(ei + 2) % 4], if (ei % 2 == 1) 'x' else 'y')
											// Outside the box (past the handles) turns it, as in Photoshop: the farther out the drag starts,
											// the finer the turn - the dot alone is a short lever and turned too fast.
											p.x < hs.b.x0 - r || p.x > hs.b.x1 + r || p.y < hs.b.y0 - r || p.y > hs.b.y1 + r -> { val c = FitGeometry.centroid(dst); Drag.Rotate(c, dst, atan2(p.y - c.y, p.x - c.x)) }
											else -> Drag.Move(p)
										}
									}
									FitTool.DISTORT -> {
										val base = if (f.grid < 6) f.changeGrid(6) else dst
										val idx = FitGeometry.cageCorners(maxOf(f.grid, 6))
										val quad = idx.map { base[it] }
										val sides = quad.indices.map { i -> Pt((quad[i].x + quad[(i + 1) % 4].x) / 2, (quad[i].y + quad[(i + 1) % 4].y) / 2) }
										f.pushCage()
										val hi = FitGeometry.nearestHandle(quad + sides, p, r)
										Drag.Distort(quad, base, p, if (hi < 0) listOf(0, 1, 2, 3) else if (hi < 4) listOf(hi) else listOf(hi - 4, (hi - 3) % 4))
									}
									FitTool.BOX -> { draft = Box(p.x, p.y, p.x, p.y); Drag.BoxDraft(p) }
									FitTool.WAND -> {
										val add = wandMode == "add"; val sub = wandMode == "subtract"; val tol = wandTol.roundToInt(); val prev = wandSel
										scope.launch(safe) {
											val m = withContext(worker) { FitGeometry.wandSelect(f.piece, f.w, f.h, p.x.toInt(), p.y.toInt(), tol) }
											val sel = if (prev != null && add) ByteArray(m.size) { (prev[it].toInt() or m[it].toInt()).toByte() }
												else if (prev != null && sub) ByteArray(m.size) { if (prev[it].toInt() != 0 && m[it].toInt() == 0) 1 else 0 } else m
											val count = sel.count { it.toInt() != 0 }
											if (count == 0) { wandSel = null; status = "Nothing selected there (tap on the piece)." }
											else { wandSel = sel; status = "$count px selected · Trim selection cuts them away (masked, not deleted)." }
										}
										null
									}
									FitTool.TRIM, FitTool.RESTORE -> {
										val restore = toolNow == FitTool.RESTORE; f.pushTrim(); val b = brush.toDouble(); val fe = feather / 100.0
										scope.launch(worker + safe) { f.trimStroke(p, p, b, fe, restore); bump() }
										f.trimmed = true
										Drag.Trim(p, restore)
									}
									FitTool.BRUSH, FitTool.ERASE -> {
										val erase = toolNow == FitTool.ERASE; val b = brush.toDouble(); val fe = feather / 100.0
										scope.launch(worker + safe) { f.copyStroke(p, p, b, fe, erase); bump() }
										f.copyUsed = true
										Drag.Copy(p, erase)
									}
								}
							}
							override fun move(at: Offset, pressure: Float) {
								val p = toCanvas(at)
								if (toolNow.brushes) hover = p
								when (val d = drag ?: return) {
									is Drag.Handle -> f.dst = f.dst.mapIndexed { i, q -> if (i == d.i) p else q }
									is Drag.Move -> { val dx = p.x - d.last.x; val dy = p.y - d.last.y; d.last = p; f.dst = f.dst.map { Pt(it.x + dx, it.y + dy) } }
									is Drag.Scale -> {
										fun ratio(a: Double, s0: Double, an: Double): Double { val den = s0 - an; return if (kotlin.math.abs(den) < 1) 1.0 else maxOf(0.05, (a - an) / den) }
										var sx = 1.0; var sy = 1.0
										when {
											d.axis == 'x' -> sx = ratio(p.x, d.start.x, d.anchor.x)
											d.axis == 'y' -> sy = ratio(p.y, d.start.y, d.anchor.y)
											freeStretch -> { sx = ratio(p.x, d.start.x, d.anchor.x); sy = ratio(p.y, d.start.y, d.anchor.y) }
											else -> { sx = maxOf(0.05, hypot(p.x - d.anchor.x, p.y - d.anchor.y) / maxOf(1.0, hypot(d.start.x - d.anchor.x, d.start.y - d.anchor.y))); sy = sx }
										}
										f.dst = d.snapshot.map { Pt(d.anchor.x + (it.x - d.anchor.x) * sx, d.anchor.y + (it.y - d.anchor.y) * sy) }
										fun pc(v: Double) = "${(v * 100).roundToInt()}%"
										scaling = when { d.axis == 'x' -> "Width ${pc(sx)}"; d.axis == 'y' -> "Height ${pc(sy)}"; freeStretch -> "Free stretch ${pc(sx)} × ${pc(sy)}"; else -> "Scale ${pc(sx)}" }
									}
									is Drag.Rotate -> {
										var deg = (atan2(p.y - d.centre.y, p.x - d.centre.x) - d.a0) * 180 / kotlin.math.PI
										deg = ((deg + 540) % 360) - 180
										// Close to a multiple of 15°, it settles there (as Procreate's snapping does); further out it's free.
										val nearest = kotlin.math.round(deg / 15) * 15
										if (snapTurn && kotlin.math.abs(deg - nearest) < 2.5) deg = nearest
										turning = deg
										f.dst = FitGeometry.transformPoints(d.snapshot, degrees = deg, centre = d.centre)
									}
									is Drag.GlueLine -> {
										val g = f.glue ?: return
										val fr = FitSession.round3(f.across(p, FitGeometry.bounds(f.dst), if (g.axisX) "x" else "y"))
										val next = if (d.which == "from") g.copy(from = fr) else g.copy(to = fr)
										f.glue = if (next.from <= next.to) next else next.copy(from = next.to, to = next.from)
									}
									Drag.SwingPivot -> f.swing?.let { sw -> f.swing = sw.copy(pivot = FitSession.round3(minOf(0.95, f.across(p, FitGeometry.bounds(f.dst), sw.edge ?: "top")))) }
									is Drag.Distort -> {
										val dx = p.x - d.start.x; val dy = p.y - d.start.y
										val quad = d.quad.mapIndexed { i, q -> if (i in d.moves) Pt(q.x + dx, q.y + dy) else q }
										// A fold or pinch is ignored: the last good shape stays.
										val m = if (FitGeometry.convexQuad(quad)) FitGeometry.homography(d.quad, quad) else null
										if (m != null) f.dst = d.snapshot.map { FitGeometry.applyHomography(m, it) }
									}
									is Drag.BoxDraft -> draft = Box(minOf(d.a.x, p.x), minOf(d.a.y, p.y), maxOf(d.a.x, p.x), maxOf(d.a.y, p.y))
									is Drag.Trim -> { val a = d.last; d.last = p; val b = brush.toDouble(); val fe = feather / 100.0; scope.launch(worker + safe) { f.trimStroke(a, p, b, fe, d.restore); bump() } }
									is Drag.Copy -> { val a = d.last; d.last = p; val b = brush.toDouble(); val fe = feather / 100.0; scope.launch(worker + safe) { f.copyStroke(a, p, b, fe, d.erase); bump() } }
								}
							}
							override fun end() {
								val d = drag; drag = null; hover = null; turning = null; scaling = null
								val dr = draft
								if (d is Drag.BoxDraft) {
									draft = null
									if (dr != null && dr.w > 6 && dr.h > 6) {
										// Into the marked box (keeping the piece's shape), then onto the drawn lines.
										val b = FitGeometry.bounds(f.dst); val sc = 0.5 * (dr.w / b.w + dr.h / b.h)
										val placed = f.dst.map { Pt((dr.x0 + dr.x1) / 2 + (it.x - (b.x0 + b.x1) / 2) * sc, (dr.y0 + dr.y1) / 2 + (it.y - (b.y0 + b.y1) / 2) * sc) }
										f.pushCage(); f.dst = placed; snap(placed)
									}
								}
							}
							override fun cancel() {
								// A second finger: the first finger's edit was the start of a pinch, so put it back.
								if (drag != null && f.undoCount > historyAtStart) undoStep()
								drag = null; draft = null; hover = null; turning = null; scaling = null
							}
							override fun undo() { undoStep() }
							override fun redo() {}
							override fun view() = view
							override fun setView(view: CanvasView) { setFitView(view) }
							override fun layout(view: CanvasView) = layout(w, h, view)
							private fun setFitView(v: CanvasView) { view = v }
						}
					}) {
						val l = layout(w, h, view)
						val cw = f.w * l[0]; val ch = f.h * l[0]
						drawRect(Color.White, Offset(l[1], l[2]), Size(cw, ch))
						fun place(x0: Float, y0: Float, x1: Float, y1: Float) =
							IntOffset((l[1] + x0 * l[0]).roundToInt(), (l[2] + y0 * l[0]).roundToInt()) to IntSize(((x1 - x0) * l[0]).roundToInt().coerceAtLeast(1), ((y1 - y0) * l[0]).roundToInt().coerceAtLeast(1))
						val st = staticFrame; val pf = pieceFrame
						st?.let { fr -> val (o, z) = place(fr.x0, fr.y0, fr.x1, fr.y1); fr.below?.let { drawImage(it, dstOffset = o, dstSize = z) } }
						pf?.let { fr -> val (o, z) = place(fr.x0, fr.y0, fr.x1, fr.y1); drawImage(fr.piece, dstOffset = o, dstSize = z) }
						st?.let { fr ->
							val (o, z) = place(fr.x0, fr.y0, fr.x1, fr.y1)
							fr.above?.let { drawImage(it, dstOffset = o, dstSize = z) }
							if (ghost > 0) fr.ghost?.let { drawImage(it, dstOffset = o, dstSize = z, alpha = ghost / 100f) }
							fr.outlines?.let { drawImage(it, dstOffset = o, dstSize = z) }
						}
						pf?.overlay?.let { ov -> val (o, z) = place(pf.x0, pf.y0, pf.x1, pf.y1); drawImage(ov, dstOffset = o, dstSize = z) }
						overlay(f, tool, l, draft, specks, hover?.takeIf { tool.brushes }?.let { it to brush / 2.0 }, feather, text)
						scaling?.let { label ->
							val c = FitGeometry.centroid(f.dst)
							val laid = text.measure(label, TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
							val at = Offset(l[1] + c.x.toFloat() * l[0] - laid.size.width / 2, l[2] + c.y.toFloat() * l[0] - laid.size.height / 2)
							drawRect(CageColour.copy(alpha = 0.85f), at - Offset(8f, 4f), Size(laid.size.width + 16f, laid.size.height + 8f))
							drawText(laid, topLeft = at)
						}
						turning?.let { deg ->
							val c = FitGeometry.centroid(f.dst)
							val laid = text.measure("${if (deg > 0) "+" else ""}${(deg * 10).roundToInt() / 10.0}°", TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
							val at = Offset(l[1] + c.x.toFloat() * l[0] - laid.size.width / 2, l[2] + c.y.toFloat() * l[0] - laid.size.height / 2)
							drawRect(CageColour.copy(alpha = 0.85f), at - Offset(8f, 4f), Size(laid.size.width + 16f, laid.size.height + 8f))
							drawText(laid, topLeft = at)
						}
					}
				}
				// ---- Bottom bar ----
				Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					val f = s
					Chip("◀ Previous piece", enabled = f != null && busy == null) { step(-1) }
					Chip("Next piece ▶", enabled = f != null && busy == null) { step(1) }
					Chip("Reset") { f?.reset(); status = "" }
				}
			}
			// ---- Options for the tool ----
			Column(Modifier.width(290.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Panel).verticalScroll(rememberScrollState()).padding(12.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp)) {
				val f = s
				Text(f?.let { "Fitting ${it.part.name}" } ?: "Fit by hand", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
				// Save / discard always in sight.
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
					Chip(if (f?.dirty == true) "Save piece" else "Done", true, enabled = f != null && busy == null) { if (f?.dirty == true) apply(false) else onClose() }
					Chip(if (f?.dirty == true) "Discard" else "Close") { onClose() }
					Chip("Undo", enabled = (f?.undoCount ?: 0) > 0) { undoStep() }
				}
				Text(if (f?.dirty == true) "● Unsaved changes" else "No changes", color = if (f?.dirty == true) Color(0xFFFFB86B) else Muted, fontSize = 12.sp)
				busy?.let { Text(it, color = Muted, fontSize = 12.sp); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent) }
				if (status.isNotEmpty()) Text(status, color = Muted, fontSize = 13.sp)
				if (f != null) {
					// Read so the panel follows the session.
					@Suppress("UNUSED_VARIABLE") val v = f.version
					when (tool) {
						FitTool.MOVE -> {
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								Chip("From middle", fromMiddle) { fromMiddle = !fromMiddle }
								Chip(if (freeStretch) "Free stretch: on" else "Free stretch", freeStretch) { freeStretch = !freeStretch }
							}
							Text("Turn: drag outside the box (farther out turns finer) or the dot, or", color = Muted, fontSize = 12.sp)
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								Chip("−15°") { f.nudge(degrees = -15.0) }
								Chip("−1°") { f.nudge(degrees = -1.0) }
								Chip("+1°") { f.nudge(degrees = 1.0) }
								Chip("+15°") { f.nudge(degrees = 15.0) }
							}
							Chip(if (snapTurn) "Settles on 15° steps" else "Turns freely", snapTurn) { snapTurn = !snapTurn }
						}
						FitTool.WAND -> {
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for ((k, label) in listOf("new" to "New", "add" to "Add", "subtract" to "Take away")) Chip(label, wandMode == k) { wandMode = k } }
							Text("Tolerance ${wandTol.roundToInt()}", color = Muted, fontSize = 12.sp)
							Slider(wandTol, { wandTol = it }, valueRange = 4f..90f, colors = sliderColors(), modifier = Modifier.height(30.dp))
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								Chip("Trim selection", true, enabled = wandSel != null) {
									val sel = wandSel ?: return@Chip
									f.pushTrim(); wandSel = null
									scope.launch(worker + safe) { f.trimSelection(sel); bump() }
									f.trimmed = true; status = "Trimmed the selection (masked, not deleted). Restore or Undo brings it back."
								}
								Chip("Clear selection", enabled = wandSel != null) { wandSel = null }
							}
						}
						FitTool.GLUE -> GluePanel(f)
						FitTool.SWING -> SwingPanel(f)
						else -> {}
					}
					if (tool == FitTool.TRIM || tool == FitTool.RESTORE) Chip("Clear all trims") {
						f.pushTrim(); scope.launch(worker + safe) { f.clearTrims(); bump() }; f.trimmed = true
						status = "All trims cleared: the whole piece shows again. Undo puts them back."
					}
					if (tool.brushes) {
						Text("Brush ${brush.roundToInt()} px", color = Muted, fontSize = 12.sp)
						Slider(brush, { brush = it }, valueRange = 2f..300f, colors = sliderColors(), modifier = Modifier.height(30.dp))
						Text("Feather ${feather.roundToInt()}%", color = Muted, fontSize = 12.sp)
						Slider(feather, { feather = it }, valueRange = 0f..100f, colors = sliderColors(), modifier = Modifier.height(30.dp))
					}
					specks?.let { sp ->
						Text("Specks", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
						Text("Specks up to ${specksSize.roundToInt()} px", color = Muted, fontSize = 12.sp)
						Slider(specksSize, { specksSize = it }, valueRange = 4f..600f, colors = sliderColors(), modifier = Modifier.height(30.dp),
							onValueChangeFinished = { scope.launch(safe) { specks = withContext(worker) { FitGeometry.findSpecks(f.piece, f.w, f.h, specksSize.roundToInt(), specksHaze) } } })
						Chip(if (specksHaze) "Faint haze: included" else "Faint haze: left", specksHaze) {
							specksHaze = !specksHaze
							scope.launch(safe) { specks = withContext(worker) { FitGeometry.findSpecks(f.piece, f.w, f.h, specksSize.roundToInt(), specksHaze) } }
						}
						Text(if (sp.px > 0) "${sp.count} speck(s), ${sp.px} px in red" else "Nothing found", color = Muted, fontSize = 12.sp)
						Chip("Remove them", true, enabled = sp.px > 0) {
							f.pushTrim(); specks = null
							scope.launch(worker + safe) { f.removeSpecks(sp); bump() }
							f.trimmed = true; status = "Removed ${sp.count} speck(s) and ${sp.px} px of haze/specks (into the trim mask: Restore brings any back)."
						}
					}
					Spacer(Modifier.height(4.dp))
					Text("Drawing on top ${ghost.roundToInt()}%", color = Muted, fontSize = 12.sp)
					Slider(ghost, { ghost = it }, valueRange = 0f..100f, colors = sliderColors(), modifier = Modifier.height(30.dp))
					FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						for (g in GhostStyle.entries) Chip(g.label, ghostStyle == g) { ghostStyle = g }
					}
					Text("Cage", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (n in listOf(2, 3, 4, 6)) Chip("$n×$n", f.grid == n) { f.changeGrid(n) } }
					Text("Piece", color = Muted, fontSize = 12.sp)
					FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						for (p in FitSession.order(project)) Chip(p.name, p.id == f.part.id, enabled = busy == null) { goTo(p.id) }
					}
				}
			}
		}
	}
}

/** How far above the box the turn dot sits, and how near a touch must land to grab it, in screen pixels. */
private const val TURN_LIFT_PX = 44.0
private const val TURN_HIT_PX = 34.0
private const val CORNER_HIT_PX = 40.0
private const val EDGE_HIT_PX = 26.0

/** The move tool's handles around the cage's box: corners, side middles, and the turn dot [lift] above. */
private class MoveHandles(val b: Box, val corners: List<Pt>, val edges: List<Pt>, val rotate: Pt)

private fun transformHandles(pts: List<Pt>, lift: Double): MoveHandles {
	val b = FitGeometry.bounds(pts); val mx = (b.x0 + b.x1) / 2; val my = (b.y0 + b.y1) / 2
	return MoveHandles(b, listOf(Pt(b.x0, b.y0), Pt(b.x1, b.y0), Pt(b.x1, b.y1), Pt(b.x0, b.y1)),
		listOf(Pt(mx, b.y0), Pt(b.x1, my), Pt(mx, b.y1), Pt(b.x0, my)), Pt(mx, b.y0 - lift))
}

/** Handles, cage, boxes and lines drawn as vectors over the canvas: crisp and the same size on screen at any zoom. */
private fun DrawScope.overlay(f: FitSession, tool: FitTool, l: FloatArray, draft: Box?, specks: FitGeometry.Specks?, brushAt: Pair<Pt, Double>?, feather: Float,
	text: androidx.compose.ui.text.TextMeasurer) {
	fun sc(p: Pt) = Offset(l[1] + p.x.toFloat() * l[0], l[2] + p.y.toFloat() * l[0])
	val dst = f.dst
	val line = Stroke(2f)
	val dash = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
	fun knob(c: Offset, r: Float = 9f, fill: Color = Color.White) { drawCircle(fill, r, c); drawCircle(CageColour, r, c, style = line) }
	fun square(c: Offset, half: Float, fill: Color = Color.White) { drawRect(fill, Offset(c.x - half, c.y - half), Size(half * 2, half * 2)); drawRect(CageColour, Offset(c.x - half, c.y - half), Size(half * 2, half * 2), style = line) }
	specks?.rings?.take(400)?.forEach { drawCircle(Color(0xFFFF2850), 14f, sc(it), style = Stroke(2.5f)) }
	if (dst.isEmpty()) return
	when (tool) {
		FitTool.HANDLES -> {
			val n = f.grid
			fun at(i: Int, j: Int) = sc(dst[j * (n + 1) + i])
			for (j in 0..n) for (i in 0..n) { if (i < n) drawLine(CageColour, at(i, j), at(i + 1, j), 2f); if (j < n) drawLine(CageColour, at(i, j), at(i, j + 1), 2f) }
			dst.forEach { knob(sc(it), 8f) }
		}
		FitTool.MOVE -> {
			val hs = transformHandles(dst, TURN_LIFT_PX / l[0])
			val a = sc(Pt(hs.b.x0, hs.b.y0)); val b = sc(Pt(hs.b.x1, hs.b.y1))
			drawRect(CageColour, a, Size(b.x - a.x, b.y - a.y), style = dash)
			drawLine(CageColour, sc(Pt((hs.b.x0 + hs.b.x1) / 2, hs.b.y0)), sc(hs.rotate), 2f)
			hs.corners.forEach { square(sc(it), 9f) }; hs.edges.forEach { square(sc(it), 7f) }; knob(sc(hs.rotate), 10f)
		}
		FitTool.DISTORT -> {
			val q = FitGeometry.cageCorners(f.grid).map { sc(dst[it]) }
			val path = Path().apply { moveTo(q[0].x, q[0].y); for (i in 1..3) lineTo(q[i].x, q[i].y); close() }
			drawPath(path, CageColour, style = dash)
			q.forEachIndexed { i, p -> val nx = q[(i + 1) % 4]; square(Offset((p.x + nx.x) / 2, (p.y + nx.y) / 2), 7f) }
			q.forEach { square(it, 10f, CageColour) }
		}
		FitTool.GLUE, FitTool.SWING -> {
			val b = FitGeometry.bounds(dst)
			val a0 = sc(Pt(b.x0, b.y0)); val a1 = sc(Pt(b.x1, b.y1)); val bw = a1.x - a0.x; val bh = a1.y - a0.y
			drawRect(CageColour, a0, Size(bw, bh), style = dash)
			fun at(how: String, fr: Double): Float = when (how) { "x", "left" -> a0.x + fr.toFloat() * bw; "right" -> a1.x - fr.toFloat() * bw; "bottom" -> a1.y - fr.toFloat() * bh; else -> a0.y + fr.toFloat() * bh }
			fun vertical(how: String) = how == "x" || how == "left" || how == "right"
			fun region(how: String, f0: Double, f1: Double, colour: Color) {
				val p0 = at(how, f0); val p1 = at(how, f1); val lo = minOf(p0, p1); val hi = maxOf(p0, p1)
				if (vertical(how)) drawRect(colour, Offset(lo, a0.y), Size(hi - lo, bh)) else drawRect(colour, Offset(a0.x, lo), Size(bw, hi - lo))
			}
			fun labelled(how: String, fr: Double, label: String, colour: Color) {
				val v = at(how, fr)
				if (vertical(how)) drawLine(colour, Offset(v, a0.y - 18f), Offset(v, a1.y + 18f), 4f) else drawLine(colour, Offset(a0.x - 18f, v), Offset(a1.x + 18f, v), 4f)
				val laid = text.measure(label, TextStyle(color = colour, fontSize = 13.sp, fontWeight = FontWeight.Bold))
				val at = if (vertical(how)) Offset(v + 6f, a0.y - 24f - laid.size.height) else Offset(a1.x + 24f, v - laid.size.height / 2)
				drawRect(Color.Black.copy(alpha = 0.55f), at, Size(laid.size.width.toFloat(), laid.size.height.toFloat()))
				drawText(laid, topLeft = at)
			}
			val g = f.glue
			if (tool == FitTool.GLUE && g != null) {
				val how = if (g.axisX) "x" else "y"; val near = if (g.axisX) "left" else "above"; val far = if (g.axisX) "right" else "below"
				val name = f.project.part(g.target ?: f.backHairId)?.name ?: "?"
				if (g.pinTop) region(how, 0.0, g.from, GlueBlue.copy(alpha = 0.35f)) else region(how, g.to, 1.0, GlueBlue.copy(alpha = 0.35f))
				if (g.to > g.from) region(how, g.from, g.to, GlueBlue.copy(alpha = 0.15f))
				if (g.pinTop) { labelled(how, g.from, "glued to $name $near", GlueBlue); labelled(how, g.to, "free $far", SwingYellow) }
				else { labelled(how, g.from, "free $near", SwingYellow); labelled(how, g.to, "glued to $name $far", GlueBlue) }
			}
			val sw = f.swing
			if (tool == FitTool.SWING && sw != null) {
				val edge = sw.edge ?: "top"
				region(edge, 0.0, sw.pivot, GlueBlue.copy(alpha = 0.18f))
				labelled(edge, sw.pivot, "swings from here", SwingYellow)
				val t = (sw.tilt ?: 0.0) * kotlin.math.PI / 180
				val (dx, dy) = when (edge) { "top" -> sin(t) to cos(t); "bottom" -> sin(t) to -cos(t); "left" -> cos(t) to sin(t); else -> -cos(t) to sin(t) }
				val v = at(edge, sw.pivot)
				val o = if (vertical(edge)) Offset(v, (a0.y + a1.y) / 2) else Offset((a0.x + a1.x) / 2, v)
				val len = (if (vertical(edge)) bw else bh) * (1 - sw.pivot.toFloat()) * 0.9f
				val tip = Offset(o.x + dx.toFloat() * len, o.y + dy.toFloat() * len)
				drawLine(SwingYellow, o, tip, 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
				val ax = atan2(dy, dx); val head = 18f
				val arrow = Path().apply { moveTo(tip.x, tip.y); lineTo(tip.x - head * cos(ax - 0.4).toFloat(), tip.y - head * sin(ax - 0.4).toFloat()); lineTo(tip.x - head * cos(ax + 0.4).toFloat(), tip.y - head * sin(ax + 0.4).toFloat()); close() }
				drawPath(arrow, SwingYellow)
			}
		}
		else -> {}
	}
	draft?.let { d -> val a = sc(Pt(d.x0, d.y0)); val b = sc(Pt(d.x1, d.y1)); drawRect(Color(0xFF3DD68C), a, Size(b.x - a.x, b.y - a.y), style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f)))) }
	brushAt?.let { (p, r) ->
		val c = sc(p); val rr = (r * l[0]).toFloat()
		val colour = when (tool) { FitTool.TRIM -> Color(0xFFFF2D55); FitTool.RESTORE -> Color(0xFF3DD68C); else -> Color.White }
		drawCircle(Color.Black.copy(alpha = 0.55f), rr, c, style = Stroke(3f)); drawCircle(colour, rr, c, style = Stroke(1.5f))
		if (feather > 0) drawCircle(colour, rr * (1 - feather / 100f), c, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GluePanel(f: FitSession) {
	val g = f.glue
	val targets = f.glueTargets
	Text("Glue to", color = Muted, fontSize = 12.sp)
	if (g == null) Text("Tap on the piece to place the glue.", color = Muted, fontSize = 13.sp)
	else {
		val current = g.target ?: f.backHairId
		FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			for (p in targets) Chip(p.name, p.id == current) { f.glue = g.copy(target = p.id) }
		}
		val name = f.project.part(current)?.name ?: "?"
		val (near, far) = if (g.axisX) "left of" to "right of" else "above" to "below"
		Text(if (g.pinTop && g.from >= 1) "The whole piece is glued to $name"
			else if (g.pinTop) "Glued to $name $near ${(g.from * 100).roundToInt()}% · hangs from it, swings on its own $far ${(g.to * 100).roundToInt()}%"
			else "Hangs from $name $near ${(g.from * 100).roundToInt()}% · glued to it $far ${(g.to * 100).roundToInt()}%", color = Muted, fontSize = 13.sp)
	}
	Text("Direction", color = Muted, fontSize = 12.sp)
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Chip("Top to bottom", g?.axisX != true, enabled = g != null) { f.glue = g?.copy(axisX = false) }
		Chip("Left to right", g?.axisX == true, enabled = g != null) { f.glue = g?.copy(axisX = true) }
	}
	Chip(if (g?.axisX == true) (if (g.pinTop) "Glue the right instead" else "Glue the left instead") else (if (g?.pinTop != false) "Glue the bottom instead" else "Glue the top instead"),
		enabled = g != null) { f.glue = g?.copy(pinTop = !g.pinTop) }
	Chip("Glue the whole piece") {
		val face = f.glueTargets.firstOrNull { StudioRig.rigName(f.project, it) == "face" }?.id
		f.glue = Glue(1.0, 1.0, true, g?.axisX ?: false, g?.target ?: face ?: f.backHairId)
	}
	Chip("Remove glue", enabled = g != null) { f.glue = null }
	if (g != null && g.from < 1 && f.swing == null) Chip("Add a swing past the glue") {
		val edge = if (g.axisX) (if (g.pinTop) "left" else "right") else (if (g.pinTop) "top" else "bottom")
		val pivot = if (g.pinTop) g.to else 1 - g.from
		f.swing = Swing.fresh(f.rigAs).copy(edge = edge, pivot = minOf(0.95, pivot))
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwingPanel(f: FitSession) {
	val sw = f.swing
	if (sw == null) { Text("Tap on the piece where it hangs from.", color = Muted, fontSize = 13.sp); return }
	Text("Hangs from", color = Muted, fontSize = 12.sp)
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		for (e in listOf("top", "left", "right", "bottom")) Chip(e.replaceFirstChar { it.uppercase() }, (sw.edge ?: "top") == e) { f.swing = sw.copy(edge = e, pivot = 0.0) }
	}
	Text("Angle ${(sw.tilt ?: 0.0).roundToInt()}°", color = Muted, fontSize = 12.sp)
	Slider((sw.tilt ?: 0.0).toFloat(), { f.swing = sw.copy(tilt = it.roundToInt().toDouble()) }, valueRange = -75f..75f, colors = sliderColors(), modifier = Modifier.height(30.dp))
	Text("Moves like", color = Muted, fontSize = 12.sp)
	FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		for ((k, label) in listOf("hair" to "Hair", "accessory" to "Accessory", "cloth" to "Cloth")) Chip(label, sw.preset == k) { f.swing = sw.copy(preset = k) }
	}
	Text("Side to side ${(sw.side * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
	Slider((sw.side * 100).toFloat(), { f.swing = sw.copy(side = it.roundToInt() / 100.0) }, valueRange = 0f..70f, colors = sliderColors(), modifier = Modifier.height(30.dp))
	Text("Up/down ${(sw.updown * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
	Slider((sw.updown * 100).toFloat(), { f.swing = sw.copy(updown = it.roundToInt() / 100.0) }, valueRange = 0f..50f, colors = sliderColors(), modifier = Modifier.height(30.dp))
	Text("Stiff root ${(sw.softness * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
	Slider((sw.softness * 100).toFloat(), { f.swing = sw.copy(softness = it.roundToInt() / 100.0) }, valueRange = 0f..100f, colors = sliderColors(), modifier = Modifier.height(30.dp))
	Text("Slowness ${if (sw.length > 0) sw.length.roundToInt().toString() else "auto"}", color = Muted, fontSize = 12.sp)
	Slider(sw.length.toFloat(), { f.swing = sw.copy(length = it.roundToInt().toDouble()) }, valueRange = 0f..16f, colors = sliderColors(), modifier = Modifier.height(30.dp))
	Chip("Remove swing") { f.swing = null }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
