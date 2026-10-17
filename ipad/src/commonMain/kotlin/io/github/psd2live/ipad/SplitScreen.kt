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
private val Second = Color(0xFF8FE0A8)

private enum class SplitTool { ISLANDS, LASSO, DEPTH }

/** At most [n] points along [pts], evenly by count (the split engine takes up to 32). */
private fun thin(pts: List<Offset>, n: Int): List<Offset> = if (pts.size <= n) pts else List(n) { pts[(it.toLong() * (pts.size - 1) / (n - 1)).toInt()] }

/**
 * Split parts (the desktop's mesh split, polygon split and depth split): pick a part, then split it into its separate
 * islands, cut it with a lasso drawn with the Pencil, or slice it into front and back around another part (a scarf
 * round a neck). Each split is one undoable step; the rig is built again as the project would open. Done writes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun SplitScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	var message by remember { mutableStateOf<String?>(null) }
	var busy by remember { mutableStateOf<String?>(null) }
	val safe = remember { kotlinx.coroutines.CoroutineExceptionHandler { _, e -> message = e.message ?: e::class.simpleName; busy = null } }
	var parts by remember { mutableStateOf(RigParts.list(editing)) }
	var part by remember { mutableStateOf<RigParts.Part?>(null) }
	var middle by remember { mutableStateOf<RigParts.Part?>(null) }
	var tool by remember { mutableStateOf(SplitTool.ISLANDS) }
	val toolNow by rememberUpdatedState(tool)
	val partNow by rememberUpdatedState(part)
	var bounds by remember { mutableStateOf<FloatArray?>(null) }
	var middleBounds by remember { mutableStateOf<FloatArray?>(null) }
	var islands by remember { mutableStateOf<List<Pair<Float, Float>>?>(null) }
	var names by remember { mutableStateOf<List<String>>(emptyList()) }
	var lasso by remember { mutableStateOf<List<Offset>>(emptyList()) }   // canvas units
	var insideName by remember { mutableStateOf("") }
	var outsideName by remember { mutableStateOf("") }
	var view by remember { mutableStateOf(CanvasView()) }
	var box by remember { mutableStateOf(0f to 0f) }
	var tick by remember { mutableStateOf(0L) }
	var image by remember { mutableStateOf<Pair<ImageBitmap, FloatArray>?>(null) }
	var overview by remember { mutableStateOf<CanvasPicture?>(null) }
	var saving by remember { mutableStateOf<Pair<String, Double>?>(null) }
	val scope = rememberCoroutineScope()
	val cw = editing.puppet.canvasWidth; val ch = editing.puppet.canvasHeight
	fun layout(w: Float, h: Float, v: CanvasView): FloatArray = v.layout(w, h, cw, ch)

	fun choose(p: RigParts.Part?) {
		part = p; middle = null; middleBounds = null; islands = null; names = emptyList(); lasso = emptyList()
		insideName = p?.let { "${it.name} piece" } ?: ""; outsideName = p?.name ?: ""
		scope.launch(worker + safe) {
			val b = p?.let { RigParts.bounds(editing, it) }
			val i = p?.let { RigSplits.islands(editing, it.layerId) }
			scope.launch(Dispatchers.Main) { bounds = b; islands = i; names = if (p != null && i != null && i.size >= 2) RigSplits.names(p.name, i) else emptyList() }
		}
	}
	fun chooseMiddle(p: RigParts.Part?) {
		middle = p
		scope.launch(worker + safe) { val b = p?.let { RigParts.bounds(editing, it) }; scope.launch(Dispatchers.Main) { middleBounds = b } }
	}
	fun run(label: String, block: () -> Unit) {
		if (busy != null) return
		busy = label; message = null
		scope.launch(worker + safe) {
			block()
			val list = RigParts.list(editing)
			scope.launch(Dispatchers.Main) { parts = list; busy = null; tick++; choose(null) }
		}
	}
	fun undoRedo(redo: Boolean) = scope.launch(worker + safe) { if (redo) editing.redo() else editing.undo(); val list = RigParts.list(editing); scope.launch(Dispatchers.Main) { parts = list; tick++; choose(null) } }

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
				val r = visibleRegion(w, h, l, cw, ch, 2_200_000f) ?: return@withContext null
				editing.frame(emptyMap(), r.x0, r.y0, r.x1 - r.x0, r.y1 - r.y0, r.width, r.height).toImageBitmap() to r.rect
			}
			// The whole canvas under it, so a pan or zoom out shows her at once (the same picture when she all shows).
			if (!viewOnly) overview = if (visibleRegion(w, h, l, cw, ch, 1f)?.whole(cw, ch) == true) image else withContext(worker) {
				val r = wholeCanvas(w, h, cw, ch)
				editing.frame(emptyMap(), 0f, 0f, cw, ch, r.width, r.height).toImageBitmap() to r.rect
			}
		}
	}

	val undoRedo = UndoRedo(canUndo = editing.canUndo && busy == null, canRedo = editing.canRedo && busy == null,
		undo = { undoRedo(false) }, redo = { undoRedo(true) })
	Column(Modifier.undoTaps(undoRedo).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
			Text("Split parts of ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (saving == null && busy == null) onCancel() })
		}
		Text(when {
			part == null -> "Tap the part to split."
			tool == SplitTool.LASSO -> "Draw round the piece to cut off, with the Pencil or a finger. Two fingers move and zoom."
			tool == SplitTool.DEPTH -> "Tap the part that goes between the front and the back slice."
			else -> "Split ${part!!.name} into its separate islands."
		}, color = Muted, fontSize = 13.sp)
		message?.let { Text(it, color = Danger, fontSize = 13.sp) }
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(820.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				if (box != (w to h)) box = w to h
				Canvas(Modifier.fillMaxSize().canvasTouch(listOf(editing, w, h)) {
					object : CanvasTouch {
						private var at0: Offset? = null
						private var drawing = false
						private fun toCanvas(o: Offset): Offset { val l = layout(w, h, view); return Offset((o.x - l[1]) / l[0], (o.y - l[2]) / l[0]) }
						override fun start(at: Offset, pressure: Float) {
							at0 = at
							drawing = toolNow == SplitTool.LASSO && partNow != null
							if (drawing) lasso = listOf(toCanvas(at))
						}
						override fun move(at: Offset, pressure: Float) { if (drawing) lasso = lasso + toCanvas(at) }
						override fun end() {
							val a = at0 ?: return; at0 = null
							// A drag draws the lasso; a tap (it barely moved) picks the part under it, whatever the tool.
							if (drawing) {
								drawing = false
								val l = layout(w, h, view)
								if (lasso.any { kotlin.math.hypot(l[1] + it.x * l[0] - a.x, l[2] + it.y * l[0] - a.y) > 12f }) return
								lasso = emptyList()
							}
							if (busy != null) return
							val p = toCanvas(a)
							scope.launch(worker + safe) {
								val hit = RigParts.at(editing, p.x, p.y)
								scope.launch(Dispatchers.Main) {
									if (toolNow == SplitTool.DEPTH && partNow != null && hit != null && hit.layerId != partNow?.layerId) chooseMiddle(hit) else choose(hit)
								}
							}
						}
						override fun cancel() { at0 = null; drawing = false }
						override fun undo() { undoRedo(false) }
						override fun redo() { undoRedo(true) }
						override fun view() = view
						override fun setView(view: CanvasView) { viewSetter(view) }
						override fun layout(view: CanvasView) = view.layout(w, h, cw, ch)
						private val viewSetter: (CanvasView) -> Unit = { v -> view = v }
					}
				}) {
					val l = layout(w, h, view)
					drawCanvasPicture(overview, l)
					drawCanvasPicture(image, l)
					fun rect(b: FloatArray, c: Color) = drawRect(c, Offset(l[1] + b[0] * l[0], l[2] + b[1] * l[0]), androidx.compose.ui.geometry.Size((b[2] - b[0]) * l[0], (b[3] - b[1]) * l[0]), style = Stroke(2.5f))
					bounds?.let { rect(it, Pick) }
					middleBounds?.let { rect(it, Second) }
					if (tool == SplitTool.ISLANDS) islands?.takeIf { it.size >= 2 }?.forEachIndexed { i, (x, y) ->
						drawCircle(Pick, 7f, Offset(l[1] + x * l[0], l[2] + y * l[0]))
					}
					if (lasso.size > 1) {
						val pts = lasso.map { Offset(l[1] + it.x * l[0], l[2] + it.y * l[0]) }
						for (i in 1 until pts.size) drawLine(Second, pts[i - 1], pts[i], 2.5f)
						drawLine(Second.copy(alpha = 0.5f), pts.last(), pts.first(), 1.5f)
					}
				}
			}
			Column(Modifier.width(380.dp).height(820.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = undoRedo.canUndo) { undoRedo.undo() }
					Chip("Redo", false, enabled = undoRedo.canRedo) { undoRedo.redo() }
					Chip("Fit view", false) { view = CanvasView() }
				}
				val s = saving
				if (s != null) { Text(s.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = s.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				else Chip("Done - keep ${editing.edits} change${if (editing.edits == 1) "" else "s"}", true, enabled = editing.edits > 0 && busy == null) {
					saving = "Writing the runtime files" to 0.2
					scope.launch(worker) {
						try { val o = editing.finish(outcome) { st, f -> scope.launch(Dispatchers.Main) { saving = st to f } }; scope.launch(Dispatchers.Main) { saving = null; onDone(o) } }
						catch (e: Throwable) { scope.launch(Dispatchers.Main) { saving = null; message = e.message ?: e::class.simpleName } }
					}
				}
				busy?.let { Text(it, color = Muted, fontSize = 13.sp); LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent) }
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					Chip("Islands", tool == SplitTool.ISLANDS) { tool = SplitTool.ISLANDS; lasso = emptyList() }
					Chip("Lasso", tool == SplitTool.LASSO) { tool = SplitTool.LASSO }
					Chip("Front & back", tool == SplitTool.DEPTH) { tool = SplitTool.DEPTH; lasso = emptyList() }
				}
				val p = part
				if (p == null) Text("Tap a part on the picture, or pick one below.", color = Muted, fontSize = 13.sp)
				else {
					Text(p.name, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					when (tool) {
						SplitTool.ISLANDS -> {
							val i = islands
							if (i == null) Text("Looking for islands…", color = Muted, fontSize = 12.sp)
							else if (i.size < 2) Text("${p.name} is one piece - use the lasso to cut it.", color = Muted, fontSize = 12.sp)
							else {
								Text("${i.size} islands. Name each part:", color = Muted, fontSize = 12.sp)
								names.forEachIndexed { k, n -> NameBox(n) { v -> names = names.toMutableList().also { it[k] = v } } }
								Chip("Split into ${i.size} parts", true, enabled = busy == null && names.all { it.isNotBlank() } && names.distinct().size == names.size) {
									val id = p.layerId; val ns = names.map { it.trim() }
									run("Splitting ${p.name}…") { RigSplits.splitIslands(editing, id, ns) }
								}
							}
						}
						SplitTool.LASSO -> {
							Text("The piece inside the lasso", color = Muted, fontSize = 12.sp); NameBox(insideName) { insideName = it }
							Text("The rest", color = Muted, fontSize = 12.sp); NameBox(outsideName) { outsideName = it }
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								Chip("Cut", true, enabled = busy == null && lasso.size >= 3 && insideName.isNotBlank() && outsideName.isNotBlank() && insideName.trim() != outsideName.trim()) {
									val id = p.layerId; val poly = thin(lasso, 32).map { it.x to it.y }; val a = insideName.trim(); val b = outsideName.trim()
									run("Cutting ${p.name}…") { RigSplits.splitPolygon(editing, id, poly, a, b) }
								}
								Chip("Clear the lasso", false, enabled = lasso.isNotEmpty()) { lasso = emptyList() }
							}
						}
						SplitTool.DEPTH -> {
							val m = middle
							Text(if (m == null) "Now tap the part that goes between (green)." else "${p.name} will be split into a front and a back slice, with ${m.name} between them.", color = Muted, fontSize = 12.sp)
							Chip("Split front and back", true, enabled = busy == null && m != null) {
								val src = p.meshes.first(); val mid = m!!.meshes.first()
								run("Slicing ${p.name}…") { RigSplits.splitDepth(editing, src, mid) }
							}
						}
					}
				}
				Text("Parts (front first)", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
				for (q in parts) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
					.background(if (q.layerId == part?.layerId) Color(0xFF3A3358) else if (q.layerId == middle?.layerId) Color(0xFF2E4A3A) else Color(0xFF24222F))
					.clickable { if (tool == SplitTool.DEPTH && part != null && q.layerId != part?.layerId) chooseMiddle(q) else choose(q) }
					.padding(horizontal = 12.dp, vertical = 8.dp)) { Text(q.name, color = Text1, fontSize = 14.sp) }
			}
		}
	}
}

@Composable
private fun NameBox(text: String, onChange: (String) -> Unit) {
	androidx.compose.foundation.text.BasicTextField(text, onChange, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Text1, fontSize = 15.sp),
		cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B283C)).padding(10.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF5B4B8A) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
		Text(label, color = if (enabled) Text1 else Muted, fontSize = 13.sp)
	}
}
