package io.github.psd2live.ipad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)
private val Warn = Color(0xFFFFC46B)

private enum class PaintTool(val label: String) { BRUSH("Brush"), ERASER("Eraser"), FILL("Fill"), PICK("Pick"), SHAPE("Shape"), MASK("Mask") }

private enum class MaskMode(val label: String) { PAINT("Mask"), UNMASK("Unmask"), COLOUR("By colour") }

private val Swatches = listOf(
	0xFF000000, 0xFFFFFFFF, 0xFF6B6B7A, 0xFFF6D7C3, 0xFFE8B49A, 0xFF8A5A44, 0xFFE0453A, 0xFFF29A38,
	0xFFF5D547, 0xFF5FBF6A, 0xFF4A90D9, 0xFF6A5ACD, 0xFFB57EDC, 0xFFF4A6C8, 0xFF2E2440, 0xFFC9B7FF,
).map { it.toInt() }

/**
 * Painting a rigged PSD's layers on the iPad: pick a layer, paint it with the desktop's engine (brush, eraser, bucket,
 * undo), switch to other layers and paint them too (each keeps its paint and undo), then apply once - the rig is rebuilt
 * with every painted layer's pixels and keeps its meshes.
 */
@Composable
fun PaintScreen(outcome: RigOutcome, onApplied: (RigOutcome) -> Unit, onClose: () -> Unit) {
	var painting by remember(outcome) { mutableStateOf<LayerPainting?>(null) }
	val layers = remember(outcome) { paintableLayers(outcome) }
	// Every layer opened so far, with its paint and undo, so switching layers keeps the work.
	val paintings = remember(outcome) { mutableMapOf<String, LayerPainting>() }
	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		val current = painting
		if (current == null) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
				Text("Paint ${outcome.name}: choose a layer", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
				// With paint not yet applied, leaving takes a second tap: "Back" sits where "Choose another layer" was, so a
				// double tap there would otherwise throw the paint away.
				var confirmBack by remember { mutableStateOf(false) }
				val unapplied = paintings.values.any { it.isDirty }
				Text(when { !unapplied -> "Back to the rig"; confirmBack -> "Tap again to drop the paint"; else -> "Back to the rig…" },
					color = if (confirmBack) Danger else Accent, fontSize = 15.sp,
					modifier = Modifier.clickable { if (!unapplied || confirmBack) onClose() else confirmBack = true })
			}
			ApplyPaint(outcome, paintings.values.toList(), onApplied)
			LayerChooser(outcome, layers, paintings) { layer -> painting = paintings.getOrPut(layer.id.raw) { LayerPainting(outcome, layer) } }
		} else {
			LayerPaintView(current, paintings.values.toList(), onApplied, onBack = { painting = null })
		}
	}
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
private fun LayerPaintView(painting: LayerPainting, all: List<LayerPainting>, onApplied: (RigOutcome) -> Unit, onBack: () -> Unit) {
	var tool by remember { mutableStateOf(PaintTool.BRUSH) }
	var radius by remember { mutableStateOf(12f) }
	var hardness by remember { mutableStateOf(0.7f) }
	var opacity by remember { mutableStateOf(1f) }
	var color by remember { mutableStateOf(Swatches[0]) }
	var pressureSize by remember { mutableStateOf(true) }
	var shape by remember { mutableStateOf(io.github.psd2live.core.RasterPaintShape.LINE) }
	var shapeFilled by remember { mutableStateOf(false) }
	var shapeWidth by remember { mutableStateOf(4f) }
	var maskMode by remember { mutableStateOf(MaskMode.PAINT) }
	var pieceName by remember(painting) { mutableStateOf("${painting.layer.name} part") }
	val shapeNow by rememberUpdatedState(Triple(shape, shapeFilled, shapeWidth))
	val maskModeNow by rememberUpdatedState(maskMode)
	var ghost by remember { mutableStateOf(true) }
	var view by remember { mutableStateOf(CanvasView()) }
	var tick by remember { mutableStateOf(0L) }
	var frame by remember { mutableStateOf<PaintFrame?>(null) }
	var box by remember { mutableStateOf(0f to 0f) }
	var cursor by remember { mutableStateOf<Pair<Offset, Float>?>(null) }
	var applying by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	// A failed edit says so instead of closing the app.
	var workError by remember { mutableStateOf<String?>(null) }
	// Whether the paint reaches past the layer's edge (it then gets a new mesh on Apply): worked out on the paint
	// worker, after strokes, once they rest.
	var pastEdge by remember(painting) { mutableStateOf(painting.pastEdgeKnown == true) }
	var keepMesh by remember(painting) { mutableStateOf(painting.keepMesh) }
	val safe = remember { kotlinx.coroutines.CoroutineExceptionHandler { _, e -> workError = e.message ?: e::class.simpleName } }
	val current by rememberUpdatedState(PaintSettings(tool, radius, hardness, opacity, color))
	val pressureOn by rememberUpdatedState(pressureSize)
	val rest = remember(painting) { painting.outcome.preview.parameters.associate { it.id to it.default } }
	fun bump() { scope.launch(Dispatchers.Main) { tick++ } }
	fun layout(w: Float, h: Float, v: CanvasView) = v.layout(w, h, painting.canvasWidth.toFloat(), painting.canvasHeight.toFloat())

	LaunchedEffect(painting) {
		// Only what is on screen is drawn, at screen resolution: a draft while things move, sharp once they rest.
		var lastView: CanvasView? = null
		snapshotFlow { listOf(tick, view, box, ghost, tool == PaintTool.MASK || painting.hasMask) }.collectLatest {
			val (w, h) = box
			if (w < 2f || h < 2f) return@collectLatest
			// A pinch or pan moves the last frame at once (it knows its canvas region); re-render once the fingers rest.
			val viewOnly = lastView != null && lastView != view
			lastView = view
			val l = layout(w, h, view); val showGhost = ghost; val showMask = tool == PaintTool.MASK || painting.hasMask
			suspend fun draw(budget: Float): PaintFrame? = withContext(Dispatchers.Default) {
				val scale = l[0]; val left = l[1]; val top = l[2]
				val x0 = maxOf(0f, -left / scale); val y0 = maxOf(0f, -top / scale)
				val x1 = minOf(painting.canvasWidth.toFloat(), (w - left) / scale); val y1 = minOf(painting.canvasHeight.toFloat(), (h - top) / scale)
				if (x1 - x0 < 1f || y1 - y0 < 1f) return@withContext null
				var outW = ((x1 - x0) * scale).roundToInt().coerceAtLeast(1); var outH = ((y1 - y0) * scale).roundToInt().coerceAtLeast(1)
				val shrink = minOf(1f, kotlin.math.sqrt(budget / (outW.toFloat() * outH)))
				outW = (outW * shrink).roundToInt().coerceAtLeast(1); outH = (outH * shrink).roundToInt().coerceAtLeast(1)
				val at = floatArrayOf(x0, y0, x1, y1)
				val sp = painting.space
				val layer = painting.image.regionBitmap(sp.canvasToImageX(x0), sp.canvasToImageY(y0),
					sp.canvasToImageX(x1) - sp.canvasToImageX(x0), sp.canvasToImageY(y1) - sp.canvasToImageY(y0), outW, outH)
				val under = if (!showGhost) null else painting.outcome.preview.render(rest, x0, y0, x1 - x0, y1 - y0, outW, outH).toImageBitmap()
				val maskBitmap = if (!showMask) null else painting.mask.regionBitmap(sp.canvasToImageX(x0), sp.canvasToImageY(y0),
					sp.canvasToImageX(x1) - sp.canvasToImageX(x0), sp.canvasToImageY(y1) - sp.canvasToImageY(y0), outW, outH)
				PaintFrame(layer, under, at, maskBitmap)
			}
			if (viewOnly) { delay(140); frame = draw(2_200_000f); return@collectLatest }
			frame = draw(300_000f)
			delay(160)
			frame = draw(2_200_000f)
		}
	}

	LaunchedEffect(painting, tick) {
		if (!painting.isDirty) { pastEdge = false; return@LaunchedEffect }
		delay(400)
		pastEdge = withContext(worker) { painting.pastEdge() }
	}

	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		Text("Painting ${painting.layer.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
		Text(if (painting.isDirty) "Choose another layer (this paint is kept)" else "Choose another layer", color = Accent, fontSize = 15.sp,
			modifier = Modifier.clickable { if (applying == null) onBack() })
	}
	Text("Pencil or a finger paints; two fingers move and zoom; tap two fingers to undo, three to redo.", color = Muted, fontSize = 13.sp)
	workError?.let { Text("Something went wrong: $it", color = Danger, fontSize = 13.sp) }
	Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
		BoxWithConstraints(Modifier.weight(1f).height(860.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
			val density = LocalDensity.current
			val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
			if (box != (w to h)) box = w to h
			Canvas(Modifier.fillMaxSize().canvasTouch(listOf(painting, w, h)) {
				object : CanvasTouch {
					private var last: Offset? = null
					private fun toCanvas(o: Offset): Offset { val l = layout(w, h, view); return Offset((o.x - l[1]) / l[0], (o.y - l[2]) / l[0]) }
					private fun size(pressure: Float, s: PaintSettings) = if (pressureOn) s.radius * (0.2f + 0.8f * pressure) else s.radius
					override fun start(at: Offset, pressure: Float) {
						if (applying != null) return
						val s = current; val p = toCanvas(at)
						when (s.tool) {
							PaintTool.SHAPE -> { last = p; scope.launch(worker + safe) { painting.shapeBegin(p.x, p.y) } }
							PaintTool.MASK -> when (maskModeNow) {
								MaskMode.COLOUR -> scope.launch(worker + safe) { painting.maskByColour(p.x, p.y, 24); bump() }
								else -> {
									val r = size(pressure, s); last = p; cursor = at to r
									scope.launch(worker + safe) { painting.maskSegment(p.x, p.y, p.x, p.y, r, s.hardness, maskModeNow == MaskMode.UNMASK); bump() }
								}
							}
							PaintTool.FILL -> { rememberColourUse(s.color); scope.launch(worker + safe) { painting.fill(p.x, p.y, s.color, s.opacity, 24); bump() } }
							PaintTool.PICK -> scope.launch(worker + safe) {
								// The layer's own colour where it has one, else what the character shows there.
								val own = painting.pixelAt(p.x, p.y)
								val picked = if ((own ushr 24) > 16) own else painting.outcome.preview.render(rest, p.x, p.y, 1f, 1f, 1, 1).argb[0]
								if ((picked ushr 24) > 0) scope.launch(Dispatchers.Main) { color = picked or (0xFF shl 24); tool = PaintTool.BRUSH }
							}
							else -> {
								val r = size(pressure, s); last = p; cursor = at to r
								scope.launch(worker + safe) {
									painting.beginStroke()
									painting.segment(p.x, p.y, p.x, p.y, r, s.hardness, s.color, s.opacity, s.tool == PaintTool.ERASER); bump()
								}
							}
						}
					}
					override fun move(at: Offset, pressure: Float) {
						val s = current; val from = last ?: return; val p = toCanvas(at); val r = size(pressure, s)
						if (s.tool == PaintTool.SHAPE) {
							val (kind, filled, width) = shapeNow
							scope.launch(worker + safe) { painting.shapeTo(p.x, p.y, kind, s.color, s.opacity, width, filled); bump() }
							return
						}
						if (s.tool == PaintTool.MASK) {
							last = p; cursor = at to r
							scope.launch(worker + safe) { painting.maskSegment(from.x, from.y, p.x, p.y, r, s.hardness, maskModeNow == MaskMode.UNMASK); bump() }
							return
						}
						last = p; cursor = at to r
						scope.launch(worker + safe) { painting.segment(from.x, from.y, p.x, p.y, r, s.hardness, s.color, s.opacity, s.tool == PaintTool.ERASER); bump() }
					}
					override fun end() {
						val s = current; cursor = null
						if (last != null) {
							last = null
							scope.launch(worker + safe) {
								when (s.tool) { PaintTool.SHAPE -> painting.shapeEnd(); PaintTool.MASK -> painting.maskEnd(); else -> painting.endStroke(s.tool.label) }
								if (s.tool == PaintTool.BRUSH || s.tool == PaintTool.SHAPE) scope.launch(Dispatchers.Main) { rememberColourUse(s.color) }
								bump()
							}
						}
					}
					override fun cancel() { cursor = null; last = null; scope.launch(worker + safe) { painting.cancelStroke(); painting.maskCancel(); bump() } }
					override fun undo() { scope.launch(worker + safe) { if (current.tool == PaintTool.MASK) painting.undoMask() else painting.undo(); bump() } }
					override fun redo() { scope.launch(worker + safe) { if (current.tool == PaintTool.MASK) painting.redoMask() else painting.redo(); bump() } }
					override fun view() = view
					override fun setView(view: CanvasView) { setPaintView(view) }
					override fun layout(view: CanvasView) = layout(w, h, view)
					private fun setPaintView(v: CanvasView) { view = v }
				}
			}) {
				val f = frame
				val l = layout(w, h, view)
				val cw = painting.canvasWidth * l[0]; val ch = painting.canvasHeight * l[0]
				// A checkerboard under the canvas, so transparent pixels read as transparent.
				clipRect(maxOf(0f, l[1]), maxOf(0f, l[2]), minOf(size.width, l[1] + cw), minOf(size.height, l[2] + ch)) {
					val cell = 24f
					var y = maxOf(0f, l[2]); var row = 0
					while (y < minOf(size.height, l[2] + ch)) {
						var x = maxOf(0f, l[1]); var col = 0
						while (x < minOf(size.width, l[1] + cw)) {
							drawRect(if ((row + col) % 2 == 0) Color(0xFF2A2A33) else Color(0xFF34343F), Offset(x, y), Size(cell, cell))
							x += cell; col++
						}
						y += cell; row++
					}
				}
				if (f != null) {
					val dst = IntOffset((l[1] + f.at[0] * l[0]).roundToInt(), (l[2] + f.at[1] * l[0]).roundToInt())
					val dsz = IntSize(((f.at[2] - f.at[0]) * l[0]).roundToInt().coerceAtLeast(1), ((f.at[3] - f.at[1]) * l[0]).roundToInt().coerceAtLeast(1))
					f.under?.let { drawImage(it, dstOffset = dst, dstSize = dsz, alpha = 0.3f) }
					drawImage(f.layer, dstOffset = dst, dstSize = dsz)
					f.mask?.let { drawImage(it, dstOffset = dst, dstSize = dsz, alpha = 0.5f) }
				}
				cursor?.let { (c, r) ->
					drawCircle(Color.White.copy(alpha = 0.8f), r * l[0], c, style = Stroke(1.5f))
					drawCircle(Color.Black.copy(alpha = 0.5f), r * l[0] + 1.5f, c, style = Stroke(1f))
				}
			}
			if (frame == null) Text("Drawing…", color = Muted, modifier = Modifier.align(Alignment.Center))
		}
		Column(Modifier.width(320.dp).height(860.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			// Read on every change, so undo, redo and apply follow the session.
			@Suppress("UNUSED_VARIABLE") val changes = tick
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (t in PaintTool.entries.take(4)) Chip(t.label, tool == t) { tool = t } }
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (t in PaintTool.entries.drop(4)) Chip(t.label, tool == t) { tool = t } }
			if (tool == PaintTool.SHAPE) {
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					for ((kind, label) in listOf(io.github.psd2live.core.RasterPaintShape.LINE to "Line", io.github.psd2live.core.RasterPaintShape.RECTANGLE to "Rectangle",
						io.github.psd2live.core.RasterPaintShape.ELLIPSE to "Ellipse")) Chip(label, shape == kind) { shape = kind }
				}
				if (shape != io.github.psd2live.core.RasterPaintShape.LINE) Chip(if (shapeFilled) "Filled" else "Outline", shapeFilled) { shapeFilled = !shapeFilled }
				Text("Line width  ${shapeWidth.roundToInt()} px", color = Muted, fontSize = 12.sp)
				Slider(shapeWidth, { shapeWidth = it }, valueRange = 1f..60f, colors = sliderColors(), modifier = Modifier.height(32.dp))
			}
			if (tool == PaintTool.MASK) {
				Text("Paint over the part to cut out (red). Soft edges split pixels between the two layers, so nothing along the edge is lost.",
					color = Muted, fontSize = 12.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (m in MaskMode.entries) Chip(m.label, maskMode == m) { maskMode = m } }
				if (maskMode != MaskMode.COLOUR) {
					Text("Size  ${radius.roundToInt() * 2} px", color = Muted, fontSize = 12.sp)
					Slider(radius, { radius = it }, valueRange = 0.5f..150f, colors = sliderColors(), modifier = Modifier.height(32.dp))
					Text("Edge softness  ${((1f - hardness) * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
					Slider(1f - hardness, { hardness = 1f - it }, valueRange = 0f..1f, colors = sliderColors(), modifier = Modifier.height(32.dp))
				} else Text("Tap a colour region of the layer to add it to the mask.", color = Muted, fontSize = 12.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = painting.canUndoMask) { scope.launch(worker + safe) { painting.undoMask(); bump() } }
					Chip("Redo", false, enabled = painting.canRedoMask) { scope.launch(worker + safe) { painting.redoMask(); bump() } }
					Chip("Clear mask", false, enabled = painting.hasMask) { scope.launch(worker + safe) { painting.clearMask(); bump() } }
				}
				Text("New layer's name", color = Muted, fontSize = 12.sp)
				androidx.compose.foundation.text.BasicTextField(pieceName, { pieceName = it }, singleLine = true,
					textStyle = androidx.compose.ui.text.TextStyle(color = Text1, fontSize = 15.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
					modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B283C)).padding(10.dp))
				// A cut re-rigs from this layer alone, so other layers' paint would be lost: apply that first.
				val otherPaint = all.any { it !== painting && it.isDirty }
				if (otherPaint) Text("Apply the other painted layers first: cutting rebuilds the rig from this layer.", color = Muted, fontSize = 12.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Cut to a new layer", true, enabled = painting.hasMask && applying == null && !otherPaint) {
						applying = "Cutting" to 0.0; failure = null
						scope.launch {
							try {
								val updated = withContext(worker) {
									painting.cutToNewLayer(pieceName) { stage, fraction -> scope.launch(Dispatchers.Main) { if (applying != null) applying = stage to fraction } }
								}
								applying = null; onApplied(updated)
							} catch (error: Throwable) { applying = null; failure = error.message ?: error::class.simpleName }
						}
					}
					Chip("Erase the masked part", false, enabled = painting.hasMask) { scope.launch(worker + safe) { painting.eraseMasked(); bump() } }
				}
			}
			if (tool == PaintTool.BRUSH || tool == PaintTool.ERASER) {
				Text("Size  ${radius.roundToInt() * 2} px", color = Muted, fontSize = 12.sp)
				Slider(radius, { radius = it }, valueRange = 0.5f..150f, colors = sliderColors(), modifier = Modifier.height(32.dp))
				Text("Hardness  ${(hardness * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
				Slider(hardness, { hardness = it }, valueRange = 0f..1f, colors = sliderColors(), modifier = Modifier.height(32.dp))
				Chip(if (pressureSize) "Pressure sets the size: on" else "Pressure sets the size: off", pressureSize) { pressureSize = !pressureSize }
			}
			Text("Opacity  ${(opacity * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
			Slider(opacity, { opacity = it }, valueRange = 0.02f..1f, colors = sliderColors(), modifier = Modifier.height(32.dp))
			if (tool != PaintTool.ERASER && tool != PaintTool.MASK) {
				ColourPicker(color) { color = it }
				Text("Swatches", color = Muted, fontSize = 12.sp)
				for (chunk in Swatches.chunked(8)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					for (swatch in chunk) Box(Modifier.size(30.dp).clip(CircleShape).background(Color(swatch))
						.border(if (swatch == color) 3.dp else 1.dp, if (swatch == color) Accent else Color(0xFF55526A), CircleShape)
						.clickable { color = swatch })
				}
			}
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Chip("Undo", false, enabled = painting.canUndo) { scope.launch(worker + safe) { painting.undo(); bump() } }
				Chip("Redo", false, enabled = painting.canRedo) { scope.launch(worker + safe) { painting.redo(); bump() } }
				Chip(if (ghost) "Hide the rest" else "Show the rest", false) { ghost = !ghost }
			}
			Chip("Fit view", false) { view = CanvasView() }
			Spacer(Modifier.height(8.dp))
			val progress = applying
			if (progress != null) {
				Text(progress.first, color = Muted, fontSize = 13.sp)
				LinearProgressIndicator(progress = progress.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent)
			} else {
				val dirty = all.filter { it.isDirty }
				if (painting.isDirty && pastEdge) {
					Text(if (keepMesh) "Paint past ${painting.layer.name}'s edge will be cut off: it keeps its old mesh."
						else "The paint goes past ${painting.layer.name}'s edge, so Apply gives it a new mesh. Its hand tweaks, paths and weights carry over.",
						color = Warn, fontSize = 12.sp)
					Chip(if (keepMesh) "Give it a new mesh" else "Keep the old mesh (cut the paint off)", false) {
						keepMesh = !keepMesh; painting.keepMesh = keepMesh
					}
				}
				Chip(if (dirty.size > 1) "Apply ${dirty.size} painted layers to the rig" else "Apply to the rig", true, enabled = dirty.isNotEmpty()) {
					applying = "Starting" to 0.0; failure = null
					scope.launch {
						try {
							val updated = withContext(worker) {
								applyPaintings(painting.outcome, dirty) { stage, fraction -> scope.launch(Dispatchers.Main) { if (applying != null) applying = stage to fraction } }
							}
							applying = null
							onApplied(updated)
						} catch (error: Throwable) {
							applying = null; failure = error.message ?: error::class.simpleName
						}
					}
				}
				Text(if (dirty.size > 1) "The rig is rebuilt once with every painted layer (${dirty.joinToString { it.layer.name }}); its meshes and deformers stay as they are."
					else "The rig is rebuilt with the new pixels; its meshes and deformers stay as they are. Other layers can be painted first and applied together.",
					color = Muted, fontSize = 12.sp)
			}
			failure?.let { Text("Couldn't apply: $it", color = Danger, fontSize = 13.sp) }
		}
	}
}

/**
 * Choosing the layer to paint: the character on the left with the chosen layer at full strength and the rest faded,
 * the layers on the right with a thumbnail each. Tap a row or tap her (the layer under the finger), then Paint.
 */
@Composable
private fun LayerChooser(outcome: RigOutcome, layers: List<org.umamo.format.art.SourceLayer>, paintings: Map<String, LayerPainting>,
	onPaint: (org.umamo.format.art.SourceLayer) -> Unit) {
	var chosen by remember(outcome) { mutableStateOf(layers.firstOrNull()) }
	var character by remember(outcome) { mutableStateOf<ImageBitmap?>(null) }
	var highlight by remember { mutableStateOf<ImageBitmap?>(null) }
	val thumbs = remember(outcome) { androidx.compose.runtime.mutableStateMapOf<String, ImageBitmap>() }
	val cw = outcome.source.widthPx.toFloat(); val ch = outcome.source.heightPx.toFloat()
	LaunchedEffect(outcome) {
		character = withContext(Dispatchers.Default) { outcome.preview.render(outcome.preview.parameters.associate { it.id to it.default }, 1200).toImageBitmap() }
		for (layer in layers) withContext(Dispatchers.Default) { layerPicture(layer, 96) }?.let { thumbs[layer.id.raw] = it }
	}
	LaunchedEffect(chosen) { highlight = chosen?.let { l -> withContext(Dispatchers.Default) { layerPicture(l, 1200) } } }
	Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
		BoxWithConstraints(Modifier.weight(1f).height(820.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
			val density = LocalDensity.current
			val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
			val scale = minOf(w / cw, h / ch); val left = (w - cw * scale) / 2f; val top0 = (h - ch * scale) / 2f
			Canvas(Modifier.fillMaxSize().pointerInput(layers, w, h) {
				detectTapGestures { o -> layerAt(layers, (o.x - left) / scale, (o.y - top0) / scale)?.let { chosen = it } }
			}) {
				character?.let { drawImage(it, dstOffset = IntOffset(left.roundToInt(), top0.roundToInt()),
					dstSize = IntSize((cw * scale).roundToInt(), (ch * scale).roundToInt()), alpha = if (chosen != null) 0.3f else 1f) }
				val l = chosen; val img = highlight
				if (l != null && img != null) {
					val b = l.bounds
					val x = left + b.left * scale; val y = top0 + b.top * scale; val bw = b.width * scale; val bh = b.height * scale
					drawImage(img, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(bw.roundToInt().coerceAtLeast(1), bh.roundToInt().coerceAtLeast(1)))
					drawRect(Accent, Offset(x - 3f, y - 3f), Size(bw + 6f, bh + 6f), style = Stroke(2f))
				}
			}
			if (character == null) Text("Drawing…", color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
		}
		Column(Modifier.width(380.dp).height(820.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			val c = chosen
			Text(if (c == null) "Tap a layer, here or on her" else "Chosen: ${c.name}", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
			Text("The chosen layer shows at full strength on the left; everything else is faded. Tap her to pick the layer under your finger.",
				color = Muted, fontSize = 12.sp)
			Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (c != null) Color(0xFF4A3F78) else Color(0xFF2B283C))
				.clickable(enabled = c != null) { c?.let(onPaint) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
				Text(if (c == null) "Paint" else "Paint ${c.name}", color = if (c != null) Accent else Muted, fontSize = 15.sp)
			}
			Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				for (layer in layers) {
					val painted = paintings[layer.id.raw]?.isDirty == true
					val p = paintings[layer.id.raw]
					val newMesh = painted && p?.pastEdgeKnown == true && !p.keepMesh
					val on = layer.id == c?.id
					Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
						.background(when { on -> Color(0xFF4A3F78); painted -> Color(0xFF332C4E); else -> Color(0xFF24222F) })
						.clickable { if (on) onPaint(layer) else chosen = layer }.padding(horizontal = 10.dp, vertical = 6.dp),
						verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
						Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A3650)), contentAlignment = Alignment.Center) {
							thumbs[layer.id.raw]?.let { androidx.compose.foundation.Image(it, layer.name, Modifier.fillMaxSize().padding(2.dp),
								contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
						}
						Column {
							Text(when { newMesh -> "${layer.name}  ·  painted, new mesh"; painted -> "${layer.name}  ·  painted"; else -> layer.name },
								color = if (painted || on) Accent else Text1, fontSize = 15.sp)
							if (layer.groupPath.isNotBlank()) Text(layer.groupPath, color = Muted, fontSize = 12.sp)
						}
					}
				}
			}
		}
	}
}

/** The layer list's "apply every painted layer" button, with its progress; nothing shows until something is painted. */
@Composable
private fun ApplyPaint(outcome: RigOutcome, paintings: List<LayerPainting>, onApplied: (RigOutcome) -> Unit) {
	val dirty = paintings.filter { it.isDirty }
	if (dirty.isEmpty()) return
	var applying by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val progress = applying
	if (progress != null) {
		Text(progress.first, color = Muted, fontSize = 13.sp)
		LinearProgressIndicator(progress = progress.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent)
	} else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		Chip(if (dirty.size > 1) "Apply ${dirty.size} painted layers to the rig" else "Apply the painted layer to the rig", true) {
			applying = "Starting" to 0.0; failure = null
			scope.launch {
				try {
					val updated = withContext(Dispatchers.Default) {
						applyPaintings(outcome, dirty) { stage, fraction -> scope.launch(Dispatchers.Main) { if (applying != null) applying = stage to fraction } }
					}
					applying = null; onApplied(updated)
				} catch (error: Throwable) { applying = null; failure = error.message ?: error::class.simpleName }
			}
		}
		Text(dirty.joinToString { it.layer.name }, color = Muted, fontSize = 13.sp)
	}
	failure?.let { Text("Couldn't apply: $it", color = Danger, fontSize = 13.sp) }
}

/** What the canvas shows: the layer and, faintly, the character under it, both covering [at] (x, y, w, h on screen). */
private class PaintFrame(val layer: ImageBitmap, val under: ImageBitmap?, val at: FloatArray, val mask: ImageBitmap? = null)

private class PaintSettings(val tool: PaintTool, val radius: Float, val hardness: Float, val opacity: Float, val color: Int)

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
