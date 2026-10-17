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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)
private val Warn = Color(0xFFFFC46B)

/**
 * Layer roles (the desktop's layer classification): what the rigger thinks each layer is - read from its name - and a
 * way to correct it. A layer's role decides how it is rigged (an eyebrow lifts, front hair sways, a mouth opens), so a
 * wrong guess means wrong motion; setting it right re-rigs with the right motion.
 */
@Composable
fun LayerRolesScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	var tick by remember { mutableStateOf(0) }
	val layers = remember(tick) { RigLayers.list(editing) }
	var chosenId by remember { mutableStateOf<String?>(null) }
	val chosen = layers.firstOrNull { it.id == chosenId }
	var tag by remember(chosenId, tick) { mutableStateOf(chosen?.tag) }
	var side by remember(chosenId, tick) { mutableStateOf(chosen?.side) }
	var type by remember(chosenId, tick) { mutableStateOf(chosen?.type) }
	var parameter by remember(chosenId, tick) { mutableStateOf(chosen?.parameter.orEmpty()) }
	var switchId by remember(chosenId, tick) { mutableStateOf(chosen?.switchId ?: 0) }
	var character by remember { mutableStateOf<ImageBitmap?>(null) }
	var highlight by remember { mutableStateOf<ImageBitmap?>(null) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	val source = editing.source
	val cw = outcome.source.widthPx.toFloat(); val ch = outcome.source.heightPx.toFloat()
	val sourceLayers = remember(tick) { source?.layers?.sortedBy { it.order }.orEmpty() }

	LaunchedEffect(tick) {
		character = withContext(worker) { editing.frame(emptyMap(), 0f, 0f, cw, ch, 1000, (1000 * ch / cw).roundToInt().coerceAtLeast(1)).toImageBitmap() }
	}
	LaunchedEffect(chosenId) {
		highlight = sourceLayers.firstOrNull { it.id.raw == chosenId }?.let { l -> withContext(worker) { layerPicture(l, 1000) } }
	}
	val changed = chosen != null && (tag != chosen.tag || side != chosen.side || type != chosen.type || parameter != chosen.parameter || switchId != chosen.switchId)

	fun step(label: String, block: () -> Unit) {
		if (busy != null) return
		busy = label to 0.3; message = null
		scope.launch { try { withContext(worker) { block() }; tick++ } catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { busy = null } }
	}

	val undoRedo = UndoRedo(canUndo = editing.canUndo && busy == null, canRedo = editing.canRedo && busy == null,
		undo = { editing.undo(); tick++ }, redo = { editing.redo(); tick++ })
	Column(Modifier.undoTaps(undoRedo).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Layer roles of ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		HowTo("What this is for", listOf(
			"The rigger guesses what each layer is from its name, and rigs it for that role: eyebrows lift, front hair sways, the mouth opens.",
			"Tap a layer (on her or in the list). If its role is wrong - a fringe rigged as an accessory, a blush rigged as the face - pick the right one.",
			"Apply re-rigs with it (about 10-20 seconds). Hand tweaks on that layer may need redoing, since its motion changes.",
			"Done keeps the changes."))
		Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(800.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				val scale = minOf(w / cw, h / ch); val left = (w - cw * scale) / 2f; val y0 = (h - ch * scale) / 2f
				Canvas(Modifier.fillMaxSize().pointerInput(sourceLayers, w, h) {
					detectTapGestures { o -> layerAt(sourceLayers, (o.x - left) / scale, (o.y - y0) / scale)?.let { chosenId = it.id.raw } }
				}) {
					character?.let { drawImage(it, dstOffset = IntOffset(left.roundToInt(), y0.roundToInt()),
						dstSize = IntSize((cw * scale).roundToInt(), (ch * scale).roundToInt()), alpha = if (chosenId != null) 0.3f else 1f) }
					val l = sourceLayers.firstOrNull { it.id.raw == chosenId }; val img = highlight
					if (l != null && img != null) {
						val b = l.bounds
						val x = left + b.left * scale; val y = y0 + b.top * scale; val bw = b.width * scale; val bh = b.height * scale
						drawImage(img, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(bw.roundToInt().coerceAtLeast(1), bh.roundToInt().coerceAtLeast(1)))
						drawRect(Accent, Offset(x - 3f, y - 3f), Size(bw + 6f, bh + 6f), style = Stroke(2f))
					}
				}
				if (character == null) Text("Drawing…", color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
			}
			Column(Modifier.width(400.dp).height(800.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				val b = busy
				if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
				if (chosen != null) {
					Text(chosen.name, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					Text("Rigged as: ${RigLayers.roleName(chosen.tag)}${sideWord(chosen.side)}" +
						if (chosen.chosen) "  (set by hand)" else "  (guessed from the name, ${(chosen.confidence * 100).roundToInt()}% sure)",
						color = if (!chosen.chosen && chosen.confidence < 0.6f) Warn else Muted, fontSize = 12.sp)
					for ((group, roles) in RigLayers.roles) {
						Text(group, color = Muted, fontSize = 12.sp)
						@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
						androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
							for ((t, name) in roles) Chip(name, tag == t) { tag = t }
						}
					}
					Text("Side", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						for ((s, name) in listOf(Side.NONE to "Middle / both", Side.LEFT to "Left", Side.RIGHT to "Right")) Chip(name, side == s) { side = s }
					}
					Text("Shows", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip("Always", type == LayerType.PRESET) { type = LayerType.PRESET }
						Chip("When a slider is on", type == LayerType.TOGGLE) { type = LayerType.TOGGLE }
						Chip("One of a set", type == LayerType.SWITCH) { type = LayerType.SWITCH }
					}
					if (type != LayerType.PRESET) {
						Text(if (type == LayerType.TOGGLE) "The parameter that turns it on (e.g. ParamGlasses)" else "The parameter that picks from the set, and this layer's number",
							color = Muted, fontSize = 12.sp)
						BasicTextField(parameter, { parameter = it.trim() }, singleLine = true, textStyle = TextStyle(color = Text1, fontSize = 14.sp), cursorBrush = SolidColor(Accent),
							modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B283C)).padding(10.dp))
						if (type == LayerType.SWITCH) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
							Chip("−", false, enabled = switchId > 0) { switchId-- }
							Text("Number $switchId", color = Text1, fontSize = 14.sp)
							Chip("+", false) { switchId++ }
						}
					}
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						val ready = changed && busy == null && (type == LayerType.PRESET || parameter.isNotBlank())
						Chip("Apply - re-rig with this", true, enabled = ready) {
							val t = tag!!; val s = side!!; val k = type!!; val p = if (k == LayerType.PRESET) "" else parameter; val n = switchId; val id = chosen.id
							step("Re-rigging with ${chosen.name} as ${RigLayers.roleName(t)}…") { RigLayers.classify(editing, id, t, s, k, p, n) }
						}
						Chip("Back to its name's guess", false, enabled = chosen.chosen && busy == null) { val id = chosen.id; step("Re-rigging…") { RigLayers.automatic(editing, id) } }
					}
				} else Text("Tap a layer on her or below.", color = Muted, fontSize = 13.sp)
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
				Text("Layers", color = Muted, fontSize = 12.sp)
				for (l in layers) {
					val on = l.id == chosenId
					Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF4A3F78) else Color(0xFF24222F))
						.clickable { chosenId = l.id }.padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
						Text(l.name, color = if (on) Accent else Text1, fontSize = 14.sp)
						Text(RigLayers.roleName(l.tag) + sideWord(l.side) + if (l.chosen) " ✎" else "",
							color = if (!l.chosen && l.confidence < 0.6f) Warn else Muted, fontSize = 12.sp)
					}
				}
			}
		}
	}
}

private fun sideWord(s: Side) = when (s) { Side.LEFT -> ", left"; Side.RIGHT -> ", right"; Side.NONE -> "" }

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
