package io.github.psd2live.ipad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PartTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.runtime.model.OrgChild
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/**
 * Pose groups (Cubism's pose3): sets of parts of which only one shows at a time - arm down or arm waving, a hat or no
 * hat. VTube Studio shows the first member and switches when a motion or expression fades another one in. Tap a
 * member to see her with it showing.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PoseGroupsScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	val scope = rememberCoroutineScope()
	var tick by remember { mutableStateOf(0) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	var group by remember { mutableStateOf<Int?>(null) } // the group being edited
	var showing by remember { mutableStateOf<String?>(null) } // the member shown in the preview
	var adding by remember { mutableStateOf(false) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	val parts = remember(tick) { RigPoseGroups.parts(editing) }
	val names = parts.associate { it.id to it.name }
	val groups = remember(tick) { RigPoseGroups.groups(editing) }
	var fade by remember(tick) { mutableStateOf(RigPoseGroups.fade(editing)) }
	val cw = editing.puppet.canvasWidth; val ch = editing.puppet.canvasHeight

	fun step(label: String, block: () -> Unit) {
		if (busy != null) return
		busy = label to 0.3; message = null
		scope.launch { try { withContext(worker) { block() }; tick++ } catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { busy = null } }
	}
	/** The part a mesh sits in (the innermost one). */
	fun partOf(mesh: String): String? = editing.puppet.parts.firstOrNull { p -> p.children.any { it is OrgChild.Drawable && it.id.raw == mesh } }?.id?.raw

	// The preview: in the group being edited, only the chosen member shows (as the pose would).
	LaunchedEffect(tick, group, showing) {
		val g = group?.let { groups.getOrNull(it) }
		val shown = showing ?: g?.firstOrNull()
		val pose = g?.associate { PartTracks.trackOf(it) to if (it == shown) 1f else 0f }.orEmpty()
		image = withContext(worker) { editing.frame(pose, 0f, 0f, cw, ch, 1000, (1000 * ch / cw).roundToInt().coerceAtLeast(1)).toImageBitmap() }
	}

	val undoRedo = UndoRedo(canUndo = editing.canUndo && busy == null, canRedo = editing.canRedo && busy == null,
		undo = { editing.undo(); tick++ }, redo = { editing.redo(); tick++ })
	Column(Modifier.undoTaps(undoRedo).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Pose groups of ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		HowTo("What this is for", listOf(
			"A pose group is a set of parts where only one shows at a time: an arm resting or the same arm waving, a hat or no hat.",
			"New group, then Add parts: tap them on her (or in the list). The first one is what shows normally; a motion or expression that fades another member in swaps them.",
			"Tap a member to see her with it showing. Fade is how long the swap takes.",
			"Done keeps the changes."))
		Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(780.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				val scale = minOf(w / cw, h / ch); val left = (w - cw * scale) / 2f; val top = (h - ch * scale) / 2f
				Canvas(Modifier.fillMaxSize().pointerInput(adding, group, w, h) {
					detectTapGestures { o ->
						val g = group ?: return@detectTapGestures
						if (!adding) return@detectTapGestures
						val x = (o.x - left) / scale; val y = (o.y - top) / scale
						scope.launch {
							val mesh = withContext(worker) { io.github.psd2live.core.PoseTweaks.meshAt(editing.puppet, emptyMap(), x, y) }
							val part = mesh?.let(::partOf)
							if (part == null) { message = "No part there."; return@launch }
							val members = groups.getOrNull(g).orEmpty()
							if (part in members) { message = "${names[part] ?: part} is already in this group."; return@launch }
							// A part is in one group at most; a new group is saved with its first part.
							val next = if (g == groups.size) groups.map { it - part } + listOf(listOf(part)) else groups.mapIndexed { i, m -> if (i == g) m + part else m - part }
							step("Adding ${names[part] ?: part}…") { RigPoseGroups.put(editing, next) }
						}
					}
				}) {
					image?.let { drawImage(it, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize((cw * scale).roundToInt(), (ch * scale).roundToInt())) }
				}
				if (image == null) Text("Drawing…", color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
			}
			Column(Modifier.width(400.dp).height(780.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = undoRedo.canUndo) { undoRedo.undo() }
					Chip("Redo", false, enabled = undoRedo.canRedo) { undoRedo.redo() }
					Chip("Done - keep the changes", true, enabled = editing.canUndo && busy == null) {
						busy = "Writing the runtime files" to 0.1
						scope.launch {
							try { val o = withContext(worker) { editing.finish(outcome) { s, f -> scope.launch(Dispatchers.Main) { busy = s to f } } }; busy = null; onDone(o) }
							catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
						}
					}
				}
				val b = busy
				if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
				Text("Fade  ${(fade * 100).roundToInt() / 100.0} s", color = Muted, fontSize = 12.sp)
				Slider(fade, { fade = it }, onValueChangeFinished = { val f = fade; step("Setting the fade…") { RigPoseGroups.put(editing, groups, f) } },
					valueRange = 0f..2f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent), modifier = Modifier.height(28.dp))
				Chip("New group", false, enabled = busy == null) {
					// A group needs a member to be kept; it starts empty here and is saved with its first part.
					group = groups.size; adding = true; showing = null
				}
				if (groups.isEmpty() && group == null) Text("No pose groups yet.", color = Muted, fontSize = 13.sp)
				val editingGroups = if (group == groups.size) groups + listOf(emptyList()) else groups
				for ((gi, members) in editingGroups.withIndex()) {
					val on = gi == group
					Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF332C4E) else Color(0xFF24222F))
						.clickable { group = gi; showing = null }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						Text("Group ${gi + 1}" + if (members.size == 1) "  (needs a second part)" else "", color = Text1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
						if (members.isEmpty()) Text("Tap parts on her to add them.", color = Muted, fontSize = 12.sp)
						androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
							for ((mi, m) in members.withIndex()) {
								val shownHere = on && (showing ?: members.firstOrNull()) == m
								Chip((if (mi == 0) "★ " else "") + (names[m] ?: m), shownHere) { group = gi; showing = m }
							}
						}
						if (on) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							Chip(if (adding) "Tapping her adds parts: on" else "Add parts", adding) { adding = !adding }
							val s = showing
							if (s != null && s in members && members.firstOrNull() != s) Chip("Show this first", false, enabled = busy == null) {
								val next = groups.mapIndexed { i, g -> if (i == gi) listOf(s) + (g - s) else g }; step("Reordering…") { RigPoseGroups.put(editing, next) }
							}
							if (s != null && s in members) Chip("Take ${names[s] ?: s} out", false, enabled = busy == null) {
								val next = groups.mapIndexed { i, g -> if (i == gi) g - s else g }; showing = null; step("Taking it out…") { RigPoseGroups.put(editing, next) }
							}
							if (gi < groups.size) Chip("Delete group", false, enabled = busy == null) {
								val next = groups.filterIndexed { i, _ -> i != gi }; group = null; step("Deleting the group…") { RigPoseGroups.put(editing, next) }
							}
						}
					}
				}
				if (group != null && adding) {
					Text("Or pick from the parts", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
					val g = group!!
					val members = editingGroups.getOrNull(g).orEmpty()
					for (p in parts.filter { it.id !in members }) Text(p.name, color = Accent, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable(enabled = busy == null) {
						val next = if (g == groups.size) groups.map { it - p.id } + listOf(listOf(p.id)) else groups.mapIndexed { i, m -> if (i == g) m + p.id else m - p.id }
						step("Adding ${p.name}…") { RigPoseGroups.put(editing, next) }
					}.padding(vertical = 5.dp))
				}
			}
		}
	}
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF5B4B8A) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 13.sp)
	}
}
