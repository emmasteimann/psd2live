package io.github.psd2live.ipad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionInterpolation
import io.github.psd2live.core.MotionKey
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.format.compile.FrameSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/** Keys closer than this (seconds) are the same key: half a frame at 30 fps. */
private const val SAME_KEY = 1f / 60f

/**
 * Your own motions, pose to pose: scrub to a time, pose her with the sliders and a key lands there for each slider you
 * move; play to watch it with physics. Each key's ease (smooth, linear, step) and the motion's length, loop and fades.
 * A looping motion joins the idles; a one-shot gets its own group (a VTube Studio hotkey). Apply writes the motion3
 * files - no re-rig.
 */
@Composable
fun MotionEditorScreen(outcome: RigOutcome, onApplied: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val puppet = outcome.model.rig.puppet
	val params = remember(outcome) { puppet.parameters }
	val defaults = remember(outcome) { params.associate { it.id.raw to it.default } }
	var clips by remember(outcome) { mutableStateOf(outcome.config.rigEdits.motionClips.filter { it.builtin == null }) }
	var selected by remember(outcome) { mutableStateOf(clips.firstOrNull()?.id) }
	var playhead by remember { mutableStateOf(0f) }
	var playing by remember { mutableStateOf(false) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var applying by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	var showAll by remember { mutableStateOf(false) }
	val scope = rememberCoroutineScope()
	val clip = clips.firstOrNull { it.id == selected }

	// Undo history of the motions: a slider drag or a key drag is one step (changes within a moment of each other merge).
	var past by remember(outcome) { mutableStateOf(listOf<List<MotionClip>>()) }
	var future by remember(outcome) { mutableStateOf(listOf<List<MotionClip>>()) }
	var lastChange by remember { mutableStateOf<kotlin.time.TimeMark?>(null) }
	var copied by remember { mutableStateOf<List<Pair<String, MotionKey>>>(emptyList()) }
	var lipSyncing by remember { mutableStateOf(false) }
	fun change(next: List<MotionClip>) {
		if (next == clips) return
		val merge = lastChange?.let { it.elapsedNow() < kotlin.time.Duration.parse("600ms") } == true
		if (!merge) { past = (past + listOf(clips)).takeLast(80); future = emptyList() }
		lastChange = kotlin.time.TimeSource.Monotonic.markNow()
		clips = next
	}
	fun update(c: MotionClip) = change(clips.map { if (it.id == c.id) c else it })
	fun undoMotions() { val p = past.lastOrNull() ?: return; future = future + listOf(clips); past = past.dropLast(1); clips = p; lastChange = null
		if (clips.none { it.id == selected }) selected = clips.firstOrNull()?.id }
	fun redoMotions() { val n = future.lastOrNull() ?: return; past = past + listOf(clips); future = future.dropLast(1); clips = n; lastChange = null
		if (clips.none { it.id == selected }) selected = clips.firstOrNull()?.id }
	fun poseAt(c: MotionClip?, t: Float): Map<String, Float> =
		defaults + (c?.let { MotionClips.sampleAll(it, t.toDouble(), loop = false).mapKeys { e -> e.key.raw } } ?: emptyMap())

	// Still frames while editing; a loop with physics while playing.
	LaunchedEffect(clip, playhead, playing) {
		val c = clip
		if (!playing || c == null) {
			val pose = poseAt(c, playhead)
			image = withContext(Dispatchers.Default) { outcome.preview.render(pose, 900).toImageBitmap() }
			return@LaunchedEffect
		}
		withContext(Dispatchers.Default) {
			val ir = RigIrCompiler.compile(outcome.model)
			val session = IrFrameRenderer.open(ir, true)
			val spec = FrameSpec.canvas(ir, 720)
			var t = 0f
			try {
				while (isActive) {
					val pose = defaults + MotionClips.sampleAll(c, t.toDouble(), loop = true).mapKeys { it.key.raw }
					val bitmap = session.render(pose, 1f / 30f, spec, null).toImageBitmap()
					withContext(Dispatchers.Main) { image = bitmap; playhead = t % c.duration }
					t += 1f / 30f
					delay(25)
				}
			} finally { session.close() }
		}
	}

	/** [c] with [parameterId] keyed to [value] at the playhead (an existing key there is replaced). */
	fun keyed(c: MotionClip, parameterId: String, value: Float): MotionClip {
		val t = playhead.coerceIn(0f, c.duration)
		val curve = c.curve(parameterId)
		val keys = (curve?.keys.orEmpty().filter { abs(it.time - t) > SAME_KEY } + MotionKey(t, value)).sortedBy { it.time }
		// A new curve starts from the parameter's rest at 0, so the motion begins where the model stands.
		val withStart = if (curve == null && t > SAME_KEY) listOf(MotionKey(0f, defaults[parameterId] ?: 0f)) + keys else keys
		val next = MotionCurve(parameterId, withStart)
		return c.copy(curves = c.curves.filter { it.parameterId != parameterId } + next)
	}

	val keyTimes = clip?.curves?.flatMap { cv -> cv.keys.map { it.time } }?.sorted()?.fold(mutableListOf<Float>()) { acc, t ->
		if (acc.isEmpty() || abs(acc.last() - t) > SAME_KEY) acc += t; acc } ?: emptyList()
	val atKey = keyTimes.firstOrNull { abs(it - playhead) <= SAME_KEY }

	val undoRedo = UndoRedo(canUndo = past.isNotEmpty() && applying == null, canRedo = future.isNotEmpty() && applying == null,
		undo = { playing = false; undoMotions() }, redo = { playing = false; redoMotions() })
	Column(Modifier.undoTaps(undoRedo).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Your motions for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (applying == null) onCancel() })
		}
		Text("Scrub to a time, pose her with the sliders and a key lands there. A looping motion joins the idles; a one-shot " +
			"becomes its own hotkey in VTube Studio.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			Chip("New motion", false) {
				val c = MotionClip(MotionClips.newId(clips + outcome.config.rigEdits.motionClips), MotionClips.uniqueName(clips, "Motion"), duration = 2f, fadeIn = 0.3f, fadeOut = 0.3f)
				change(clips + c); selected = c.id; playhead = 0f; playing = false
			}
			Chip("Undo", false, enabled = undoRedo.canUndo) { undoRedo.undo() }
			Chip("Redo", false, enabled = undoRedo.canRedo) { undoRedo.redo() }
			for (c in clips) Chip(c.name, c.id == selected) { selected = c.id; playhead = 0f; playing = false }
		}
		if (clip == null) {
			Text("No motions of your own yet.", color = Muted, fontSize = 14.sp)
		} else {
			Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Box(Modifier.fillMaxWidth().height(640.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017)), contentAlignment = Alignment.Center) {
						image?.let { Image(it, contentDescription = "The motion at the playhead", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
							?: Text("Drawing…", color = Muted)
					}
					// The timeline: keys as diamonds, the playhead as a line; drag anywhere to scrub, tap a diamond to jump to it.
					// Drag a diamond sideways to move that key (every slider keyed there) in time; drag elsewhere to scrub.
					Canvas(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF24222F)).pointerInput(clip.id, clip.duration, keyTimes, clips) {
						awaitEachGesture {
							val down = awaitFirstDown(); playing = false
							fun at(x: Float): Float {
								val t = (x / size.width).coerceIn(0f, 1f) * clip.duration
								return keyTimes.firstOrNull { abs(it - t) / clip.duration * size.width < 14f } ?: ((t * 30f).roundToInt() / 30f)
							}
							val grabbed = keyTimes.firstOrNull { abs(it / clip.duration * size.width - down.position.x) < 14f }
							val before = clip
							var moving = false
							playhead = at(down.position.x); down.consume()
							while (true) {
								val e = awaitPointerEvent(); val ch = e.changes.firstOrNull { it.id == down.id } ?: break
								if (!ch.pressed) break
								if (grabbed != null && (moving || abs(ch.position.x - down.position.x) > 10f)) {
									moving = true
									val dt = ((ch.position.x - down.position.x) / size.width * clip.duration * 30f).roundToInt() / 30f
									val selection = before.curves.flatMap { cv -> cv.keys.filter { abs(it.time - grabbed) <= SAME_KEY }.map { io.github.psd2live.core.MotionKeyRef(cv.parameterId, it.time) } }.toSet()
									update(io.github.psd2live.core.MotionKeyEdits.move(before, selection, dt, 0f).first)
									playhead = (grabbed + dt).coerceIn(0f, clip.duration)
								} else playhead = at(ch.position.x)
								ch.consume()
							}
							lastChange = null
						}
					}) {
						val w = size.width; val h = size.height
						var s = 0f
						while (s <= clip.duration + 1e-3f) { val x = s / clip.duration * w; drawLine(Color(0xFF3A3650), Offset(x, 0f), Offset(x, h), 1f); s += 0.5f }
						for (t in keyTimes) {
							val x = t / clip.duration * w
							val d = Path().apply { moveTo(x, h / 2 - 9); lineTo(x + 9, h / 2); lineTo(x, h / 2 + 9); lineTo(x - 9, h / 2); close() }
							drawPath(d, if (abs(t - playhead) <= SAME_KEY) Color(0xFFFFB347) else Accent)
						}
						val px = playhead / clip.duration * w
						drawRect(Color.White, Offset(px - 1f, 0f), Size(2f, h))
					}
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
						Chip(if (playing) "Stop" else "Play", playing) { playing = !playing }
						Text("${(playhead * 100).roundToInt() / 100.0} s of ${(clip.duration * 10).roundToInt() / 10.0} s", color = Muted, fontSize = 13.sp)
						Chip("◀ key", false, enabled = keyTimes.any { it < playhead - SAME_KEY }) { playing = false; playhead = keyTimes.last { it < playhead - SAME_KEY } }
						Chip("key ▶", false, enabled = keyTimes.any { it > playhead + SAME_KEY }) { playing = false; playhead = keyTimes.first { it > playhead + SAME_KEY } }
					}
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
						Chip("Copy this key", false, enabled = atKey != null) {
							val t = atKey!!
							val sel = clip.curves.flatMap { cv -> cv.keys.filter { abs(it.time - t) <= SAME_KEY }.map { io.github.psd2live.core.MotionKeyRef(cv.parameterId, it.time) } }.toSet()
							copied = io.github.psd2live.core.MotionKeyEdits.copy(clip, sel)
						}
						Chip("Paste at ${(playhead * 100).roundToInt() / 100.0} s", false, enabled = copied.isNotEmpty()) {
							playing = false; lastChange = null
							update(io.github.psd2live.core.MotionKeyEdits.paste(clip, copied, playhead.coerceIn(0f, clip.duration)).first)
						}
						if (copied.isNotEmpty()) Text("${copied.size} slider${if (copied.size == 1) "" else "s"} copied", color = Muted, fontSize = 12.sp)
					}
					if (atKey != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
						Text("This key eases:", color = Muted, fontSize = 13.sp)
						for ((label, kind) in listOf("Smooth" to MotionInterpolation.BEZIER, "Linear" to MotionInterpolation.LINEAR, "Step" to MotionInterpolation.STEPPED)) {
							val current = clip.curves.flatMap { it.keys }.firstOrNull { abs(it.time - atKey) <= SAME_KEY }?.interpolation
							Chip(label, current == kind) {
								update(clip.copy(curves = clip.curves.map { cv -> cv.copy(keys = cv.keys.map { k -> if (abs(k.time - atKey) <= SAME_KEY) k.copy(interpolation = kind) else k }) }))
							}
						}
						Chip("Delete key", false) {
							update(clip.copy(curves = clip.curves.mapNotNull { cv ->
								val left = cv.keys.filter { abs(it.time - atKey) > SAME_KEY }; if (left.isEmpty()) null else cv.copy(keys = left) }))
						}
					}
				}
				Column(Modifier.width(360.dp).height(840.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text("Name", color = Muted, fontSize = 12.sp)
					BasicTextField(clip.name, { n -> update(clip.copy(name = n.ifBlank { clip.name })) }, singleLine = true,
						textStyle = TextStyle(color = Text1, fontSize = 15.sp), cursorBrush = SolidColor(Accent),
						modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B283C)).padding(10.dp))
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						Chip(if (clip.loop) "Loops (an idle)" else "One-shot (a hotkey)", clip.loop) { update(clip.copy(loop = !clip.loop)) }
						Chip("Duplicate", false) {
							val copy = clip.copy(id = MotionClips.newId(clips + outcome.config.rigEdits.motionClips), name = MotionClips.uniqueName(clips, clip.name + " copy"))
							lastChange = null; change(clips + copy); selected = copy.id
						}
						Chip("Delete motion", false) { lastChange = null; change(clips.filter { it.id != clip.id }); selected = clips.firstOrNull()?.id }
					}
					Text("Length  ${(clip.duration * 10).roundToInt() / 10.0} s", color = Muted, fontSize = 12.sp)
					Slider(clip.duration, { d ->
						val len = (d * 10).roundToInt() / 10f
						update(io.github.psd2live.core.MotionKeyEdits.withDuration(clip, len))
						playhead = playhead.coerceAtMost(len)
					}, valueRange = 0.5f..10f, colors = sliderColors(), modifier = Modifier.height(28.dp))
					Text("Fade in  ${(clip.fadeIn * 10).roundToInt() / 10.0} s   ·   fade out  ${(clip.fadeOut * 10).roundToInt() / 10.0} s", color = Muted, fontSize = 12.sp)
					Slider(clip.fadeIn, { update(clip.copy(fadeIn = it)) }, valueRange = 0f..2f, colors = sliderColors(), modifier = Modifier.height(26.dp))
					Slider(clip.fadeOut, { update(clip.copy(fadeOut = it)) }, valueRange = 0f..2f, colors = sliderColors(), modifier = Modifier.height(26.dp))
					// Lip sync: a voice recording becomes this motion's mouth keys (open from loudness, shape from brightness).
					val mouthOpen = params.firstOrNull { it.id == io.github.psd2live.core.StandardParameters.MOUTH_OPEN }
					Chip(if (lipSyncing) "Listening…" else "Lip sync from a recording…", false, enabled = !lipSyncing && mouthOpen != null) {
						val target = clip; val open = mouthOpen!!
						val form = params.firstOrNull { it.id == io.github.psd2live.core.StandardParameters.MOUTH_FORM }
						Exchange.pick(setOf("wav", "m4a", "mp3", "aac", "caf", "aif", "aiff"), multiple = false) { names ->
							val name = names.firstOrNull() ?: return@pick
							lipSyncing = true; failure = null
							scope.launch {
								try {
									val path = RigFiles.psdPath(name)
									val baked = withContext(Dispatchers.Default) {
										val result = io.github.psd2live.core.LipSync.analyze(readAudioMono(path), target.fps)
										io.github.psd2live.core.LipSync.bake(target, result, open, form, path)
									}
									lastChange = null; update(baked); playhead = 0f
								} catch (e: Throwable) { failure = e.message ?: e::class.simpleName } finally { lipSyncing = false }
							}
						}
					}
					if (mouthOpen == null) Text("Lip sync needs a mouth-open slider (ParamMouthOpenY).", color = Muted, fontSize = 12.sp)
					else if (clip.sound != null) Text("Sound: ${clip.sound!!.substringAfterLast('/')} - it plays with the motion in VTube Studio.", color = Muted, fontSize = 12.sp)
					Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
						Text("Pose at the playhead", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
						Text(if (showAll) "Keyed only" else "Every parameter", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { showAll = !showAll })
					}
					val pose = poseAt(clip, playhead)
					val keyedIds = clip.curves.mapTo(HashSet()) { it.parameterId }
					for (p in params.filter { showAll || it.id.raw in keyedIds || keyedIds.isEmpty() }) {
						val id = p.id.raw
						val value = pose[id] ?: p.default
						val here = clip.curve(id)?.keys?.any { abs(it.time - playhead) <= SAME_KEY } == true
						Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
							Text("${if (here) "◆ " else if (id in keyedIds) "◇ " else ""}${p.name}  ${(value * 100).roundToInt() / 100.0}",
								color = if (here) Text1 else Muted, fontSize = 12.sp)
							if (id in keyedIds) Text("Clear ✕", color = Accent, fontSize = 12.sp, modifier = Modifier.clickable {
								playing = false; lastChange = null; update(clip.copy(curves = clip.curves.filter { it.parameterId != id }))
							})
						}
						Slider(value, { v -> playing = false; update(keyed(clip, id, v)) }, valueRange = p.min..p.max, colors = sliderColors(), modifier = Modifier.height(28.dp))
					}
				}
			}
		}
		val progress = applying
		if (progress != null) {
			Text(progress.first, color = Muted, fontSize = 13.sp)
			LinearProgressIndicator(progress = progress.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent)
		} else Chip("Apply - keep these motions", true, enabled = clips != outcome.config.rigEdits.motionClips.filter { it.builtin == null }) {
			applying = "Writing the motion files" to 0.2; failure = null
			// The built-in overrides stay as they were; these replace the user's own.
			val all = outcome.config.rigEdits.motionClips.filter { it.builtin != null } + clips.filter { it.curves.isNotEmpty() }
			val config = outcome.config.copy(rigEdits = outcome.config.rigEdits.copy(motionClips = all))
			scope.launch {
				try {
					val updated = withContext(Dispatchers.Default) {
						applyRigConfig(outcome, config) { stage, fraction -> scope.launch(Dispatchers.Main) { if (applying != null) applying = stage to fraction } }
					}
					applying = null; onApplied(updated)
				} catch (error: Throwable) { applying = null; failure = error.message ?: error::class.simpleName }
			}
		}
		failure?.let { Text("Couldn't apply: $it", color = Danger, fontSize = 13.sp) }
	}
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 13.sp)
	}
}
