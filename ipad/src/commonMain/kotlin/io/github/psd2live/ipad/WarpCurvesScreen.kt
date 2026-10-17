package io.github.psd2live.ipad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
private val Cage = Color(0xFFFFC46B)
private val AnchorC = Color(0xFF6FD3FF)
private val HandleC = Color(0xFFFF8FD1)

/**
 * Warp curves (the desktop's Bezier warp editing): a warp's cage as a few curved anchors with handles. Drag an anchor
 * to move a whole region smoothly, drag a handle to curve the edge. Set the key (pose) the shape belongs to, the number
 * of curved sections, and the cage's own grid.
 */
@Composable
fun WarpCurvesScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	val cw = outcome.source.widthPx.toFloat(); val ch = outcome.source.heightPx.toFloat()
	var tick by remember { mutableStateOf(0) }
	val warps = remember(tick) { RigWarps.list(editing) }
	var warpId by remember { mutableStateOf<String?>(null) }
	var key by remember(warpId) { mutableStateOf<Map<String, Float>>(emptyMap()) }
	var shown by remember { mutableStateOf<RigWarps.View?>(null) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var held by remember { mutableStateOf<RigWarps.Control?>(null) }
	var dragFrom by remember { mutableStateOf<Offset?>(null) }
	var dragTo by remember { mutableStateOf<Offset?>(null) }
	var smooth by remember { mutableStateOf(true) }
	var view by remember { mutableStateOf(CanvasView()) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	fun setWarpView(v: CanvasView) { view = v }

	// Every slider of the warp gets a key (its rest key to start): an edit names the key of each.
	LaunchedEffect(warpId) {
		val id = warpId ?: return@LaunchedEffect
		key = withContext(worker) { runCatching { RigWarps.key(editing, id, editing.puppet.parameters.associate { it.id.raw to it.default }) }.getOrDefault(emptyMap()) }
	}
	LaunchedEffect(tick, warpId, key) {
		val id = warpId; val k = key
		image = withContext(worker) { editing.frame(k, 0f, 0f, cw, ch, 1400, (1400 * ch / cw).roundToInt().coerceAtLeast(1)).toImageBitmap() }
		shown = if (id == null) null else withContext(worker) { runCatching { RigWarps.view(editing, id, k) }.getOrNull() }
	}
	fun step(label: String, block: () -> Unit) {
		if (busy != null) return
		busy = label to 0.3; message = null
		scope.launch { try { withContext(worker) { block() }; tick++ } catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { busy = null } }
	}

	val undoRedo = UndoRedo(canUndo = editing.canUndo && busy == null, canRedo = editing.canRedo && busy == null,
		undo = { editing.undo(); tick++ }, redo = { editing.redo(); tick++ })
	Column(Modifier.undoTaps(undoRedo).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Warp curves for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		HowTo("How warp curves work", listOf(
			"Tap a warp's cage on her (or pick one from the list). Blue squares are anchors, pink dots are handles.",
			"Choose the key it should change: Rest, or a key of its slider (e.g. Angle X 30). The shape belongs to that key.",
			"Drag an anchor to move that region smoothly; drag a handle to curve the edge. More curve sections = finer control.",
			"Done keeps the changes. Straighten the curves undoes the bends at this key."))
		Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(800.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				fun warpLayout(v: CanvasView) = v.layout(w, h, cw, ch)
				Canvas(Modifier.fillMaxSize().canvasTouch(listOf(w, h)) {
					object : CanvasTouch {
						private fun toCanvas(o: Offset): Offset { val l = warpLayout(view); return Offset((o.x - l[1]) / l[0], (o.y - l[2]) / l[0]) }
						override fun start(at: Offset, pressure: Float) {
							if (busy != null) return
							val l = warpLayout(view)
							val near = shown?.controls?.minByOrNull { kotlin.math.hypot(l[1] + it.x * l[0] - at.x, l[2] + it.y * l[0] - at.y) }
								?.takeIf { kotlin.math.hypot(l[1] + it.x * l[0] - at.x, l[2] + it.y * l[0] - at.y) < 36f }
							if (near != null) { held = near; dragFrom = at; dragTo = at; return }
							val p = toCanvas(at); val k = key
							scope.launch(worker) { RigWarps.at(editing, k, p.x, p.y)?.let { id -> scope.launch(Dispatchers.Main) { warpId = id } } }
						}
						override fun move(at: Offset, pressure: Float) { if (held != null) dragTo = at }
						override fun end() {
							val c = held; val a = dragFrom; val b = dragTo; val id = warpId; val k = key
							held = null; dragFrom = null; dragTo = null
							if (c == null || a == null || b == null || id == null) return
							val l = warpLayout(view)
							val dx = (b.x - a.x) / l[0]; val dy = (b.y - a.y) / l[0]
							if (kotlin.math.hypot(dx, dy) < 0.5f) return
							val sm = smooth
							step("Bending the warp") { RigWarps.drag(editing, id, k, c, dx, dy, sm) }
						}
						override fun cancel() { held = null; dragFrom = null; dragTo = null }
						override fun undo() {}
						override fun redo() {}
						override fun view() = view
						override fun setView(view: CanvasView) { setWarpView(view) }
						override fun layout(view: CanvasView) = warpLayout(view)
					}
				}) {
					val l = warpLayout(view)
					image?.let { drawImage(it, dstOffset = IntOffset(l[1].roundToInt(), l[2].roundToInt()), dstSize = IntSize((cw * l[0]).roundToInt(), (ch * l[0]).roundToInt()),
						alpha = if (shown != null) 0.6f else 1f) }
					val s = shown ?: return@Canvas
					fun at(x: Float, y: Float) = Offset(l[1] + x * l[0], l[2] + y * l[0])
					val cols = s.latticeColumns + 1
					for (r in 0..s.latticeRows) for (c in 0..s.latticeColumns) {
						val i = (r * cols + c) * 2
						val p = at(s.lattice[i], s.lattice[i + 1])
						if (c < s.latticeColumns) drawLine(Cage.copy(alpha = 0.55f), p, at(s.lattice[i + 2], s.lattice[i + 3]), 1f)
						if (r < s.latticeRows) drawLine(Cage.copy(alpha = 0.55f), p, at(s.lattice[i + cols * 2], s.lattice[i + cols * 2 + 1]), 1f)
					}
					// The drag in hand moves its control on screen until it lands.
					val off = if (held != null && dragFrom != null && dragTo != null) Offset(dragTo!!.x - dragFrom!!.x, dragTo!!.y - dragFrom!!.y) else Offset.Zero
					val anchors = s.controls.filter { it.direction == null }.associateBy { it.row to it.column }
					for (hd in s.controls.filter { it.direction != null }) {
						val an = anchors[hd.row to hd.column] ?: continue
						val hp = at(hd.x, hd.y) + if (hd === held) off else Offset.Zero
						val ap = at(an.x, an.y) + if (an === held) off else Offset.Zero
						drawLine(HandleC.copy(alpha = 0.8f), ap, hp, 1.5f)
						drawCircle(HandleC, if (hd === held) 9f else 6f, hp)
					}
					for (an in anchors.values) {
						val p = at(an.x, an.y) + if (an === held) off else Offset.Zero
						val r = if (an === held) 10f else 7f
						drawRect(AnchorC, Offset(p.x - r, p.y - r), Size(r * 2, r * 2))
						drawRect(Color.Black.copy(alpha = 0.5f), Offset(p.x - r, p.y - r), Size(r * 2, r * 2), style = Stroke(1f))
					}
				}
				if (image == null) Text("Drawing…", color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
			}
			Column(Modifier.width(380.dp).height(800.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				val s = shown; val id = warpId
				if (s != null && id != null) {
					Text(warps.firstOrNull { it.id == id }?.name ?: id, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					Text("Key (the pose this shape belongs to)", color = Muted, fontSize = 12.sp)
					if (s.axes.isEmpty()) Text("This warp has no slider of its own: the shape is its rest shape.", color = Muted, fontSize = 12.sp)
					for ((param, keys) in s.axes) {
						Text(param.removePrefix("Param"), color = Muted, fontSize = 12.sp)
						@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
						androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
							for (k in keys) Chip(if (k == k.roundToInt().toFloat()) k.roundToInt().toString() else k.toString(),
								key[param] == k) { key = key + (param to k) }
						}
					}
					Text("Curve sections  ${s.bezierRows} down × ${s.bezierColumns} across", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip("− down", false, enabled = s.bezierRows > 1 && busy == null) { val k = key; step("Changing the curves") { RigWarps.divisions(editing, id, k, s.bezierRows - 1, s.bezierColumns) } }
						Chip("+ down", false, enabled = s.bezierRows < 16 && busy == null) { val k = key; step("Changing the curves") { RigWarps.divisions(editing, id, k, s.bezierRows + 1, s.bezierColumns) } }
						Chip("− across", false, enabled = s.bezierColumns > 1 && busy == null) { val k = key; step("Changing the curves") { RigWarps.divisions(editing, id, k, s.bezierRows, s.bezierColumns - 1) } }
						Chip("+ across", false, enabled = s.bezierColumns < 16 && busy == null) { val k = key; step("Changing the curves") { RigWarps.divisions(editing, id, k, s.bezierRows, s.bezierColumns + 1) } }
					}
					Chip(if (smooth) "Handles stay smooth: on" else "Handles stay smooth: off", smooth) { smooth = !smooth }
					Text("Smooth handles move their partner too, so the curve stays round through the anchor.", color = Muted, fontSize = 12.sp)
					Chip("Straighten the curves (at this key)", false, enabled = busy == null) { val k = key; step("Straightening") { RigWarps.reset(editing, id, k) } }
					Text("Cage grid  ${s.latticeRows} × ${s.latticeColumns}", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip("Coarser", false, enabled = s.latticeRows > 1 && busy == null) { step("Changing the grid") { RigWarps.grid(editing, id, s.latticeRows - 1, s.latticeColumns - 1) } }
						Chip("Finer", false, enabled = s.latticeRows < 32 && busy == null) { step("Changing the grid") { RigWarps.grid(editing, id, s.latticeRows + 1, s.latticeColumns + 1) } }
					}
					Text("The grid is how many points the warp really has; the shape is kept when it changes.", color = Muted, fontSize = 12.sp)
				} else Text("Tap a warp's cage on her, or pick one below.", color = Muted, fontSize = 13.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = undoRedo.canUndo) { undoRedo.undo() }
					Chip("Redo", false, enabled = undoRedo.canRedo) { undoRedo.redo() }
					Chip("Fit view", false) { view = CanvasView() }
				}
				val b = busy
				if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				else Chip("Done - keep the changes", true, enabled = editing.canUndo) {
					busy = "Writing the runtime files" to 0.1
					scope.launch {
						try { val o = withContext(worker) { editing.finish(outcome) { st, f -> scope.launch(Dispatchers.Main) { busy = st to f } } }; busy = null; onDone(o) }
						catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
					}
				}
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
				Text("Warps", color = Muted, fontSize = 12.sp)
				for (wp in warps) {
					val on = wp.id == warpId
					Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF4A3F78) else Color(0xFF24222F))
						.clickable { warpId = wp.id }.padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
						Text(wp.name, color = if (on) Accent else Text1, fontSize = 13.sp)
						Text("${wp.rows}×${wp.columns}", color = Muted, fontSize = 12.sp)
					}
				}
			}
		}
	}
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
