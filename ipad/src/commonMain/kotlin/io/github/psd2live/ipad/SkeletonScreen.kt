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
private val Bone = Color(0xFFFFC46B)
private val BoneOn = Color(0xFF8FE0A8)

/**
 * The skeleton (the desktop's Skeleton mode): propose bones from the art (arms, or the full body), then drag a joint to
 * move it, pose each bone with its slider, bind a part to a bone, set a bone's limits or remove a limb, and keep named
 * poses. A bone change rebuilds the rig (a few seconds); undo/redo; Done writes the rig.
 */
@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SkeletonScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	var message by remember { mutableStateOf<String?>(null) }
	var busy by remember { mutableStateOf<String?>(null) }
	val safe = remember { kotlinx.coroutines.CoroutineExceptionHandler { _, e -> message = e.message ?: e::class.simpleName; busy = null } }
	var spec by remember { mutableStateOf(RigSkeleton.spec(editing)) }
	val specNow by rememberUpdatedState(spec)
	var selected by remember { mutableStateOf<String?>(null) }
	val selectedNow by rememberUpdatedState(selected)
	var pose by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
	var binding by remember { mutableStateOf(false) }
	val bindingNow by rememberUpdatedState(binding)
	var drag by remember { mutableStateOf<Triple<String, String, Offset>?>(null) } // bone, end, canvas point
	var poseName by remember { mutableStateOf("") }
	// Removing a bone asks twice: the panel moves as bones are picked, so one stray tap mustn't do it.
	var confirmRemove by remember { mutableStateOf<String?>(null) }
	var view by remember { mutableStateOf(CanvasView()) }
	var box by remember { mutableStateOf(0f to 0f) }
	var tick by remember { mutableStateOf(0L) }
	var image by remember { mutableStateOf<Pair<ImageBitmap, FloatArray>?>(null) }
	var saving by remember { mutableStateOf<Pair<String, Double>?>(null) }
	val scope = rememberCoroutineScope()
	val cw = editing.puppet.canvasWidth; val ch = editing.puppet.canvasHeight
	fun layout(w: Float, h: Float, v: CanvasView): FloatArray = v.layout(w, h, cw, ch)
	fun ranges() = editing.puppet.parameters.associate { it.id.raw to (it.min..it.max) }

	fun run(label: String, block: () -> Unit) {
		if (busy != null) return
		confirmRemove = null
		busy = label; message = null
		scope.launch(worker + safe) {
			block()
			val s = RigSkeleton.spec(editing)
			scope.launch(Dispatchers.Main) { spec = s; busy = null; tick++; if (s?.bone(selected ?: "") == null) selected = null }
		}
	}

	LaunchedEffect(editing) {
		var lastView: CanvasView? = null
		snapshotFlow { listOf(tick, view, box, pose) }.collectLatest {
			val (w, h) = box
			if (w < 2f || h < 2f) return@collectLatest
			val viewOnly = lastView != null && lastView != view
			lastView = view
			if (viewOnly) delay(140)
			val l = layout(w, h, view); val p = pose
			image = withContext(worker) {
				val x0 = maxOf(0f, -l[1] / l[0]); val y0 = maxOf(0f, -l[2] / l[0])
				val x1 = minOf(cw, (w - l[1]) / l[0]); val y1 = minOf(ch, (h - l[2]) / l[0])
				if (x1 - x0 < 1f || y1 - y0 < 1f) return@withContext null
				var ow = ((x1 - x0) * l[0]).roundToInt().coerceAtLeast(1); var oh = ((y1 - y0) * l[0]).roundToInt().coerceAtLeast(1)
				val k = minOf(1f, kotlin.math.sqrt(2_200_000f / (ow.toFloat() * oh))); ow = (ow * k).roundToInt().coerceAtLeast(1); oh = (oh * k).roundToInt().coerceAtLeast(1)
				editing.frame(p, x0, y0, x1 - x0, y1 - y0, ow, oh).toImageBitmap() to floatArrayOf(x0, y0, x1, y1)
			}
		}
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
			Text("Skeleton for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (saving == null && busy == null) onCancel() })
		}
		Text(when {
			spec == null -> "Bones let arms, legs, a tail or wings swing. Propose a skeleton from the art to start."
			binding -> "Tap a part to bind it to the selected bone."
			else -> "Drag a joint to move it (at rest). Pose a bone with its slider. Two fingers move and zoom."
		}, color = if (binding) BoneOn else Muted, fontSize = 13.sp)
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
						override fun start(at: Offset, pressure: Float) {
							at0 = at
							val s = specNow ?: return
							if (bindingNow || busy != null) return
							// The nearest joint within reach is picked up (only at rest: joints are placed on the drawing).
							val l = layout(w, h, view); var best: Triple<String, String, Offset>? = null; var bestD = 28f
							for (b in s.bones) for ((end, x, y) in listOf(Triple("head", b.headX, b.headY), Triple("tail", b.tailX, b.tailY))) {
								val d = kotlin.math.hypot(l[1] + x * l[0] - at.x, l[2] + y * l[0] - at.y)
								if (d < bestD) { bestD = d; best = Triple(b.id, end, Offset(x, y)) }
							}
							drag = best; best?.let { selected = it.first }
						}
						override fun move(at: Offset, pressure: Float) { drag?.let { drag = Triple(it.first, it.second, toCanvas(at)) } }
						override fun end() {
							val a = at0 ?: return; at0 = null
							val d = drag; drag = null
							if (d != null) {
								val start = specNow?.bone(d.first)?.let { if (d.second == "head") Offset(it.headX, it.headY) else Offset(it.tailX, it.tailY) }
								if (start != null && kotlin.math.hypot(d.third.x - start.x, d.third.y - start.y) > 1f)
									run("Moving the joint…") { RigSkeleton.move(editing, d.first, d.second, d.third.x, d.third.y) }
								return
							}
							if (bindingNow) {
								val bone = selectedNow ?: return; val p = toCanvas(a)
								scope.launch(worker + safe) {
									val mesh = editing.puppet.let { io.github.psd2live.core.PoseTweaks.meshAt(it, emptyMap(), p.x, p.y) }
									scope.launch(Dispatchers.Main) {
										if (mesh == null) message = "No part there." else run("Binding…") { RigSkeleton.bind(editing, mesh, bone) }
									}
								}
							}
						}
						override fun cancel() { at0 = null; drag = null }
						override fun undo() { if (busy == null) run("Undoing…") { editing.undo() } }
						override fun redo() { if (busy == null) run("Redoing…") { editing.redo() } }
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
					val s = spec
					if (s != null && pose.values.all { it == 0f }) for (b in s.bones) {
						if (b.role.anchor) continue
						var hx = b.headX; var hy = b.headY; var tx = b.tailX; var ty = b.tailY
						drag?.let { d -> if (d.first == b.id) { if (d.second == "head") { hx = d.third.x; hy = d.third.y } else { tx = d.third.x; ty = d.third.y } } }
						val c = if (b.id == selected) BoneOn else Bone
						val hp = Offset(l[1] + hx * l[0], l[2] + hy * l[0]); val tp = Offset(l[1] + tx * l[0], l[2] + ty * l[0])
						drawLine(Color.Black.copy(alpha = 0.6f), hp, tp, 6f); drawLine(c, hp, tp, 3.5f)
						drawCircle(c, 8f, hp); drawCircle(c.copy(alpha = 0.7f), 6f, tp)
					}
				}
			}
			Column(Modifier.width(380.dp).height(820.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = editing.canUndo && busy == null) { run("Undoing…") { editing.undo() } }
					Chip("Redo", false, enabled = editing.canRedo && busy == null) { run("Redoing…") { editing.redo() } }
					Chip("Fit view", false) { view = CanvasView() }
				}
				val sv = saving
				if (sv != null) { Text(sv.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = sv.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				else Chip("Done - keep the changes", true, enabled = editing.edits > 0 || editing.model.config.rigEdits.skeleton != outcome.model.config.rigEdits.skeleton) {
					saving = "Writing the runtime files" to 0.2
					scope.launch(worker) {
						try { val o = editing.finish(outcome) { st, f -> scope.launch(Dispatchers.Main) { saving = st to f } }; scope.launch(Dispatchers.Main) { saving = null; onDone(o) } }
						catch (e: Throwable) { scope.launch(Dispatchers.Main) { saving = null; message = e.message ?: e::class.simpleName } }
					}
				}
				busy?.let { Text(it, color = Muted, fontSize = 13.sp); LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent) }
				val s = spec
				if (s == null) {
					Chip("Propose arms", true, enabled = busy == null) { run("Building the arm bones…") { RigSkeleton.auto(editing, "arms") } }
					Chip("Propose the full body (arms, legs, tail, wings)", false, enabled = busy == null) { run("Building the skeleton…") { RigSkeleton.auto(editing, "full") } }
				} else {
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip(if (s.enabled) "Skeleton on" else "Skeleton off", s.enabled, enabled = busy == null) { val on = !s.enabled; run("Switching…") { RigSkeleton.enable(editing, on) } }
						Chip("Rest pose", false, enabled = pose.isNotEmpty()) { pose = emptyMap() }
					}
					// The list stays put above the bone's controls, so picking a bone never moves the row under the next tap.
					Text("Bones", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
					for (b in s.bones.filter { !it.role.anchor }) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
						.background(if (b.id == selected) Color(0xFF3A3358) else Color(0xFF24222F)).clickable { selected = b.id }.padding(horizontal = 12.dp, vertical = 8.dp),
						horizontalArrangement = Arrangement.SpaceBetween) {
						Text(b.name, color = Text1, fontSize = 14.sp)
						Text("${(pose[b.parameterId] ?: 0f).roundToInt()}°", color = Muted, fontSize = 12.sp)
					}
					val sel = selected?.let { s.bone(it) }
					if (sel != null && !sel.role.anchor) {
						Text(sel.name, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
						val r = ranges()[sel.parameterId]
						if (r != null) {
							val v = pose[sel.parameterId] ?: 0f
							Text("Pose  ${v.roundToInt()}°", color = Muted, fontSize = 12.sp)
							Slider(v.coerceIn(r), { pose = pose + (sel.parameterId to it) }, valueRange = r, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent), modifier = Modifier.height(28.dp))
						}
						Text("Turns from ${sel.minAngle.roundToInt()}° to ${sel.maxAngle.roundToInt()}°", color = Muted, fontSize = 12.sp)
						androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
							Chip("Narrower", false, enabled = busy == null) { run("Changing the limits…") { RigSkeleton.bone(editing, sel.id, minAngle = sel.minAngle * 0.75f, maxAngle = sel.maxAngle * 0.75f) } }
							Chip("Wider", false, enabled = busy == null) { run("Changing the limits…") { RigSkeleton.bone(editing, sel.id, minAngle = (sel.minAngle * 1.25f).coerceAtLeast(-180f), maxAngle = (sel.maxAngle * 1.25f).coerceAtMost(180f)) } }
							Chip(if (binding) "Done binding" else "Bind a part to it", binding) { binding = !binding }
							if (sel.role.limb) {
								if (confirmRemove == sel.id) Chip("Tap again to remove ${sel.name}", true, enabled = busy == null) { val id = sel.id; confirmRemove = null; run("Removing the bone…") { RigSkeleton.remove(editing, id) } }
								else Chip("Remove this bone", false, enabled = busy == null) { confirmRemove = sel.id }
							}
						}
						if (sel.drawableIds.isNotEmpty()) Text("Moves: ${sel.drawableIds.joinToString { id -> editing.puppet.drawables.firstOrNull { it.id.raw == id }?.name ?: id }}", color = Muted, fontSize = 12.sp)
					} else Text("Tap a joint or a bone below to pose and set it.", color = Muted, fontSize = 13.sp)
					Text("Saved poses", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
						androidx.compose.foundation.text.BasicTextField(poseName, { poseName = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Text1, fontSize = 15.sp),
							cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent), decorationBox = { inner -> Box { if (poseName.isEmpty()) Text("Name (e.g. Wave)", color = Muted, fontSize = 15.sp); inner() } },
							modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B283C)).padding(10.dp))
						Chip("Save", true, enabled = poseName.isNotBlank() && pose.isNotEmpty() && busy == null) { val n = poseName; val p = pose; run("Saving the pose…") { RigSkeleton.savePose(editing, n, p) }; poseName = "" }
					}
					for ((n, values) in s.savedPoses) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
						Text(n, color = Text1, fontSize = 14.sp, modifier = Modifier.weight(1f))
						Chip("Show", false) { pose = values }
						Chip("Delete", false, enabled = busy == null) { run("Deleting…") { RigSkeleton.deletePose(editing, n) } }
					}
				}
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
