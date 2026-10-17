package io.github.psd2live.ipad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.CanvasWeightAuthoring
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.core.SkeletonWeightBrushMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.runtime.model.VertexGroupKind
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/**
 * Weights by hand (the desktop's weight brush): with the Pencil or a finger, paint how strongly each vertex of a mesh
 * follows a skeleton bone, or its simulation weights (pin, stiffness, goal, mass, damping, wind). Blue is none, red is
 * full. Skeleton strokes edit a draft that Apply builds into the rig; simulation strokes land at once.
 */
@Composable
fun WeightsScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	val hasSkeleton = remember(outcome) { RigSkeleton.spec(editing)?.let { RigSkeletonWeights.meshes(editing, it).isNotEmpty() } == true }
	var skeletonMode by remember { mutableStateOf(hasSkeleton) }
	// Skeleton: the draft spec the strokes edit, and its earlier states for undo.
	var draft by remember { mutableStateOf(RigSkeleton.spec(editing)) }
	var draftUndo by remember { mutableStateOf(listOf<SkeletonSpec>()) }
	var draftRedo by remember { mutableStateOf(listOf<SkeletonSpec>()) }
	var mesh by remember { mutableStateOf<String?>(null) }
	var pickingPart by remember { mutableStateOf(false) }
	val pickingPartNow by rememberUpdatedState(pickingPart)
	var copyFrom by remember { mutableStateOf<String?>(null) }
	var copyMirror by remember { mutableStateOf(false) }
	var bone by remember { mutableStateOf<String?>(null) }
	var kind by remember { mutableStateOf(VertexGroupKind.PIN) }
	var radius by remember { mutableStateOf(40f) }
	var strength by remember { mutableStateOf(0.5f) }
	var hardness by remember { mutableStateOf(0.5f) }
	var boneMode by remember { mutableStateOf(SkeletonWeightBrushMode.ADD) }
	var simMode by remember { mutableStateOf(CanvasWeightAuthoring.Mode.ADD) }
	var tick by remember { mutableStateOf(0) }
	var shown by remember { mutableStateOf<WeightMesh?>(null) }
	var background by remember { mutableStateOf<Pair<ImageBitmap, FloatArray>?>(null) }
	var view by remember { mutableStateOf(CanvasView()) }
	var box by remember { mutableStateOf(0f to 0f) }
	var stroke by remember { mutableStateOf(listOf<Offset>()) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	val rest = remember(outcome) { outcome.preview.parameters.associate { it.id to it.default } }
	fun setWeightView(v: CanvasView) { view = v }

	val meshes = remember(skeletonMode, draft, tick) {
		if (skeletonMode) draft?.let { RigSkeletonWeights.meshes(editing, it) }.orEmpty() else RigSimulationWeights.meshes(editing)
	}
	if (mesh == null || meshes.none { it.drawableId == mesh }) mesh = meshes.firstOrNull()?.drawableId
	val bones = remember(draft, mesh) { val d = draft; val m = mesh; if (d == null || m == null) emptyList() else RigSkeletonWeights.bones(d, m) }
	if (skeletonMode && (bone == null || bones.none { it.id == bone })) bone = bones.firstOrNull()?.id

	// The mesh in view and the art under it: drawn for the selected mesh's box (padded), re-drawn as things change.
	LaunchedEffect(skeletonMode, mesh, bone, kind, draft, tick) {
		val m = mesh ?: return@LaunchedEffect
		shown = withContext(worker) {
			if (skeletonMode) { val d = draft; val b = bone; if (d == null || b == null) null else RigSkeletonWeights.weights(editing, d, m, b) }
			else RigSimulationWeights.weights(editing, m, kind)
		}
		val s = shown ?: return@LaunchedEffect
		val region = regionOf(s.positions, outcome.source.widthPx.toFloat(), outcome.source.heightPx.toFloat())
		val scale = 1400f / maxOf(region[2], region[3])
		background = withContext(worker) {
			editing.frame(rest, region[0], region[1], region[2], region[3], (region[2] * scale).roundToInt().coerceAtLeast(1), (region[3] * scale).roundToInt().coerceAtLeast(1))
				.toImageBitmap() to region
		}
	}

	fun step(label: String, block: () -> Unit) {
		if (busy != null) return
		busy = label to 0.3; message = null
		scope.launch {
			try { withContext(worker) { block() }; tick++ } catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { busy = null }
		}
	}

	fun stroked(points: List<Pair<Float, Float>>) {
		val m = mesh ?: return
		if (skeletonMode) {
			val d = draft ?: return; val b = bone ?: return
			step("Painting") {
				val next = RigSkeletonWeights.paint(editing, d, m, b, points, radius, strength, boneMode)
				draftUndo = (draftUndo + d).takeLast(40); draftRedo = emptyList(); draft = next
			}
		} else step("Painting") { RigSimulationWeights.paint(editing, m, kind, points, radius, hardness, simMode, strength) }
	}

	// Skeleton mode undoes strokes on the unapplied draft; simulation mode undoes the rig's own steps.
	val undoRedo = if (skeletonMode) UndoRedo(canUndo = draftUndo.isNotEmpty() && busy == null, canRedo = draftRedo.isNotEmpty() && busy == null,
		undo = { draftRedo = draftRedo + draft!!; draft = draftUndo.last(); draftUndo = draftUndo.dropLast(1) },
		redo = { draftUndo = draftUndo + draft!!; draft = draftRedo.last(); draftRedo = draftRedo.dropLast(1) })
	else UndoRedo(canUndo = editing.canUndo && busy == null, canRedo = editing.canRedo && busy == null,
		undo = { editing.undo(); tick++ }, redo = { editing.redo(); tick++ })
	val undoSteps by rememberUpdatedState(undoRedo)
	Column(Modifier.undoTaps(undoRedo).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Weights of ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		Text("Pencil or a finger paints; two fingers move and zoom. Blue is no weight, red is full.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Chip("Skeleton (which bone moves it)", skeletonMode, enabled = hasSkeleton) { skeletonMode = true; view = CanvasView() }
			Chip("Simulation (pin, stiffness...)", !skeletonMode) { skeletonMode = false; view = CanvasView() }
		}
		if (skeletonMode && !hasSkeleton) Text("This rig has no skeleton yet: make one on the Skeleton screen first.", color = Muted, fontSize = 13.sp)
		Text("Mesh", color = Muted, fontSize = 12.sp)
		Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			for (m in meshes) Chip(m.name, m.drawableId == mesh) { mesh = m.drawableId; view = CanvasView() }
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			Chip(if (pickingPart) "Tapping her picks the part: on" else "Pick the part on her", pickingPart) { pickingPart = !pickingPart }
			if (pickingPart) Text("Tap a part to paint it next. Turn this off to paint again.", color = Muted, fontSize = 12.sp)
		}
		if (skeletonMode) {
			Text("Bone", color = Muted, fontSize = 12.sp)
			Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				for (b in bones) Chip(b.name, b.id == bone) { bone = b.id }
			}
		} else {
			Text("Weight", color = Muted, fontSize = 12.sp)
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				for (k in RigSimulationWeights.kinds) Chip(kindLabel(k), k == kind) { kind = k }
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(820.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				if (box != (w to h)) box = w to h
				val bg = background
				fun layout(v: CanvasView): FloatArray? {
					val region = bg?.second ?: return null
					val l = v.layout(w, h, region[2], region[3])
					return floatArrayOf(l[0], l[1] - region[0] * l[0], l[2] - region[1] * l[0])
				}
				Canvas(Modifier.fillMaxSize().canvasTouch(listOf(w, h, bg, skeletonMode, mesh)) {
					object : CanvasTouch {
						private fun toCanvas(o: Offset): Offset? { val l = layout(view) ?: return null; return Offset((o.x - l[1]) / l[0], (o.y - l[2]) / l[0]) }
						override fun start(at: Offset, pressure: Float) { if (busy == null) toCanvas(at)?.let { stroke = listOf(it) } }
						override fun move(at: Offset, pressure: Float) { if (stroke.isNotEmpty() && !pickingPartNow) toCanvas(at)?.let { stroke = stroke + it } }
						override fun end() {
							val s = stroke; stroke = emptyList()
							if (s.isEmpty()) return
							if (!pickingPartNow) { stroked(s.map { it.x to it.y }); return }
							// Picking: the part under the finger becomes the one painted, when its weights can be painted here.
							val p = s.first()
							scope.launch {
								val id = withContext(worker) { io.github.psd2live.core.PoseTweaks.meshAt(editing.puppet, emptyMap(), p.x, p.y) }
								val hit = meshes.firstOrNull { it.drawableId == id }
								if (hit == null) message = if (id == null) "No part there." else "${editing.puppet.drawables.firstOrNull { it.id.raw == id }?.name ?: id} has no ${if (skeletonMode) "skeleton" else "simulation"} weights to paint."
								else { message = null; mesh = hit.drawableId; view = CanvasView() }
							}
						}
						override fun cancel() { stroke = emptyList() }
						override fun undo() { undoSteps.undo() }
						override fun redo() { undoSteps.redo() }
						override fun view() = view
						override fun setView(view: CanvasView) { setWeightView(view) }
						override fun layout(view: CanvasView) = layout(view) ?: floatArrayOf(1f, 0f, 0f)
					}
				}) {
					val l = layout(view) ?: return@Canvas
					val region = bg!!.second
					drawImage(bg.first, dstOffset = IntOffset((l[1] + region[0] * l[0]).roundToInt(), (l[2] + region[1] * l[0]).roundToInt()),
						dstSize = IntSize((region[2] * l[0]).roundToInt(), (region[3] * l[0]).roundToInt()))
					val s = shown
					if (s != null) {
						val px = FloatArray(s.positions.size) { if (it % 2 == 0) s.positions[it] * l[0] + l[1] else s.positions[it] * l[0] + l[2] }
						for (t in s.triangles.indices step 3) {
							val a = s.triangles[t]; val b = s.triangles[t + 1]; val c = s.triangles[t + 2]
							val weight = (s.weights.getOrElse(a) { 0f } + s.weights.getOrElse(b) { 0f } + s.weights.getOrElse(c) { 0f }) / 3f
							val path = Path().apply { moveTo(px[a * 2], px[a * 2 + 1]); lineTo(px[b * 2], px[b * 2 + 1]); lineTo(px[c * 2], px[c * 2 + 1]); close() }
							drawPath(path, heat(weight).copy(alpha = 0.5f))
						}
					}
					// The bone being painted, head to tail.
					val d = draft; val b = bone
					if (skeletonMode && d != null && b != null) d.bone(b)?.let { bn ->
						drawLine(Color.White, Offset(bn.headX * l[0] + l[1], bn.headY * l[0] + l[2]), Offset(bn.tailX * l[0] + l[1], bn.tailY * l[0] + l[2]), strokeWidth = 4f)
						drawCircle(Color.White, 7f, Offset(bn.headX * l[0] + l[1], bn.headY * l[0] + l[2]))
					}
					// The stroke in hand, at the brush's size.
					for (p in stroke) drawCircle(Color.White.copy(alpha = 0.25f), radius * l[0], Offset(p.x * l[0] + l[1], p.y * l[0] + l[2]), style = Stroke(1.5f))
				}
				if (shown == null) Text(if (meshes.isEmpty()) "No meshes to paint." else "Drawing…", color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
			}
			Column(Modifier.width(360.dp).height(820.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text("Brush", color = Muted, fontSize = 12.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					if (skeletonMode) for ((m, label) in listOf(SkeletonWeightBrushMode.ADD to "Add", SkeletonWeightBrushMode.SUBTRACT to "Subtract",
						SkeletonWeightBrushMode.REPLACE to "Set", SkeletonWeightBrushMode.SMOOTH to "Smooth")) Chip(label, boneMode == m) { boneMode = m }
					else for ((m, label) in listOf(CanvasWeightAuthoring.Mode.ADD to "Add", CanvasWeightAuthoring.Mode.SUBTRACT to "Subtract",
						CanvasWeightAuthoring.Mode.SET to "Set", CanvasWeightAuthoring.Mode.SMOOTH to "Smooth")) Chip(label, simMode == m) { simMode = m }
				}
				Text("Size  ${(radius * 2).roundToInt()} px", color = Muted, fontSize = 12.sp)
				Slider(radius, { radius = it }, valueRange = 4f..300f, colors = sliderColors(), modifier = Modifier.height(32.dp))
				Text("Strength  ${(strength * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
				Slider(strength, { strength = it }, valueRange = 0.02f..1f, colors = sliderColors(), modifier = Modifier.height(32.dp))
				if (!skeletonMode) {
					Text("Hardness  ${(hardness * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
					Slider(hardness, { hardness = it }, valueRange = 0f..1f, colors = sliderColors(), modifier = Modifier.height(32.dp))
				}
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = undoRedo.canUndo) { undoRedo.undo() }
					Chip("Redo", false, enabled = undoRedo.canRedo) { undoRedo.redo() }
					Chip("Fit view", false) { view = CanvasView() }
				}
				val m = mesh
				if (m != null) {
					if (skeletonMode) {
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							Chip("Clean up", false, enabled = busy == null && draft != null) {
								val d = draft!!; step("Cleaning up") { val next = RigSkeletonWeights.cleanup(editing, d, m); draftUndo = draftUndo + d; draftRedo = emptyList(); draft = next }
							}
							Chip("Back to automatic", false, enabled = busy == null && draft?.manualWeights?.containsKey(m) == true) {
								val d = draft!!; step("Resetting") { val next = RigSkeletonWeights.clear(editing, d, m); draftUndo = draftUndo + d; draftRedo = emptyList(); draft = next }
							}
						}
						Text("Clean up keeps at most two bones a vertex and drops stray specks. Back to automatic forgets this mesh's painted weights.", color = Muted, fontSize = 12.sp)
						Text("Copy weights from another part onto this one", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
						Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							for (o in meshes) if (o.drawableId != m) Chip(o.name, o.drawableId == copyFrom) { copyFrom = if (copyFrom == o.drawableId) null else o.drawableId }
						}
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							Chip("Mirrored (left onto right)", copyMirror) { copyMirror = !copyMirror }
							val from = copyFrom
							Chip("Copy onto ${meshes.firstOrNull { it.drawableId == m }?.name ?: "this part"}", true, enabled = busy == null && draft != null && from != null && from != m) {
								val d = draft!!; val mirror = copyMirror
								step("Copying weights") { val next = RigSkeletonWeights.transfer(editing, d, from!!, m, mirror); draftUndo = draftUndo + d; draftRedo = emptyList(); draft = next }
							}
						}
					} else {
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							Chip("Fill", false, enabled = busy == null) { step("Filling") { RigSimulationWeights.fill(editing, m, kind, 1f) } }
							Chip("Clear", false, enabled = busy == null) { step("Clearing") { RigSimulationWeights.fill(editing, m, kind, 0f) } }
							Chip("Invert", false, enabled = busy == null && RigSimulationWeights.has(editing, m, kind)) { step("Inverting") { RigSimulationWeights.invert(editing, m, kind) } }
						}
						Chip("Remove this weight", false, enabled = busy == null && RigSimulationWeights.has(editing, m, kind)) { step("Removing") { RigSimulationWeights.delete(editing, m, kind) } }
						Text(kindHelp(kind), color = Muted, fontSize = 12.sp)
					}
				}
				Box(Modifier.height(8.dp))
				val b = busy
				if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				else {
					val changed = if (skeletonMode) draftUndo.isNotEmpty() else editing.canUndo
					Chip(if (skeletonMode) "Apply - build the rig with these weights" else "Done - keep the changes", true, enabled = changed) {
						busy = (if (skeletonMode) "Building the skeleton" else "Writing the runtime files") to 0.1
						scope.launch {
							try {
								val o = withContext(worker) {
									if (skeletonMode) RigSkeletonWeights.apply(editing, draft!!)
									editing.finish(outcome) { s, f -> scope.launch(Dispatchers.Main) { busy = s to f } }
								}
								busy = null; onDone(o)
							} catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
						}
					}
					if (skeletonMode) Text("Skeleton weights build into the rig when you apply (a skeleton build takes a minute or two).", color = Muted, fontSize = 12.sp)
				}
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
			}
		}
	}
}

/** The canvas box around [positions] (x, y pairs), padded, inside the canvas: left, top, width, height. */
private fun regionOf(positions: FloatArray, canvasW: Float, canvasH: Float): FloatArray {
	var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
	for (i in positions.indices step 2) { x0 = minOf(x0, positions[i]); x1 = maxOf(x1, positions[i]); y0 = minOf(y0, positions[i + 1]); y1 = maxOf(y1, positions[i + 1]) }
	if (x0 > x1) return floatArrayOf(0f, 0f, canvasW, canvasH)
	val pad = maxOf(x1 - x0, y1 - y0) * 0.25f + 20f
	val l = (x0 - pad).coerceAtLeast(0f); val t = (y0 - pad).coerceAtLeast(0f)
	return floatArrayOf(l, t, ((x1 + pad).coerceAtMost(canvasW) - l).coerceAtLeast(1f), ((y1 + pad).coerceAtMost(canvasH) - t).coerceAtLeast(1f))
}

/** Blue (none) through green to red (full). */
private fun heat(w: Float): Color {
	val v = w.coerceIn(0f, 1f)
	return if (v < 0.5f) lerp(Color(0xFF2F5BFF), Color(0xFF3DDC84), v * 2f) else lerp(Color(0xFF3DDC84), Color(0xFFFF3B30), (v - 0.5f) * 2f)
}

private fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

private fun kindLabel(k: VertexGroupKind) = when (k) {
	VertexGroupKind.PIN -> "Pin"; VertexGroupKind.STIFFNESS -> "Stiffness"; VertexGroupKind.GOAL -> "Goal"
	VertexGroupKind.MASS -> "Mass"; VertexGroupKind.DAMPING -> "Damping"; VertexGroupKind.WIND -> "Wind"
}

private fun kindHelp(k: VertexGroupKind) = when (k) {
	VertexGroupKind.PIN -> "Pin: held to the rig - red stays put (a hem's top, a sleeve's cuff), blue swings free."
	VertexGroupKind.STIFFNESS -> "Stiffness: how much it resists stretching and bending."
	VertexGroupKind.GOAL -> "Goal: how strongly it is pulled back to its drawn shape."
	VertexGroupKind.MASS -> "Mass: heavier parts (hems, tips) lag and overshoot more."
	VertexGroupKind.DAMPING -> "Damping: how quickly its motion settles."
	VertexGroupKind.WIND -> "Wind: how much wind and force fields push it."
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
