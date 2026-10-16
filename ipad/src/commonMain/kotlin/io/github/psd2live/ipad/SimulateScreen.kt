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
private val Warn = Color(0xFFFFC46B)

/**
 * Simulate (the desktop's Simulate mode): hair and cloth as soft bodies, baked into the rig so it sways like real
 * fabric in VTube Studio. Presets add them (clothing is simulated only where it hangs loose); the classic sway can
 * come back; each one can be re-baked, cleared or removed. Baking takes a while. Undo/redo; Done writes the rig.
 */
@Composable
fun SimulateScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	var sims by remember { mutableStateOf(RigSimulation.list(editing)) }
	var busy by remember { mutableStateOf<Pair<String, Float>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var tick by remember { mutableStateOf(0) }
	var sway by remember { mutableStateOf(0f) }
	val scope = rememberCoroutineScope()
	val cw = editing.puppet.canvasWidth; val ch = editing.puppet.canvasHeight

	fun run(label: String, block: ((Float) -> Unit) -> kotlinx.serialization.json.JsonObject?) {
		if (busy != null) return
		busy = label to 0f; message = null; warnings = emptyList()
		scope.launch {
			try {
				val report = withContext(Dispatchers.Default) { block { f -> scope.launch(Dispatchers.Main) { busy = label to f } } }
				warnings = (report?.get("warnings") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty() +
					listOfNotNull((report?.get("bake_error") as? kotlinx.serialization.json.JsonPrimitive)?.content)
				sims = RigSimulation.list(editing); tick++
			} catch (e: Throwable) { message = e.message ?: e::class.simpleName }
			finally { busy = null }
		}
	}

	LaunchedEffect(editing) {
		// A still pose: the baked sway itself is driven by physics as she moves (Physics screen, VTube Studio).
		snapshotFlow { tick to sway }.collectLatest { (_, s) ->
			image = withContext(Dispatchers.Default) {
				val k = 900f / maxOf(cw, ch)
				editing.frame(mapOf("ParamBodyAngleX" to s, "ParamAngleX" to s * 2f), 0f, 0f, cw, ch, (cw * k).roundToInt(), (ch * k).roundToInt()).toImageBitmap()
			}
		}
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Simulate ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		Text("Hair and cloth as soft bodies, baked into the rig so they sway like the real thing. The sway plays when she moves: drag her in Physics after Done, or in VTube Studio.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Column(Modifier.weight(1f)) {
				Box(Modifier.fillMaxWidth().height(800.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017)), contentAlignment = Alignment.Center) {
					image?.let { Image(it, contentDescription = "The rig leaning", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) } ?: Text("Drawing…", color = Muted)
				}
				Text("Lean  ${sway.roundToInt()}", color = Muted, fontSize = 12.sp)
				Slider(sway, { sway = it }, valueRange = -10f..10f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent), modifier = Modifier.height(28.dp))
			}
			Column(Modifier.width(380.dp).height(860.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = editing.canUndo && busy == null) { editing.undo(); sims = RigSimulation.list(editing); tick++ }
					Chip("Redo", false, enabled = editing.canRedo && busy == null) { editing.redo(); sims = RigSimulation.list(editing); tick++ }
				}
				val b = busy
				if (b != null) {
					Text(b.first, color = Muted, fontSize = 13.sp)
					LinearProgressIndicator(progress = b.second.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth(), color = Accent)
				} else Chip("Done - keep the changes", true, enabled = editing.model.config.rigEdits.simEdits != outcome.model.config.rigEdits.simEdits || editing.edits > 0) {
					busy = "Writing the runtime files" to 0.2f
					scope.launch {
						try { val o = withContext(Dispatchers.Default) { editing.finish(outcome) { s, f -> scope.launch(Dispatchers.Main) { busy = s to f.toFloat() } } }; busy = null; onDone(o) }
						catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
					}
				}
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
				for (w in warnings) Text(w, color = Warn, fontSize = 12.sp)
				Text("Add", color = Muted, fontSize = 12.sp)
				Chip("Simulate the clothing (where it hangs loose)", true, enabled = busy == null) { run("Simulating the clothing - this takes a while…") { p -> RigSimulation.preset(editing, "clothing", p) } }
				Chip("Simulate the front hair", false, enabled = busy == null) { run("Simulating the front hair…") { p -> RigSimulation.preset(editing, "front_hair", p) } }
				Chip("Simulate the back hair", false, enabled = busy == null) { run("Simulating the back hair…") { p -> RigSimulation.preset(editing, "back_hair", p) } }
				Text("Or go back", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
				Chip("Classic sway for the front hair", false, enabled = busy == null) { run("Restoring the classic sway…") { p -> RigSimulation.preset(editing, "classic_front_hair", p) } }
				Chip("Classic sway for the back hair", false, enabled = busy == null) { run("Restoring the classic sway…") { p -> RigSimulation.preset(editing, "classic_back_hair", p) } }
				Chip("Remove the clothing simulation", false, enabled = busy == null && sims.isNotEmpty()) { run("Removing…") { p -> RigSimulation.preset(editing, "remove_clothing", p) } }
				Text("Simulations", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
				if (sims.isEmpty()) Text("None yet.", color = Muted, fontSize = 13.sp)
				for (s in sims) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF24222F)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text("${s.name}  ·  ${s.kind}, ${s.targets} part${if (s.targets == 1) "" else "s"}", color = Text1, fontSize = 14.sp)
					Text(if (!s.baked) "Not baked: it won't move in VTube Studio until it is." else
						"Baked - matches the simulation ${((s.fit ?: 0f) * 100).roundToInt()}%, off by up to ${(s.errorPx ?: 0f).roundToInt()} px.",
						color = if (s.baked && (s.fit ?: 0f) >= 0.6f) Muted else Warn, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip(if (s.baked) "Bake again" else "Bake", true, enabled = busy == null) { run("Baking ${s.name}…") { p -> RigSimulation.bake(editing, s.id, p) } }
						if (s.baked) Chip("Clear the bake", false, enabled = busy == null) { run("Clearing…") { RigSimulation.clearBake(editing, s.id) } }
						Chip("Remove", false, enabled = busy == null) { run("Removing…") { RigSimulation.delete(editing, s.id) } }
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
