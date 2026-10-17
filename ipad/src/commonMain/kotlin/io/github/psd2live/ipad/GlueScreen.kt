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
private val SideA = Color(0xFF6FA8FF)
private val SideB = Color(0xFFFFA24D)
private val WeldDot = Color(0xFF8FE0A8)

private enum class GlueTool(val label: String) { PICK_A("Pick part A"), PICK_B("Pick part B"), BRUSH("Glue brush"), ERASE("Unglue brush"), PULL("Pull brush") }

/**
 * Glue (the desktop's Glue tool): weld two parts' seams so they move as one. Pick part A and part B on her, then weld
 * the whole seam in one tap or brush along where they meet; the unglue brush takes welds away and the pull brush sets
 * which side follows which. Move her with the test pose to see the seam hold.
 */
@Composable
fun GlueScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	val cw = outcome.source.widthPx.toFloat(); val ch = outcome.source.heightPx.toFloat()
	var tick by remember { mutableStateOf(0) }
	var tool by remember { mutableStateOf(GlueTool.PICK_A) }
	var meshA by remember { mutableStateOf<String?>(null) }
	var meshB by remember { mutableStateOf<String?>(null) }
	var radius by remember { mutableStateOf(24f) }
	var distance by remember { mutableStateOf(40f) }
	var pullMode by remember { mutableStateOf("balance") }
	var testPose by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var wiresA by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
	var wiresB by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
	var welds by remember { mutableStateOf<List<RigGlue.Weld>>(emptyList()) }
	var stroke by remember { mutableStateOf(listOf<Offset>()) }
	var view by remember { mutableStateOf(CanvasView()) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	val names = remember(tick) { RigGlue.meshes(editing).toMap() }
	val glued = remember(tick) { RigGlue.pairs(editing) }
	val toolNow by rememberUpdatedState(tool)
	fun setGlueView(v: CanvasView) { view = v }

	LaunchedEffect(tick, meshA, meshB, testPose) {
		val pose = testPose; val a = meshA; val b = meshB
		image = withContext(worker) { editing.frame(pose, 0f, 0f, cw, ch, 1400, (1400 * ch / cw).roundToInt().coerceAtLeast(1)).toImageBitmap() }
		wiresA = if (a == null) emptyList() else withContext(worker) { io.github.psd2live.core.PoseTweaks.wireframes(editing.puppet, pose, setOf(a)).map { it.second } }
		wiresB = if (b == null) emptyList() else withContext(worker) { io.github.psd2live.core.PoseTweaks.wireframes(editing.puppet, pose, setOf(b)).map { it.second } }
		welds = if (a == null || b == null) emptyList() else withContext(worker) { RigGlue.welds(editing, a, b, pose) }
	}

	fun step(label: String, block: () -> Unit) {
		if (busy != null) return
		busy = label to 0.3; message = null
		scope.launch { try { withContext(worker) { block() }; tick++ } catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { busy = null } }
	}
	fun stroked(points: List<Pair<Float, Float>>) {
		val a = meshA ?: return; val b = meshB ?: return
		when (toolNow) {
			GlueTool.BRUSH -> step("Gluing") { RigGlue.brush(editing, a, b, points, radius, distance) }
			GlueTool.ERASE -> step("Ungluing") { RigGlue.unglue(editing, a, b, points, radius) }
			GlueTool.PULL -> step("Setting the pull") { RigGlue.pull(editing, a, b, points, radius, pullMode) }
			else -> {}
		}
	}

	val undoRedo = UndoRedo(canUndo = editing.canUndo && busy == null, canRedo = editing.canRedo && busy == null,
		undo = { editing.undo(); tick++ }, redo = { editing.redo(); tick++ })
	Column(Modifier.undoTaps(undoRedo).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Glue for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		HowTo("How to glue two parts", listOf(
			"Tap Pick part A, then tap a part on her (it outlines in blue). Do the same for part B (orange) - e.g. the neck and the head.",
			"Tap Weld the whole seam, or use the Glue brush along where they meet. Green dots are the welds.",
			"Use Test pose to move her and check the seam holds. Unglue brush takes welds away; Pull brush sets which side follows.",
			"Done keeps the changes. Gluing never changes how she looks at rest."))
		Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(800.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				fun glueLayout(v: CanvasView) = v.layout(w, h, cw, ch)
				Canvas(Modifier.fillMaxSize().canvasTouch(listOf(w, h)) {
					object : CanvasTouch {
						private fun toCanvas(o: Offset): Offset { val l = glueLayout(view); return Offset((o.x - l[1]) / l[0], (o.y - l[2]) / l[0]) }
						override fun start(at: Offset, pressure: Float) {
							if (busy != null) return
							val p = toCanvas(at)
							when (toolNow) {
								GlueTool.PICK_A, GlueTool.PICK_B -> {
									val picking = toolNow; val pose = testPose
									scope.launch(worker) {
										val id = RigGlue.meshAt(editing, pose, p.x, p.y) ?: return@launch
										scope.launch(Dispatchers.Main) {
											if (picking == GlueTool.PICK_A) { meshA = id; if (meshB == id) meshB = null; tool = GlueTool.PICK_B }
											else { if (id != meshA) { meshB = id; tool = GlueTool.BRUSH } }
										}
									}
								}
								else -> stroke = listOf(p)
							}
						}
						override fun move(at: Offset, pressure: Float) { if (stroke.isNotEmpty()) stroke = stroke + toCanvas(at) }
						override fun end() { val s = stroke; stroke = emptyList(); if (s.isNotEmpty()) stroked(s.map { it.x to it.y }) }
						override fun cancel() { stroke = emptyList() }
						override fun undo() {}
						override fun redo() {}
						override fun view() = view
						override fun setView(view: CanvasView) { setGlueView(view) }
						override fun layout(view: CanvasView) = glueLayout(view)
					}
				}) {
					val l = glueLayout(view)
					image?.let { drawImage(it, dstOffset = IntOffset(l[1].roundToInt(), l[2].roundToInt()), dstSize = IntSize((cw * l[0]).roundToInt(), (ch * l[0]).roundToInt()),
						alpha = if (meshA != null || meshB != null) 0.55f else 1f) }
					fun wires(list: List<FloatArray>, color: Color) {
						val pts = ArrayList<Offset>()
						for (wire in list) { var i = 0; while (i + 1 < wire.size) { pts += Offset(l[1] + wire[i] * l[0], l[2] + wire[i + 1] * l[0]); i += 2 } }
						drawPoints(pts, androidx.compose.ui.graphics.PointMode.Lines, color.copy(alpha = 0.8f), strokeWidth = 1.2f)
					}
					wires(wiresA, SideA); wires(wiresB, SideB)
					for (wd in welds) {
						val a = Offset(l[1] + wd.ax * l[0], l[2] + wd.ay * l[0]); val b = Offset(l[1] + wd.bx * l[0], l[2] + wd.by * l[0])
						drawLine(WeldDot, a, b, 2f)
						drawCircle(WeldDot, 4f, Offset(a.x + (b.x - a.x) * (1f - wd.weightA), a.y + (b.y - a.y) * (1f - wd.weightA)))
					}
					for (p in stroke) drawCircle(Color.White.copy(alpha = 0.3f), radius * l[0], Offset(l[1] + p.x * l[0], l[2] + p.y * l[0]), style = Stroke(1.5f))
				}
				if (image == null) Text("Drawing…", color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
			}
			Column(Modifier.width(380.dp).height(800.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text("Part A: ${meshA?.let { names[it] ?: it } ?: "not picked"}", color = if (meshA != null) SideA else Muted, fontSize = 14.sp)
				Text("Part B: ${meshB?.let { names[it] ?: it } ?: "not picked"}", color = if (meshB != null) SideB else Muted, fontSize = 14.sp)
				@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
				androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					for (t in GlueTool.entries) Chip(t.label, tool == t, enabled = t == GlueTool.PICK_A || t == GlueTool.PICK_B || (meshA != null && meshB != null)) { tool = t }
					Chip("Swap A / B", false, enabled = meshA != null && meshB != null) { val a = meshA; meshA = meshB; meshB = a }
				}
				val ready = meshA != null && meshB != null && busy == null
				Chip("Weld the whole seam", true, enabled = ready) { val a = meshA!!; val b = meshB!!; step("Welding the seam") { RigGlue.weldAll(editing, a, b, distance) } }
				Text("${welds.size} weld${if (welds.size == 1) "" else "s"} between them", color = Muted, fontSize = 12.sp)
				Chip("Remove all welds between them", false, enabled = ready && welds.isNotEmpty()) { val a = meshA!!; val b = meshB!!; step("Ungluing") { RigGlue.removeAll(editing, a, b) } }
				Text("Brush size  ${(radius * 2).roundToInt()} px", color = Muted, fontSize = 12.sp)
				Slider(radius, { radius = it }, valueRange = 4f..120f, colors = sliderColors(), modifier = Modifier.height(32.dp))
				Text("Weld reach  ${distance.roundToInt()} px (how far apart two edges can be and still weld)", color = Muted, fontSize = 12.sp)
				Slider(distance, { distance = it }, valueRange = 4f..160f, colors = sliderColors(), modifier = Modifier.height(32.dp))
				if (tool == GlueTool.PULL) {
					Text("Pull", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip("Meet in the middle", pullMode == "balance") { pullMode = "balance" }
						Chip("A follows B", pullMode == "a") { pullMode = "a" }
						Chip("B follows A", pullMode == "b") { pullMode = "b" }
					}
				}
				Text("Test pose", color = Muted, fontSize = 12.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					Chip("Rest", testPose.isEmpty()) { testPose = emptyMap() }
					Chip("Turn head", testPose.containsKey("ParamAngleX")) { testPose = mapOf("ParamAngleX" to 30f, "ParamAngleZ" to 15f) }
					Chip("Lean body", testPose.containsKey("ParamBodyAngleX")) { testPose = mapOf("ParamBodyAngleX" to 10f, "ParamBodyAngleZ" to 10f) }
				}
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
						try { val o = withContext(worker) { editing.finish(outcome) { s, f -> scope.launch(Dispatchers.Main) { busy = s to f } } }; busy = null; onDone(o) }
						catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
					}
				}
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
				if (glued.isNotEmpty()) {
					Text("Glued already (tap to open)", color = Muted, fontSize = 12.sp)
					for ((a, bb) in glued) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF24222F))
						.clickable { meshA = a; meshB = bb; tool = GlueTool.BRUSH }.padding(horizontal = 10.dp, vertical = 7.dp)) {
						Text("${names[a] ?: a}  +  ${names[bb] ?: bb}", color = Text1, fontSize = 13.sp)
					}
				}
			}
		}
	}
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
