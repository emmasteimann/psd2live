package io.github.psd2live.ipad

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)
private val Field = Color(0xFF2B283C)

private fun fmt(v: Float) = ((v * 100).roundToInt() / 100.0).toString().removeSuffix(".0")

/**
 * Parameters (the desktop's Parameters panel, its keys editor and its parameter dialog): every parameter's range and
 * keys. Scrub a value to see her there; add, move and delete keys (shapes are then made at those keys with Tweak by
 * hand); rename, re-range, blend shape, repeat; make or delete your own. Undo/redo; Done writes the rig's files.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParametersScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	var tick by remember { mutableStateOf(0) }
	var list by remember { mutableStateOf(RigParameters.list(editing)) }
	var selected by remember { mutableStateOf(list.firstOrNull()?.id) }
	var value by remember { mutableStateOf(0f) }
	var key by remember { mutableStateOf<Float?>(null) }
	var snap by remember { mutableStateOf<Float?>(null) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	var making by remember { mutableStateOf(false) }
	val scope = rememberCoroutineScope()
	val current = list.firstOrNull { it.id == selected }

	fun refresh() { list = RigParameters.list(editing); tick++; if (list.none { it.id == selected }) selected = list.firstOrNull()?.id }
	/** One edit on the worker; a refusal from the engine is shown, nothing changes. */
	fun edit(label: String, block: () -> Unit) {
		if (busy != null) return
		busy = label to 0.3; message = null
		scope.launch {
			try { withContext(Dispatchers.Default) { block() }; refresh() }
			catch (e: Throwable) { message = e.message ?: e::class.simpleName }
			finally { busy = null }
		}
	}

	LaunchedEffect(selected) { current?.let { value = it.default; key = it.keys.minByOrNull { k -> abs(k - it.default) } } }
	LaunchedEffect(editing) {
		snapshotFlow { Triple(tick, selected, value) }.collectLatest { (_, id, v) ->
			val pose = if (id == null) emptyMap() else mapOf(id to v)
			image = withContext(Dispatchers.Default) {
				val w = editing.puppet.canvasWidth; val h = editing.puppet.canvasHeight; val s = 900f / maxOf(w, h)
				editing.frame(pose, 0f, 0f, w, h, (w * s).roundToInt(), (h * s).roundToInt()).toImageBitmap()
			}
		}
	}

	val undoRedo = UndoRedo(canUndo = editing.canUndo && busy == null, canRedo = editing.canRedo && busy == null,
		undo = { editing.undo(); refresh() }, redo = { editing.redo(); refresh() })
	Column(Modifier.undoTaps(undoRedo).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Parameters for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		Text("Keys are the values a parameter has shapes at. Add keys here, then pose her at a key in Tweak by hand to shape it.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Box(Modifier.weight(1f).height(860.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017)), contentAlignment = Alignment.Center) {
				image?.let { Image(it, contentDescription = "The rig at this value", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) } ?: Text("Drawing…", color = Muted)
			}
			Column(Modifier.width(400.dp).height(860.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = undoRedo.canUndo) { undoRedo.undo() }
					Chip("Redo", false, enabled = undoRedo.canRedo) { undoRedo.redo() }
					Chip(if (making) "Cancel new" else "New parameter", making) { making = !making }
				}
				val b = busy
				if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				else Chip("Done - keep ${editing.edits} change${if (editing.edits == 1) "" else "s"}", true, enabled = editing.edits > 0) {
					busy = "Writing the runtime files" to 0.2
					scope.launch {
						try { val o = withContext(Dispatchers.Default) { editing.finish(outcome) { s, f -> scope.launch(Dispatchers.Main) { busy = s to f } } }; busy = null; onDone(o) }
						catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
					}
				}
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
				if (making) NewParameter { name, min, max, def, blend ->
					edit("Adding $name") { val id = RigParameters.create(editing, name, min, max, def, blend); selected = id }
					making = false
				}
				if (current != null) {
					Text(current.name, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					Text(current.id, color = Muted, fontSize = 11.sp)
					fun place(v: Float) = snap?.let { s -> ((v / s).roundToInt() * s).coerceIn(current.min, current.max) } ?: v
					Text("See her at  ${fmt(value)}", color = Muted, fontSize = 12.sp)
					Slider(value.coerceIn(current.min, current.max), { value = place(it) }, valueRange = current.min..current.max, colors = sliderColors(), modifier = Modifier.height(28.dp))
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
						Text("Snap", color = Muted, fontSize = 12.sp)
						for (s in listOf<Float?>(null, 1f, 5f, 15f, 30f)) Chip(s?.let(::fmt) ?: "Off", snap == s) { snap = s; value = place(value) }
					}
					Text("Keys (tap one to pick it; the pose jumps there)", color = Muted, fontSize = 12.sp)
					FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						for (k in current.keys) Chip(fmt(k), key == k) { key = k; value = k }
						if (current.keys.isEmpty()) Text("None yet: nothing moves with it. Add two keys to start.", color = Muted, fontSize = 12.sp)
					}
					FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						val at = value
						Chip("Add a key at ${fmt(at)}", true, enabled = busy == null && current.keys.none { abs(it - at) < 1e-4f }) {
							// A parameter with no keys needs two to start (the engine's rule): add its ends with it.
							val values = if (current.keys.isEmpty()) listOf(current.min, at, current.max).distinct() else listOf(at)
							edit("Adding a key") { RigParameters.keys(editing, current.id, "add", values) }; key = at
						}
						val k = key
						if (k != null && current.keys.any { it == k }) {
							Chip("Move ${fmt(k)} to ${fmt(at)}", false, enabled = busy == null && abs(k - at) > 1e-4f) {
								edit("Moving the key") { RigParameters.keys(editing, current.id, "move", listOf(at), from = k) }; key = at
							}
							Chip("Delete key ${fmt(k)}", false, enabled = busy == null) {
								edit("Deleting the key") { RigParameters.keys(editing, current.id, "delete", listOf(k)) }; key = null
							}
						}
					}
					Definition(current) { name, min, max, def, blend, repeat ->
						edit("Saving ${current.name}") { RigParameters.update(editing, current, name, min, max, def, blend, repeat) }
					}
					if (current.blendShape) BlendLimitsPanel(editing, current, busy == null) { label, block -> edit(label, block) }
					if (current.created) Chip("Delete this parameter", false, enabled = busy == null && !current.generated) {
						edit("Deleting ${current.name}") { RigParameters.delete(editing, current) }
					}
					if (current.generated) Text("A swing or simulation made this one: change it there.", color = Muted, fontSize = 12.sp)
				}
				Text("All parameters", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
				for (p in list) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (p.id == selected) Color(0xFF3A3358) else Color(0xFF24222F))
					.clickable { selected = p.id }.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
					Text(p.name + (if (p.created) "  ·  yours" else ""), color = Text1, fontSize = 14.sp, modifier = Modifier.weight(1f))
					Text("${fmt(p.min)}…${fmt(p.max)} · ${p.keys.size} keys", color = Muted, fontSize = 12.sp)
				}
			}
		}
	}
}

/** Name, lowest, default, highest, blend shape and repeat for a parameter; Save when something changed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Definition(p: RigParameters.Info, onSave: (String, Float, Float, Float, Boolean, Boolean) -> Unit) {
	var name by remember(p) { mutableStateOf(p.name) }
	var min by remember(p) { mutableStateOf(fmt(p.min)) }
	var def by remember(p) { mutableStateOf(fmt(p.default)) }
	var max by remember(p) { mutableStateOf(fmt(p.max)) }
	var blend by remember(p) { mutableStateOf(p.blendShape) }
	var repeat by remember(p) { mutableStateOf(p.repeat) }
	Text("Settings", color = Muted, fontSize = 12.sp)
	TextBox(name, "Name") { name = it }
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Column(Modifier.weight(1f)) { Text("Lowest", color = Muted, fontSize = 11.sp); TextBox(min, "-1") { min = it } }
		Column(Modifier.weight(1f)) { Text("Default", color = Muted, fontSize = 11.sp); TextBox(def, "0") { def = it } }
		Column(Modifier.weight(1f)) { Text("Highest", color = Muted, fontSize = 11.sp); TextBox(max, "1") { max = it } }
	}
	FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Chip("Blend shape", blend) { blend = !blend }
		Chip("Repeats (loops round)", repeat) { repeat = !repeat }
	}
	val lo = min.toFloatOrNull(); val hi = max.toFloatOrNull(); val d = def.toFloatOrNull()
	val changed = name != p.name || lo != p.min || hi != p.max || d != p.default || blend != p.blendShape || repeat != p.repeat
	Chip("Save these settings", true, enabled = changed && lo != null && hi != null && d != null) { onSave(name, lo!!, hi!!, d!!, blend, repeat) }
}

@Composable
private fun NewParameter(onCreate: (String, Float, Float, Float, Boolean) -> Unit) {
	var name by remember { mutableStateOf("") }
	var min by remember { mutableStateOf("-1") }; var max by remember { mutableStateOf("1") }
	var blend by remember { mutableStateOf(false) }
	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF24222F)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		TextBox(name, "Name (e.g. Lantern swing)") { name = it }
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Column(Modifier.weight(1f)) { Text("Lowest", color = Muted, fontSize = 11.sp); TextBox(min, "-1") { min = it } }
			Column(Modifier.weight(1f)) { Text("Highest", color = Muted, fontSize = 11.sp); TextBox(max, "1") { max = it } }
		}
		Chip("Blend shape", blend) { blend = !blend }
		val lo = min.toFloatOrNull(); val hi = max.toFloatOrNull()
		Chip("Add it", true, enabled = name.isNotBlank() && lo != null && hi != null && lo < hi) { onCreate(name.trim(), lo!!, hi!!, if (lo!! <= 0f && hi!! >= 0f) 0f else lo, blend) }
	}
}

@Composable
private fun TextBox(text: String, hint: String, onChange: (String) -> Unit) {
	BasicTextField(text, onChange, textStyle = TextStyle(color = Text1, fontSize = 15.sp), cursorBrush = SolidColor(Accent), singleLine = true,
		decorationBox = { inner -> Box { if (text.isEmpty()) Text(hint, color = Muted, fontSize = 15.sp); inner() } },
		modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Field).padding(10.dp))
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF5B4B8A) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
		Text(label, color = if (enabled) Text1 else Muted, fontSize = 13.sp)
	}
}

/**
 * A blend shape's weight limits (the desktop's blend limits): another slider that fades this blend shape out - a smile
 * that eases off as the mouth opens. Each limit here is a simple fade over a range of that slider.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BlendLimitsPanel(editing: RigEditing, current: RigParameters.Info, enabled: Boolean, edit: (String, () -> Unit) -> Unit) {
	val limits = RigBlendLimits.limits(editing, current.id)
	val others = editing.puppet.parameters.filter { it.id.raw != current.id && it.kind != org.umamo.runtime.model.ParameterKind.BLEND_SHAPE }
	var by by remember(current.id) { mutableStateOf<String?>(null) }
	var choosing by remember(current.id) { mutableStateOf(false) }
	val p = others.firstOrNull { it.id.raw == by }
	var from by remember(by) { mutableStateOf(p?.default ?: 0f) }
	var to by remember(by) { mutableStateOf(p?.max ?: 1f) }
	Text("Limits (another slider fades this blend shape out)", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
	if (limits.isEmpty()) Text("None: it always applies fully.", color = Muted, fontSize = 12.sp)
	for (l in limits) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
		val name = editing.puppet.parameters.firstOrNull { it.id == l.parameterId }?.name ?: l.parameterId.raw
		Text("$name: " + l.points.joinToString(" → ") { "${fmt(it.value)} ${(it.weight * 100).roundToInt()}%" }, color = Text1, fontSize = 12.sp, modifier = Modifier.weight(1f))
		Chip("Remove", false, enabled = enabled) { val rest = limits - l; edit("Removing the limit") { RigBlendLimits.put(editing, current.id, rest) } }
	}
	Chip(if (p == null) "Add a limit - choose the slider" else "Limited by ${p.name}", choosing) { choosing = !choosing }
	if (choosing) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		for (o in others.filter { o -> limits.none { it.parameterId == o.id } }) Chip(o.name, o.id.raw == by) { by = o.id.raw; choosing = false }
	}
	if (p != null) {
		Text("Full until ${fmt(from)}, gone by ${fmt(to)}", color = Muted, fontSize = 12.sp)
		Slider(from, { from = it.coerceAtMost(to) }, valueRange = p.min..p.max, colors = sliderColors(), modifier = Modifier.height(28.dp))
		Slider(to, { to = it.coerceAtLeast(from) }, valueRange = p.min..p.max, colors = sliderColors(), modifier = Modifier.height(28.dp))
		Chip("Add this limit", true, enabled = enabled && to > from) {
			val id = p.id.raw; val a = from; val b = to
			edit("Adding the limit") { RigBlendLimits.put(editing, current.id, limits + RigBlendLimits.fade(editing, id, a, b)) }
			by = null
		}
	}
}
