package io.github.psd2live.ipad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.hypot
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/** Where the canvas sits on screen: [zoom] times the fitted scale, moved by [panX]/[panY] screen pixels. */
private data class View(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)

/**
 * Tweak the rig by hand at a pose: pose it with the sliders, then push what's under the pencil (or a finger) into
 * place. Two fingers pan and pinch-zoom. Each stroke is one undoable keyform edit; Done writes the rig's files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun TweakScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val tweaking = remember(outcome) { RigTweaking(outcome) }
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	var pose by remember(outcome) { mutableStateOf(tweaking.parameters.associate { it.id to it.default }) }
	var radius by remember { mutableStateOf(40f) }
	var strength by remember { mutableStateOf(0.6f) }
	var smooth by remember { mutableStateOf(false) }
	var view by remember { mutableStateOf(View()) }
	var tick by remember { mutableStateOf(0L) }
	var image by remember { mutableStateOf<Pair<ImageBitmap, FloatArray>?>(null) }
	var cursor by remember { mutableStateOf<Offset?>(null) }
	var saving by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	var box by remember { mutableStateOf(0f to 0f) }
	val scope = rememberCoroutineScope()
	val settings by rememberUpdatedState(Triple(radius, strength, smooth))
	val currentPose by rememberUpdatedState(pose)
	fun bump() { scope.launch(Dispatchers.Main) { tick++ } }

	// Canvas units to screen pixels, for the current box and view.
	fun layout(w: Float, h: Float, v: View): FloatArray {
		val fit = minOf(w / tweaking.canvasWidth, h / tweaking.canvasHeight)
		val scale = fit * v.zoom
		val left = (w - tweaking.canvasWidth * scale) / 2f + v.panX; val top = (h - tweaking.canvasHeight * scale) / 2f + v.panY
		return floatArrayOf(scale, left, top)
	}

	LaunchedEffect(tweaking) {
		// Draws only what is on screen, so zooming in stays sharp: a quick draft (~0.35 MP) while the pencil or a slider
		// moves, the full-resolution frame (~2.2 MP) once things rest for a moment. A newer change drops both.
		snapshotFlow { listOf(tick, pose, view, box) }.collectLatest {
			val (w, h) = box
			if (w < 2f || h < 2f) return@collectLatest
			val l = layout(w, h, view)
			val shownPose = pose
			suspend fun draw(budget: Float) = withContext(Dispatchers.Default) {
				val scale = l[0]; val left = l[1]; val top = l[2]
				val x0 = maxOf(0f, -left / scale); val y0 = maxOf(0f, -top / scale)
				val x1 = minOf(tweaking.canvasWidth, (w - left) / scale); val y1 = minOf(tweaking.canvasHeight, (h - top) / scale)
				if (x1 - x0 < 1f || y1 - y0 < 1f) return@withContext null
				var outW = ((x1 - x0) * scale).roundToInt().coerceAtLeast(1); var outH = ((y1 - y0) * scale).roundToInt().coerceAtLeast(1)
				val shrink = minOf(1f, kotlin.math.sqrt(budget / (outW.toFloat() * outH)))
				outW = (outW * shrink).roundToInt().coerceAtLeast(1); outH = (outH * shrink).roundToInt().coerceAtLeast(1)
				val raster = tweaking.frame(shownPose, x0, y0, x1 - x0, y1 - y0, outW, outH)
				raster.toImageBitmap() to floatArrayOf(left + x0 * scale, top + y0 * scale, (x1 - x0) * scale, (y1 - y0) * scale)
			}
			image = draw(350_000f)
			delay(180)
			image = draw(2_200_000f)
		}
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
			Text("Tweak ${outcome.name} by hand", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping tweaks", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (saving == null) onCancel() })
		}
		Text("Pose her with the sliders, then push what's under the pencil into place. The tweak belongs to that pose " +
			"and blends back toward rest. Two fingers move and zoom.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(820.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				if (box != (w to h)) box = w to h
				Canvas(Modifier.fillMaxSize().pointerInput(tweaking) {
					awaitEachGesture {
						val down = awaitFirstDown(requireUnconsumed = false)
						if (saving != null) return@awaitEachGesture
						val l = layout(w, h, view)
						fun toCanvas(o: Offset) = Offset((o.x - l[1]) / l[0], (o.y - l[2]) / l[0])
						val (r, s, sm) = settings
						val start = toCanvas(down.position)
						var stroking = false
						val p0 = currentPose
						scope.launch(worker) { stroking = tweaking.begin(p0, start.x, start.y, r, 0.5f, s, sm) }
						cursor = down.position
						var pinchFrom: Pair<Offset, Float>? = null
						var viewAtPinch = view
						var cancelled = false
						while (true) {
							val event = awaitPointerEvent()
							val pressed = event.changes.filter { it.pressed }
							if (pressed.isEmpty()) break
							if (pressed.size >= 2 && down.type != PointerType.Stylus) {
								// Two fingers: the stroke is dropped and the canvas pans and zooms.
								if (!cancelled) { cancelled = true; cursor = null; scope.launch(worker) { tweaking.cancel(); bump() } }
								val a = pressed[0].position; val b = pressed[1].position
								val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f); val dist = hypot(a.x - b.x, a.y - b.y).coerceAtLeast(1f)
								val from = pinchFrom ?: (mid to dist).also { pinchFrom = it; viewAtPinch = view }
								val zoom = (viewAtPinch.zoom * dist / from.second).coerceIn(0.5f, 16f)
								// Keep the canvas point under the pinch centre under it.
								val l0 = layout(w, h, viewAtPinch)
								val cx = (from.first.x - l0[1]) / l0[0]; val cy = (from.first.y - l0[2]) / l0[0]
								val l1 = layout(w, h, viewAtPinch.copy(zoom = zoom, panX = 0f, panY = 0f))
								view = View(zoom, mid.x - (l1[1] + cx * l1[0]), mid.y - (l1[2] + cy * l1[0]))
								event.changes.forEach { it.consume() }
								continue
							}
							if (cancelled) { event.changes.forEach { it.consume() }; continue }
							val change = event.changes.firstOrNull { it.id == down.id } ?: continue
							val p = toCanvas(change.position)
							cursor = change.position
							scope.launch(worker) { if (stroking) { tweaking.step(p.x, p.y); bump() } }
							change.consume()
						}
						cursor = null
						if (!cancelled) scope.launch(worker) { if (stroking) { tweaking.end(); bump() } }
					}
				}) {
					image?.let { (bitmap, at) ->
						drawImage(bitmap, dstOffset = IntOffset(at[0].roundToInt(), at[1].roundToInt()),
							dstSize = androidx.compose.ui.unit.IntSize(at[2].roundToInt(), at[3].roundToInt()))
					}
					cursor?.let { c ->
						val l = layout(w, h, view)
						drawCircle(Color.White.copy(alpha = 0.8f), radius * l[0], c, style = Stroke(1.5f))
						drawCircle(Color.Black.copy(alpha = 0.5f), radius * l[0] + 1.5f, c, style = Stroke(1f))
					}
				}
				if (image == null) Text("Drawing…", color = Muted, modifier = Modifier.align(Alignment.Center))
			}
			Column(Modifier.width(330.dp).height(820.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				@Suppress("UNUSED_VARIABLE") val changes = tick
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Push", !smooth) { smooth = false }
					Chip("Smooth", smooth) { smooth = true }
					Chip("Fit view", false) { view = View() }
				}
				Text("Brush size  ${(radius * 2).roundToInt()} px", color = Muted, fontSize = 12.sp)
				Slider(radius, { radius = it }, valueRange = 4f..300f, colors = sliderColors(), modifier = Modifier.height(30.dp))
				Text("Strength  ${(strength * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
				Slider(strength, { strength = it }, valueRange = 0.05f..1f, colors = sliderColors(), modifier = Modifier.height(30.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = tweaking.canUndo) { scope.launch(worker) { tweaking.undo(); bump() } }
					Chip("Redo", false, enabled = tweaking.canRedo) { scope.launch(worker) { tweaking.redo(); bump() } }
					Text("${tweaking.edits} tweaks", color = Muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
				}
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text("Pose", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					Text("Rest pose", color = Accent, fontSize = 14.sp, modifier = Modifier.clickable { pose = tweaking.parameters.associate { it.id to it.default } })
				}
				Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
					for (p in tweaking.parameters) {
						val value = pose[p.id] ?: p.default
						Text("${p.name}  ${(value * 100).roundToInt() / 100.0}", color = Muted, fontSize = 12.sp)
						Slider(value, { pose = pose + (p.id to it) }, valueRange = p.min..p.max, colors = sliderColors(), modifier = Modifier.height(28.dp))
					}
				}
				val progress = saving
				if (progress != null) {
					Text(progress.first, color = Muted, fontSize = 13.sp)
					LinearProgressIndicator(progress = progress.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent)
				} else Chip("Done - keep the tweaks", true, enabled = tweaking.edits > 0) {
					saving = "Starting" to 0.0; failure = null
					scope.launch {
						try {
							val updated = withContext(worker) {
								tweaking.finish { stage, fraction -> scope.launch(Dispatchers.Main) { if (saving != null) saving = stage to fraction } }
							}
							saving = null; onDone(updated)
						} catch (error: Throwable) { saving = null; failure = error.message ?: error::class.simpleName }
					}
				}
				failure?.let { Text("Couldn't save: $it", color = Danger, fontSize = 13.sp) }
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
