package io.github.psd2live.ipad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)
private val Pick = Color(0xFFFFC46B)

/**
 * Move parts (the desktop's Select mode): tap a part on the canvas or in the list, drag it with the Pencil or a finger
 * to move it, set its size and turn about its middle, or nudge it a pixel at a time. Two fingers pan and zoom; a
 * two-finger tap undoes. Each change is one undoable edit; Done writes the rig's files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun PartsScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	var message by remember { mutableStateOf<String?>(null) }
	val safe = remember { kotlinx.coroutines.CoroutineExceptionHandler { _, e -> message = e.message ?: e::class.simpleName } }
	var parts by remember { mutableStateOf(RigParts.list(editing)) }
	var selected by remember { mutableStateOf<RigParts.Part?>(null) }
	var bounds by remember { mutableStateOf<FloatArray?>(null) }
	var drag by remember { mutableStateOf<Offset?>(null) }   // canvas units, while dragging
	var size by remember { mutableStateOf(100f) }
	var turn by remember { mutableStateOf(0f) }
	var step by remember { mutableStateOf(1f) }
	var view by remember { mutableStateOf(CanvasView()) }
	var box by remember { mutableStateOf(0f to 0f) }
	var tick by remember { mutableStateOf(0L) }
	var image by remember { mutableStateOf<Pair<ImageBitmap, FloatArray>?>(null) }
	var saving by remember { mutableStateOf<Pair<String, Double>?>(null) }
	val scope = rememberCoroutineScope()
	val selectedNow by rememberUpdatedState(selected)
	val boundsNow by rememberUpdatedState(bounds)
	val cw = editing.puppet.canvasWidth; val ch = editing.puppet.canvasHeight
	fun layout(w: Float, h: Float, v: CanvasView): FloatArray = v.layout(w, h, cw, ch)

	/** After an edit or undo: the list, the selected part's box and the picture follow. */
	fun refreshed() {
		val list = RigParts.list(editing); val sel = selectedNow?.let { s -> list.firstOrNull { it.layerId == s.layerId } }
		val b = sel?.let { RigParts.bounds(editing, it) }
		scope.launch(Dispatchers.Main) { parts = list; selected = sel; bounds = b; tick++ }
	}
	fun select(p: RigParts.Part?) { selected = p; size = 100f; turn = 0f; scope.launch(worker + safe) { val b = p?.let { RigParts.bounds(editing, it) }; scope.launch(Dispatchers.Main) { bounds = b } } }
	fun apply(label: String, delta: (FloatArray) -> io.github.psd2live.project.LayerTransform) {
		val p = selectedNow ?: return; val b = boundsNow ?: return
		message = null
		scope.launch(worker + safe) { RigParts.transform(editing, p, delta(b)); refreshed() }
	}

	LaunchedEffect(editing) {
		// Only what's on screen is drawn: a pinch moves the last frame at once, the sharp one follows when it rests.
		var lastView: CanvasView? = null
		snapshotFlow { Triple(tick, view, box) }.collectLatest { (_, v, b) ->
			val (w, h) = b
			if (w < 2f || h < 2f) return@collectLatest
			val viewOnly = lastView != null && lastView != v
			lastView = v
			if (viewOnly) delay(140)
			val l = layout(w, h, v)
			image = withContext(worker) {
				val x0 = maxOf(0f, -l[1] / l[0]); val y0 = maxOf(0f, -l[2] / l[0])
				val x1 = minOf(cw, (w - l[1]) / l[0]); val y1 = minOf(ch, (h - l[2]) / l[0])
				if (x1 - x0 < 1f || y1 - y0 < 1f) return@withContext null
				var ow = ((x1 - x0) * l[0]).roundToInt().coerceAtLeast(1); var oh = ((y1 - y0) * l[0]).roundToInt().coerceAtLeast(1)
				val k = minOf(1f, kotlin.math.sqrt(2_200_000f / (ow.toFloat() * oh))); ow = (ow * k).roundToInt().coerceAtLeast(1); oh = (oh * k).roundToInt().coerceAtLeast(1)
				editing.frame(emptyMap(), x0, y0, x1 - x0, y1 - y0, ow, oh).toImageBitmap() to floatArrayOf(x0, y0, x1, y1)
			}
		}
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
			Text("Move parts of ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (saving == null) onCancel() })
		}
		Text("Tap a part, then drag it. Its art stays the same; where it sits in the rig moves, at rest. Two fingers move and zoom.", color = Muted, fontSize = 13.sp)
		message?.let { Text(it, color = Danger, fontSize = 13.sp) }
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(820.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				if (box != (w to h)) box = w to h
				Canvas(Modifier.fillMaxSize().canvasTouch(listOf(editing, w, h)) {
					object : CanvasTouch {
						private var from: Offset? = null
						private var moving = false
						private fun toCanvas(o: Offset): Offset { val l = layout(w, h, view); return Offset((o.x - l[1]) / l[0], (o.y - l[2]) / l[0]) }
						override fun start(at: Offset, pressure: Float) {
							if (saving != null) return
							val p = toCanvas(at); from = p
							val b = boundsNow
							// On the selected part's box (with a little slack): drag it. Elsewhere: pick what's there.
							val slack = 16f / layout(w, h, view)[0]
							moving = selectedNow != null && b != null && p.x in b[0] - slack..b[2] + slack && p.y in b[1] - slack..b[3] + slack
							if (!moving) scope.launch(worker + safe) { val hit = RigParts.at(editing, p.x, p.y); scope.launch(Dispatchers.Main) { select(hit) } }
						}
						override fun move(at: Offset, pressure: Float) { val f = from ?: return; if (moving) { val p = toCanvas(at); drag = Offset(p.x - f.x, p.y - f.y) } }
						override fun end() {
							val d = drag; drag = null; from = null
							if (moving && d != null && (kotlin.math.abs(d.x) > 0.5f || kotlin.math.abs(d.y) > 0.5f)) apply("Moving") { RigParts.transformOf(d.x, d.y, 1f, 0f, 0f, 0f) }
							moving = false
						}
						override fun cancel() { drag = null; from = null; moving = false }
						override fun undo() { scope.launch(worker + safe) { editing.undo(); refreshed() } }
						override fun redo() { scope.launch(worker + safe) { editing.redo(); refreshed() } }
						override fun view() = view
						override fun setView(view: CanvasView) { viewSetter(view) }
						override fun layout(view: CanvasView) = view.layout(w, h, cw, ch)
						private val viewSetter: (CanvasView) -> Unit = { v -> view = v }
					}
				}) {
					val l = layout(w, h, view)
					image?.let { (bitmap, r) ->
						drawImage(bitmap, dstOffset = IntOffset((l[1] + r[0] * l[0]).roundToInt(), (l[2] + r[1] * l[0]).roundToInt()),
							dstSize = androidx.compose.ui.unit.IntSize(((r[2] - r[0]) * l[0]).roundToInt().coerceAtLeast(1), ((r[3] - r[1]) * l[0]).roundToInt().coerceAtLeast(1)))
					}
					bounds?.let { b ->
						// The selected part's box, following the drag; dashed where it will land.
						val d = drag ?: Offset.Zero
						val tl = Offset(l[1] + (b[0] + d.x) * l[0], l[2] + (b[1] + d.y) * l[0])
						drawRect(Pick, tl, androidx.compose.ui.geometry.Size((b[2] - b[0]) * l[0], (b[3] - b[1]) * l[0]), style = Stroke(2.5f))
					}
				}
			}
			Column(Modifier.width(380.dp).height(820.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = editing.canUndo && saving == null) { scope.launch(worker + safe) { editing.undo(); refreshed() } }
					Chip("Redo", false, enabled = editing.canRedo && saving == null) { scope.launch(worker + safe) { editing.redo(); refreshed() } }
					Chip("Fit view", false) { view = CanvasView() }
				}
				val s = saving
				if (s != null) { Text(s.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = s.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				else Chip("Done - keep ${editing.edits} change${if (editing.edits == 1) "" else "s"}", true, enabled = editing.edits > 0) {
					saving = "Writing the runtime files" to 0.2
					scope.launch(worker) {
						try { val o = editing.finish(outcome) { st, f -> scope.launch(Dispatchers.Main) { saving = st to f } }; scope.launch(Dispatchers.Main) { saving = null; onDone(o) } }
						catch (e: Throwable) { scope.launch(Dispatchers.Main) { saving = null; message = e.message ?: e::class.simpleName } }
					}
				}
				val sel = selected
				if (sel != null) {
					Text(sel.name, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					Text("Nudge", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
						Chip("←", false) { apply("Nudging") { RigParts.transformOf(-step, 0f, 1f, 0f, 0f, 0f) } }
						Chip("↑", false) { apply("Nudging") { RigParts.transformOf(0f, -step, 1f, 0f, 0f, 0f) } }
						Chip("↓", false) { apply("Nudging") { RigParts.transformOf(0f, step, 1f, 0f, 0f, 0f) } }
						Chip("→", false) { apply("Nudging") { RigParts.transformOf(step, 0f, 1f, 0f, 0f, 0f) } }
						for (n in listOf(1f, 10f)) Chip("${n.roundToInt()} px", step == n) { step = n }
					}
					Text("Size  ${size.roundToInt()}%", color = Muted, fontSize = 12.sp)
					Slider(size, { size = it }, valueRange = 25f..300f, colors = sliderColors(), modifier = Modifier.height(28.dp))
					Text("Turn  ${turn.roundToInt()}°", color = Muted, fontSize = 12.sp)
					Slider(turn, { turn = (it / 5f).roundToInt() * 5f }, valueRange = -180f..180f, colors = sliderColors(), modifier = Modifier.height(28.dp))
					Chip("Apply size and turn (about its middle)", true, enabled = size != 100f || turn != 0f) {
						val k = size / 100f; val t = turn
						apply("Sizing") { b -> RigParts.transformOf(0f, 0f, k, t, (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f) }
						size = 100f; turn = 0f
					}
				} else Text("Tap a part on the picture, or pick one below.", color = Muted, fontSize = 13.sp)
				Text("Parts (front first)", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
				for (p in parts) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (p.layerId == selected?.layerId) Color(0xFF3A3358) else Color(0xFF24222F))
					.clickable { select(p) }.padding(horizontal = 12.dp, vertical = 8.dp)) { Text(p.name, color = Text1, fontSize = 14.sp) }
			}
		}
	}
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF5B4B8A) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
		Text(label, color = if (enabled) Text1 else Muted, fontSize = 13.sp)
	}
}
