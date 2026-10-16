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
private val Pivot = Color(0xFF8FE0A8)

/**
 * Add deformers (the desktop's Edit mode create tools): tap the parts that should move together, then add a warp
 * around them (to bend them as one) or a rotation (tap where it pivots, to swing or turn them). The parts keep their
 * look; the new deformer is then posed in Tweak by hand or driven by a parameter. Undo/redo; Done writes the rig.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun StructureScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	var message by remember { mutableStateOf<String?>(null) }
	var info by remember { mutableStateOf<String?>(null) }
	val safe = remember { kotlinx.coroutines.CoroutineExceptionHandler { _, e -> message = e.message ?: e::class.simpleName } }
	var parts by remember { mutableStateOf(RigParts.list(editing)) }
	var chosen by remember { mutableStateOf<List<RigParts.Part>>(emptyList()) }
	var boxes by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
	var pivotMode by remember { mutableStateOf(false) }
	val pivotNow by rememberUpdatedState(pivotMode)
	val chosenNow by rememberUpdatedState(chosen)
	var grid by remember { mutableStateOf(5) }
	var name by remember { mutableStateOf("") }
	var view by remember { mutableStateOf(CanvasView()) }
	var box by remember { mutableStateOf(0f to 0f) }
	var tick by remember { mutableStateOf(0L) }
	var image by remember { mutableStateOf<Pair<ImageBitmap, FloatArray>?>(null) }
	var saving by remember { mutableStateOf<Pair<String, Double>?>(null) }
	val scope = rememberCoroutineScope()
	val cw = editing.puppet.canvasWidth; val ch = editing.puppet.canvasHeight
	fun layout(w: Float, h: Float, v: CanvasView): FloatArray = v.layout(w, h, cw, ch)

	fun describe(list: List<RigParts.Part>) {
		scope.launch(worker + safe) {
			val b = list.mapNotNull { RigParts.bounds(editing, it) }
			val meshes = list.flatMap { it.meshes }
			val holder = meshes.mapNotNull { RigStructure.parentName(editing, it) }.distinct()
			val problem = RigStructure.problem(editing, meshes)
			scope.launch(Dispatchers.Main) {
				boxes = b
				info = problem ?: if (holder.isEmpty()) null else "Hangs from: ${holder.joinToString()}"
			}
		}
	}
	fun refreshed() {
		val list = RigParts.list(editing); val ch2 = chosenNow.mapNotNull { c -> list.firstOrNull { it.layerId == c.layerId } }
		scope.launch(Dispatchers.Main) { parts = list; chosen = ch2; tick++; describe(ch2) }
	}
	fun toggle(p: RigParts.Part) {
		val next = if (chosen.any { it.layerId == p.layerId }) chosen.filter { it.layerId != p.layerId } else chosen + p
		chosen = next; describe(next)
	}

	LaunchedEffect(editing) {
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
			Text("Add deformers to ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (saving == null) onCancel() })
		}
		HowTo("How deformers work", listOf(
			"Tap the parts that should move together (they outline in colour).",
			"Warp: pick a grid size and tap Add a warp around them. Rotation: tap Add a rotation, then tap where it pivots.",
			"Tap Done. This only makes the cage - nothing moves yet.",
			"Open Tweak by hand, choose Warp, pose her with a slider (e.g. Angle X right) and drag the cage. That shape belongs to that pose."))
		Text(if (pivotMode) "Tap where the rotation should pivot (a shoulder, the top of a charm...)." else
			"Tap the parts that should move together. A warp bends them as one; a rotation swings or turns them. Two fingers move and zoom.",
			color = if (pivotMode) Pivot else Muted, fontSize = 13.sp)
		message?.let { Text(it, color = Danger, fontSize = 13.sp) }
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(820.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				if (box != (w to h)) box = w to h
				Canvas(Modifier.fillMaxSize().canvasTouch(listOf(editing, w, h)) {
					object : CanvasTouch {
						private var at0: Offset? = null
						private fun toCanvas(o: Offset): Offset { val l = layout(w, h, view); return Offset((o.x - l[1]) / l[0], (o.y - l[2]) / l[0]) }
						override fun start(at: Offset, pressure: Float) { at0 = at }
						override fun move(at: Offset, pressure: Float) {}
						override fun end() {
							val a = at0 ?: return; at0 = null
							if (saving != null) return
							val p = toCanvas(a)
							if (pivotNow) {
								val meshes = chosenNow.flatMap { it.meshes }
								val label = name.ifBlank { (chosenNow.firstOrNull()?.name ?: "Part") + " rotation" }
								pivotMode = false; message = null
								scope.launch(worker + safe) { RigStructure.addRotation(editing, meshes, label, p.x, p.y); refreshed() }
								return
							}
							scope.launch(worker + safe) { val hit = RigParts.at(editing, p.x, p.y); scope.launch(Dispatchers.Main) { hit?.let { toggle(it) } } }
						}
						override fun cancel() { at0 = null }
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
					for (b in boxes) drawRect(if (pivotMode) Pivot else Pick, Offset(l[1] + b[0] * l[0], l[2] + b[1] * l[0]),
						androidx.compose.ui.geometry.Size((b[2] - b[0]) * l[0], (b[3] - b[1]) * l[0]), style = Stroke(2.5f))
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
				Text(if (chosen.isEmpty()) "Nothing picked yet." else "Picked: ${chosen.joinToString { it.name }}", color = Text1, fontSize = 14.sp)
				info?.let { Text(it, color = Muted, fontSize = 12.sp) }
				if (chosen.isNotEmpty()) Chip("Clear the picks", false) { chosen = emptyList(); boxes = emptyList(); info = null; pivotMode = false }
				Text("Name (optional)", color = Muted, fontSize = 12.sp)
				androidx.compose.foundation.text.BasicTextField(name, { name = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Text1, fontSize = 15.sp),
					cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B283C)).padding(10.dp))
				Text("Warp grid", color = Muted, fontSize = 12.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (g in listOf(3, 5, 8)) Chip("${g}x$g", grid == g) { grid = g } }
				val ok = chosen.isNotEmpty() && saving == null && info?.startsWith("These parts") != true
				Chip("Add a warp around them", true, enabled = ok) {
					val meshes = chosen.flatMap { it.meshes }; val label = name.ifBlank { (chosen.first().name) + " warp" }; val g = grid
					message = null
					scope.launch(worker + safe) { RigStructure.addWarp(editing, meshes, label, g, g); refreshed() }
				}
				Chip(if (pivotMode) "Cancel placing the pivot" else "Add a rotation (then tap its pivot)", pivotMode, enabled = ok || pivotMode) { pivotMode = !pivotMode }
				Text("Parts (front first)", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
				for (p in parts) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (chosen.any { it.layerId == p.layerId }) Color(0xFF3A3358) else Color(0xFF24222F))
					.clickable { toggle(p) }.padding(horizontal = 12.dp, vertical = 8.dp)) { Text(p.name, color = Text1, fontSize = 14.sp) }
			}
		}
	}
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF5B4B8A) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
		Text(label, color = if (enabled) Text1 else Muted, fontSize = 13.sp)
	}
}

/** A numbered how-to shown at the top of a screen: what to do, in order, and where the result goes next. */
@Composable
internal fun HowTo(title: String, steps: List<String>) {
	androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF24222F)).padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(title, color = Color(0xFFF2ECFD), fontSize = 13.sp, fontWeight = FontWeight.Bold)
		for ((i, s) in steps.withIndex()) Text("${i + 1}.  $s", color = Color(0xFFC8C3D8), fontSize = 13.sp)
	}
}
