package io.github.psd2live.ipad

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.application.WorkspaceSwingDrafts
import io.github.psd2live.core.RigSwingEdit
import io.github.psd2live.core.SwingKind
import io.github.psd2live.core.SwingPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/**
 * Swings (the desktop's swing dialog): choose what sways - a lock of hair, a ribbon, a hem - pick hair, accessory or
 * cloth, its directions and segments, shape it, and watch the audition before keeping it. Existing swings can be edited,
 * removed, or baked into plain keyforms.
 */
@Composable
fun SwingScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	var tick by remember { mutableStateOf(0) }
	var targets by remember { mutableStateOf(listOf<String>()) }
	var draft by remember { mutableStateOf<RigSwingEdit?>(null) }
	var motion by remember { mutableStateOf(0) }
	var playing by remember { mutableStateOf(true) }
	var frame by remember { mutableStateOf<ImageBitmap?>(null) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	val swings = remember(tick) { RigSwings.list(editing) }
	// Meshes, swaying kinds first (hair, then clothing and accessories by name), then the rest.
	val meshes = remember(tick) {
		val swayWords = listOf("hair", "ribbon", "skirt", "bottomwear", "cape", "tail", "sleeve", "neckwear", "headwear", "ear")
		editing.model.rig.puppet.drawables.filter { it.mesh != null }
			.sortedBy { d -> swayWords.indexOfFirst { d.name.contains(it, true) }.let { if (it < 0) swayWords.size else it } }
	}

	fun step(label: String, block: () -> Unit) {
		if (busy != null) return
		busy = label to 0.3; message = null
		scope.launch { try { withContext(worker) { block() }; tick++ } catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { busy = null } }
	}
	fun change(next: (RigSwingEdit) -> RigSwingEdit) {
		val d = draft ?: return
		step("Updating the swing") { draft = next(d) }
	}

	// The audition: the draft applied to a copy of the rig, its parameters swinging, drawn a few times a second.
	LaunchedEffect(draft, playing) {
		val d = draft ?: run { frame = withContext(worker) { outcome.preview.render(outcome.preview.parameters.associate { it.id to it.default }, 760).toImageBitmap() }; return@LaunchedEffect }
		delay(250) // a slider drag: prepare once it rests (a change before then restarts this)
		val prepared = try { withContext(worker) { RigSwings.prepare(editing, d) } } catch (e: Throwable) { message = e.message; return@LaunchedEffect }
		val preview = withContext(worker) { RigPreview(prepared.first) }
		val rest = preview.parameters.associate { it.id to it.default }
		var t = 0f
		while (true) {
			val values = rest + if (playing) WorkspaceSwingDrafts.audition(prepared.second, t).mapKeys { it.key.raw } else emptyMap()
			frame = withContext(worker) { preview.render(values, 760).toImageBitmap() }
			if (!playing) break
			delay(90); t += 0.12f
		}
	}

	val undoRedo = UndoRedo(canUndo = editing.canUndo, canRedo = editing.canRedo,
		undo = { editing.undo(); tick++ }, redo = { editing.redo(); tick++ })
	Column(Modifier.undoTaps(undoRedo).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Swings of ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		Text("A sway for hair, ribbons or cloth: choose what swings, shape it, watch it, keep it.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
			Box(Modifier.weight(1f).height(820.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017)), contentAlignment = Alignment.Center) {
				// Choosing parts: a tap on her adds the part under the finger, a second tap takes it out.
				frame?.let { Image(it, "The swing audition", Modifier.fillMaxSize().pointerInput(draft == null) {
					if (draft != null) return@pointerInput
					detectTapGestures { at ->
						val cw = editing.puppet.canvasWidth; val ch = editing.puppet.canvasHeight
						val k = minOf(size.width / cw, size.height / ch)
						val x = (at.x - (size.width - cw * k) / 2f) / k; val y = (at.y - (size.height - ch * k) / 2f) / k
						scope.launch {
							val id = withContext(worker) { io.github.psd2live.core.PoseTweaks.meshAt(editing.puppet, emptyMap(), x, y) }
							if (id == null) message = "No part there." else { message = null; targets = if (id in targets) targets - id else targets + id }
						}
					}
				}, contentScale = ContentScale.Fit) } ?: Text("Drawing…", color = Muted, fontSize = 14.sp)
			}
			Column(Modifier.width(380.dp).height(820.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				val d = draft
				if (d == null) {
					if (swings.isNotEmpty()) {
						Text("Swings", color = Muted, fontSize = 12.sp)
						for (s in swings) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF24222F)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
							Text(s.name, color = Text1, fontSize = 14.sp)
							Text("${s.motions.joinToString { kindLabel(it.kind) + " ×" + it.segments }}${if (s.hasPhysics) " · pendulum" else ""}${if (s.baked) " · baked" else ""}", color = Muted, fontSize = 12.sp)
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								Chip("Edit", false, enabled = busy == null && !s.baked) { targets = s.targets; motion = 0; draft = s }
								Chip("Remove", false, enabled = busy == null) { step("Removing") { RigSwings.remove(editing, s.id, bake = false) } }
								Chip("Bake", false, enabled = busy == null && !s.baked) { step("Baking") { RigSwings.remove(editing, s.id, bake = true) } }
							}
						}
						Text("Bake keeps the motion as plain keyforms and stops it regenerating.", color = Muted, fontSize = 12.sp)
					}
					Text("New swing: tap what should swing - on her, or in the list", color = Muted, fontSize = 12.sp)
					// The start button above the list, so a choice never needs a scroll to the bottom.
					Chip(if (targets.isEmpty()) "Start a swing (choose parts below)" else "Start a swing on ${targets.size} part${if (targets.size == 1) "" else "s"}",
						targets.isNotEmpty(), enabled = targets.isNotEmpty() && busy == null) {
						step("Setting up the swing") { draft = RigSwings.start(editing, targets); motion = 0 }
					}
					for (m in meshes) {
						val on = m.id.raw in targets
						Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF332C4E) else Color(0xFF24222F))
							.clickable { targets = if (on) targets - m.id.raw else targets + m.id.raw }.padding(horizontal = 12.dp, vertical = 8.dp)) {
							Text(if (on) "✓  ${m.name}" else m.name, color = if (on) Accent else Text1, fontSize = 14.sp)
						}
					}
				} else {
					Text(d.name, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					Text("Kind", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						for ((p, label) in listOf(SwingPreset.HAIR to "Hair", SwingPreset.ACCESSORY to "Accessory", SwingPreset.CLOTH to "Cloth"))
							Chip(label, d.preset == p, enabled = busy == null) { change { RigSwings.preset(editing, it, p) } }
					}
					Text("Directions", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						val kinds = d.motions.map { it.kind }
						for (k in listOf(SwingKind.LATERAL, SwingKind.VERTICAL)) Chip(kindLabel(k), k in kinds, enabled = busy == null && !(k in kinds && kinds.size == 1)) {
							change { RigSwings.kinds(editing, it, if (k in kinds) kinds - k else kinds + k) }
						}
					}
					if (d.motions.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						for ((i, m) in d.motions.withIndex()) Chip("Shape: ${kindLabel(m.kind)}", motion == i) { motion = i }
					}
					val m = d.motions[motion.coerceAtMost(d.motions.lastIndex)]
					Text("Segments  ${m.segments}", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						for (n in 1..RigSwingEdit.MAX_SEGMENTS) Chip("$n", m.segments == n, enabled = busy == null) { change { RigSwings.segments(editing, it, motion, n) } }
					}
					Text("How far it swings  ${(m.shape.magnitude * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
					Slider(m.shape.magnitude, { v -> draft = RigSwings.shape(d, motion) { it.copy(magnitude = v) } }, valueRange = 0.02f..RigSwingEdit.MAX_MAGNITUDE, colors = sliderColors(), modifier = Modifier.height(32.dp))
					Text("Soft tip  ${(m.shape.softness * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
					Slider(m.shape.softness, { v -> draft = RigSwings.shape(d, motion) { it.copy(softness = v) } }, valueRange = 0f..1f, colors = sliderColors(), modifier = Modifier.height(32.dp))
					Text("Lift or droop  ${(m.shape.lift * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
					Slider(m.shape.lift, { v -> draft = RigSwings.shape(d, motion) { it.copy(lift = v) } }, valueRange = -0.5f..0.5f, colors = sliderColors(), modifier = Modifier.height(32.dp))
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip(if (m.shape.flip) "Flipped" else "Flip", m.shape.flip, enabled = busy == null) { draft = RigSwings.shape(d, motion) { it.copy(flip = !it.flip) } }
						Chip(if (d.hasPhysics) "Pendulum: on" else "Pendulum: off", d.hasPhysics, enabled = busy == null) { change { RigSwings.physics(editing, it, !it.hasPhysics) } }
						Chip(if (playing) "Pause" else "Play", false) { playing = !playing }
					}
					Text("The pendulum makes it follow head and body motion in VTube Studio.", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip("Keep this swing", true, enabled = busy == null) {
							step("Keeping the swing") { RigSwings.commit(editing, d) }
							draft = null; targets = emptyList()
						}
						Chip("Drop it", false, enabled = busy == null) { draft = null }
					}
				}
				Box(Modifier.height(8.dp))
				val b = busy
				if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				else if (d == null) {
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						Chip("Undo", false, enabled = undoRedo.canUndo) { undoRedo.undo() }
						Chip("Redo", false, enabled = undoRedo.canRedo) { undoRedo.redo() }
					}
					Chip("Done - keep the changes", true, enabled = editing.canUndo) {
						busy = "Writing the runtime files" to 0.1
						scope.launch {
							try { val o = withContext(worker) { editing.finish(outcome) { s, f -> scope.launch(Dispatchers.Main) { busy = s to f } } }; busy = null; onDone(o) }
							catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
						}
					}
				}
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
			}
		}
	}
}

private fun kindLabel(k: SwingKind) = when (k) { SwingKind.LATERAL -> "Side to side"; SwingKind.VERTICAL -> "Up and down" }

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
