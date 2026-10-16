package io.github.psd2live.ipad

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.core.PhysicsCatalog
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.format.compile.FrameSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sin

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/**
 * Physics on the iPad: drag on the character and her head follows the finger (as face tracking would turn it) while
 * the physics runs live; every group's pendulum - length, how freely it moves, how much it lags, how hard it
 * accelerates - and its on/off are sliders. Apply writes the rig's files with the new physics.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PhysicsScreen(outcome: RigOutcome, onApplied: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val available = remember(outcome) { outcome.model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw } }
	var config by remember(outcome) { mutableStateOf(outcome.config) }
	val groups = remember(config) { PhysicsCatalog.groups(outcome.model.analysis, config, available) }
	var head by remember { mutableStateOf(0f to 0f) }
	var dragging by remember { mutableStateOf(false) }
	var wobble by remember { mutableStateOf(0L) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var open by remember { mutableStateOf<String?>(null) }
	var addingInput by remember { mutableStateOf(false) }
	var addingOutput by remember { mutableStateOf(false) }
	val paramNames = remember(outcome) { outcome.model.rig.puppet.parameters.associate { it.id.raw to it.name } }
	var applying by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	var note by remember { mutableStateOf<String?>(null) }
	var importing by remember { mutableStateOf(false) }
	var working by remember { mutableStateOf(false) }
	val scope = rememberCoroutineScope()

	LaunchedEffect(config, wobble) {
		// A physics session for these settings, stepped ~30 times a second with the head where the finger put it.
		val started = wobble
		withContext(Dispatchers.Default) {
			val ir = RigIrCompiler.compile(outcome.model.copy(config = config))
			val session = IrFrameRenderer.open(ir, true)
			val spec = FrameSpec.canvas(ir, 720)
			var x = 0f; var y = 0f
			var t = 0f
			try {
				while (isActive) {
					val (tx, ty) = head
					// The head eases toward the finger and back to the middle when it lets go, as a tracked face would.
					val shake = if (started > 0 && t < 1.6f) 28f * sin(t * 11f) * (1f - t / 1.6f) else 0f
					x += ((if (dragging) tx else 0f) + shake - x) * 0.35f
					y += ((if (dragging) ty else 0f) - y) * 0.35f
					val pose = mapOf("ParamAngleX" to x, "ParamAngleY" to y, "ParamBodyAngleX" to x / 3f, "ParamAngleZ" to -x / 4f)
					val bitmap = session.render(pose, 1f / 30f, spec, null).toImageBitmap()
					withContext(Dispatchers.Main) { image = bitmap }
					t += 1f / 30f
					delay(25)
				}
			} finally { session.close() }
		}
	}

	fun edit(group: RigPhysicsEdit, change: (RigPhysicsEdit) -> RigPhysicsEdit) {
		val updated = change(group)
		val edits = config.rigEdits.physicsEdits.filter { it.id != group.id } + updated
		config = config.copy(rigEdits = config.rigEdits.copy(physicsEdits = edits))
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Physics for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (applying == null) onCancel() })
		}
		Text("Drag on her and the head follows your finger; let go and watch everything settle. Shake does a quick head shake.",
			color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(860.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				Box(Modifier.fillMaxSize().pointerInput(Unit) {
					awaitEachGesture {
						val down = awaitFirstDown()
						dragging = true
						fun aim(px: Float, py: Float) { head = ((px / w - 0.5f) * 2f * 30f).coerceIn(-30f, 30f) to (-(py / h - 0.35f) * 2f * 30f).coerceIn(-30f, 30f) }
						aim(down.position.x, down.position.y)
						while (true) {
							val event = awaitPointerEvent()
							val c = event.changes.firstOrNull { it.id == down.id } ?: break
							if (!c.pressed) break
							aim(c.position.x, c.position.y); c.consume()
						}
						dragging = false
					}
				}) {
					image?.let { Image(it, contentDescription = "The rig with live physics", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
						?: Text("Starting…", color = Muted, modifier = Modifier.align(Alignment.Center))
				}
			}
			Column(Modifier.width(360.dp).height(860.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Shake", false) { wobble = wobble + 1 }
					Chip("Defaults", false) { config = config.copy(rigEdits = config.rigEdits.copy(physicsEdits = outcome.config.rigEdits.physicsEdits.filter { false },
						disabledPhysicsIds = emptySet())) }
				}
				Chip(if (importing) "Close the list" else "Copy physics from another rig…", importing) { importing = !importing }
				if (importing) {
					Text("Takes that rig's physics groups as they are (its own Fps too). Groups here on the same sliders switch off.", color = Muted, fontSize = 12.sp)
					val own = outcome.folder.trimEnd('/')
					val files = remember { RigFiles.physicsFiles().filter { !it.startsWith("$own/") } }
					if (files.isEmpty()) Text("No other rig has physics yet (Rigs/<name>/<name>.physics3.json).", color = Muted, fontSize = 13.sp)
					for (path in files) Text(path.substringAfterLast('/').removeSuffix(".physics3.json"), color = Accent, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable {
						if (working) return@clickable
						working = true; note = "Reading ${path.substringAfterLast('/')}…"
						scope.launch {
							note = try {
								val r = withContext(Dispatchers.Default) { RigPhysicsExtras.import(outcome.model, config, RigPhysicsExtras.readFile(path)) }
								config = r.config; importing = false
								"Copied ${r.ids.size} group${if (r.ids.size == 1) "" else "s"}" +
									(if (r.disabled.isNotEmpty()) "; switched off ${r.disabled.joinToString()}" else "") +
									(if (r.missing.isNotEmpty()) ". This rig lacks some sliders they use: ${r.missing.values.flatten().distinct().joinToString()}" else "") +
									". Drag her to try it, then Apply."
							} catch (e: Throwable) { "Couldn't copy it: ${e.message ?: e::class.simpleName}" }
							working = false
						}
					}.padding(vertical = 6.dp))
				}
				note?.let { Text(it, color = Muted, fontSize = 13.sp) }
				for (group in groups) {
					val id = group.id
					val on = group.enabled
					Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF24222F)).clickable { open = if (open == id) null else id }
						.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
						Column(Modifier.weight(1f)) {
							Text(group.setting.name, color = if (on) Text1 else Muted, fontSize = 14.sp)
							Text(group.issue?.let { "Not exported: ${it.code.name.lowercase().replace('_', ' ')}" }
								?: group.shadowedBy?.let { "Replaced by $it" }
								?: "${group.setting.segments.size} pendulum parts" + if (group.overridden) ", changed" else "", color = Muted, fontSize = 11.sp)
						}
						Chip(if (on) "On" else "Off", on) {
							val disabled = config.rigEdits.disabledPhysicsIds
							config = when (group.origin) {
								io.github.psd2live.core.PhysicsOrigin.PRESET -> when {
									id.contains("Front", true) -> config.copy(physicsFrontHair = !on)
									id.contains("Back", true) -> config.copy(physicsBackHair = !on)
									else -> config.copy(physicsEyeJelly = !on)
								}
								else -> config.copy(rigEdits = config.rigEdits.copy(disabledPhysicsIds = if (on) disabled + id else disabled - id))
							}
						}
					}
					if (open == id) {
						val g = group.setting
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
							for ((label, target) in listOf("Fit to the art" to 1f, "Fit softer" to 0.6f)) Chip(label, false, enabled = !working) {
								working = true; note = "Fitting ${g.name}…"
								scope.launch {
									note = try {
										config = withContext(Dispatchers.Default) { RigPhysicsExtras.fit(outcome.model, config, id, target) }
										"Fitted ${g.name}: a full head sway now swings it to ${(target * 100).roundToInt()}% of its range."
									} catch (e: Throwable) { "Couldn't fit it: ${e.message ?: e::class.simpleName}" }
									working = false
								}
							}
						}
						Text("Presets", color = Text1, fontSize = 13.sp, fontWeight = FontWeight.Bold)
						androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
							for (preset in io.github.psd2live.core.PhysicsPresets.builtins(io.github.psd2live.core.PhysicsPresets.Kind.INPUT))
								Chip(preset.name, io.github.psd2live.core.PhysicsPresets.matches(preset, g)) { edit(g) { io.github.psd2live.core.PhysicsPresets.apply(preset, it, available) } }
						}
						androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
							for (preset in io.github.psd2live.core.PhysicsPresets.builtins(io.github.psd2live.core.PhysicsPresets.Kind.PENDULUM))
								Chip(preset.name, io.github.psd2live.core.PhysicsPresets.matches(preset, g)) { edit(g) { io.github.psd2live.core.PhysicsPresets.apply(preset, it, available) } }
						}
						Text("What shakes it", color = Text1, fontSize = 13.sp, fontWeight = FontWeight.Bold)
						for (input in g.inputs) {
							Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
								Text(paramNames[input.parameter] ?: input.parameter, color = Text1, fontSize = 13.sp, modifier = Modifier.weight(1f))
								Chip(if (input.type == io.github.psd2live.core.PhysicsSourceType.X) "Sideways" else "Angle", false) {
									edit(g) { it.copy(inputs = it.inputs.map { i -> if (i === input) i.copy(type = if (i.type == io.github.psd2live.core.PhysicsSourceType.X)
										io.github.psd2live.core.PhysicsSourceType.ANGLE else io.github.psd2live.core.PhysicsSourceType.X) else i }) } }
								Chip("⇄", input.reflect) { edit(g) { it.copy(inputs = it.inputs.map { i -> if (i === input) i.copy(reflect = !i.reflect) else i }) } }
								Text("✕", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp).clickable {
									edit(g) { it.copy(inputs = it.inputs.filter { i -> i !== input }) } })
							}
							ValueRow("Weight", input.weight, 0f..100f) { v -> edit(g) { it.copy(inputs = it.inputs.map { i -> if (i === input) i.copy(weight = v) else i }) } }
						}
						Chip(if (addingInput) "Close the list" else "Add what shakes it", addingInput) { addingInput = !addingInput }
						if (addingInput) for ((pid, pname) in paramNames.filterKeys { k -> g.inputs.none { it.parameter == k } && k !in g.outputParameters }) {
							Text(pname, color = Accent, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable {
								edit(g) { it.copy(inputs = it.inputs + io.github.psd2live.core.PhysicsInput(pid, 50f)) }; addingInput = false
							}.padding(vertical = 4.dp))
						}
						Text("What it moves", color = Text1, fontSize = 13.sp, fontWeight = FontWeight.Bold)
						for (output in g.outputs) {
							Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
								Text("${paramNames[output.parameter] ?: output.parameter} ← part ${output.vertex}", color = Text1, fontSize = 13.sp, modifier = Modifier.weight(1f))
								Chip("⇄", output.reflect) { edit(g) { it.copy(outputs = it.outputs.map { o -> if (o === output) o.copy(reflect = !o.reflect) else o }) } }
							}
							if (g.segments.size > 1) ValueRow("Driven by part", output.vertex.toFloat(), 1f..g.segments.size.toFloat()) { v ->
								edit(g) { it.copy(outputs = it.outputs.map { o -> if (o === output) o.copy(vertex = v.roundToInt().coerceIn(1, g.segments.size)) else o }) } }
							ValueRow("Scale", output.scale, 0f..10f) { v -> edit(g) { it.copy(outputs = it.outputs.map { o -> if (o === output) o.copy(scale = v) else o }) } }
						}
						Chip(if (addingOutput) "Close the list" else "Add what it moves", addingOutput) { addingOutput = !addingOutput }
						if (addingOutput) for ((pid, pname) in paramNames.filterKeys { k -> g.outputs.none { it.parameter == k } && g.inputs.none { it.parameter == k } }) {
							Text(pname, color = Accent, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable {
								edit(g) { it.copy(outputs = it.outputs + io.github.psd2live.core.PhysicsOutput(pid, it.segments.size, 1f)) }; addingOutput = false
							}.padding(vertical = 4.dp))
						}
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
							Text("Pendulum parts: ${g.segments.size}", color = Text1, fontSize = 13.sp, fontWeight = FontWeight.Bold)
							Chip("+", false, enabled = g.segments.size < RigPhysicsEdit.MAX_SEGMENTS) { edit(g) { it.copy(segments = it.segments + it.segments.last()) } }
							Chip("−", false, enabled = g.segments.size > 1) {
								edit(g) { val n = it.segments.size - 1; it.copy(segments = it.segments.dropLast(1), outputs = it.outputs.map { o -> if (o.vertex > n) o.copy(vertex = n) else o }) } }
						}
						for ((index, segment) in group.setting.segments.withIndex()) {
							Text("Part ${index + 1}", color = Text1, fontSize = 13.sp, fontWeight = FontWeight.Bold)
							fun set(change: (PhysicsSegment) -> PhysicsSegment) = edit(group.setting) { s ->
								s.copy(segments = s.segments.mapIndexed { i, old -> if (i == index) change(old) else old })
							}
							ValueRow("Length (longer swings slower)", segment.length, 1f..60f) { v -> set { it.copy(length = v) } }
							ValueRow("Mobility (how freely it moves)", segment.mobility, 0f..1f) { v -> set { it.copy(mobility = v) } }
							ValueRow("Delay (how much it lags)", segment.delay, 0.1f..2f) { v -> set { it.copy(delay = v) } }
							ValueRow("Acceleration (how hard it pulls)", segment.acceleration, 0f..5f) { v -> set { it.copy(acceleration = v) } }
						}
					}
				}
				val progress = applying
				if (progress != null) {
					Text(progress.first, color = Muted, fontSize = 13.sp)
					LinearProgressIndicator(progress = progress.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent)
				} else Chip("Apply - keep this physics", true, enabled = config != outcome.config) {
					applying = "Writing the runtime files" to 0.2; failure = null
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
	}
}

@Composable
private fun ValueRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
	Text("$label  ${(value * 100).roundToInt() / 100.0}", color = Muted, fontSize = 12.sp)
	Slider(value, { onChange(it) }, valueRange = range, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
		modifier = Modifier.height(28.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
