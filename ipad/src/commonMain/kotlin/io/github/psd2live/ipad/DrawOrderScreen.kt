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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)
private val Pick = Color(0xFFFFC46B)

/**
 * Draw order (the desktop's draw-order ruler): which part is in front. Tap a part on the picture or in the list, then
 * bring it forward or back a step, or set its order exactly (0-1000, higher in front). A set order wins over any
 * animated order until you reset it. Undo/redo; Done writes the rig's files.
 */
@Composable
fun DrawOrderScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	var list by remember { mutableStateOf(RigDrawOrder.list(editing)) }
	var selected by remember { mutableStateOf<String?>(null) }
	var exact by remember { mutableStateOf(500f) }
	var bounds by remember { mutableStateOf<FloatArray?>(null) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var tick by remember { mutableStateOf(0) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val cw = editing.puppet.canvasWidth; val ch = editing.puppet.canvasHeight

	fun pick(layerId: String?) {
		selected = layerId; exact = list.firstOrNull { it.layerId == layerId }?.order ?: 500f
		scope.launch { bounds = withContext(Dispatchers.Default) { layerId?.let { id -> RigParts.list(editing).firstOrNull { it.layerId == id }?.let { RigParts.bounds(editing, it) } } } }
	}
	fun edit(label: String, block: () -> Unit) {
		if (busy != null) return
		busy = label to 0.3; message = null
		scope.launch {
			try { withContext(Dispatchers.Default) { block() }; list = RigDrawOrder.list(editing); tick++; exact = list.firstOrNull { it.layerId == selected }?.order ?: exact }
			catch (e: Throwable) { message = e.message ?: e::class.simpleName }
			finally { busy = null }
		}
	}
	LaunchedEffect(editing) {
		snapshotFlow { tick }.collectLatest {
			image = withContext(Dispatchers.Default) { val s = 1100f / maxOf(cw, ch); editing.frame(emptyMap(), 0f, 0f, cw, ch, (cw * s).roundToInt(), (ch * s).roundToInt()).toImageBitmap() }
		}
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Draw order for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		Text("Which part is in front. Tap a part, then bring it forward or back. A set order wins over any animated order until you reset it.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(860.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				val scale = minOf(w / cw, h / ch); val left = (w - cw * scale) / 2f; val top = (h - ch * scale) / 2f
				Canvas(Modifier.fillMaxSize().pointerInput(editing, w, h) {
					detectTapGestures { at ->
						val x = (at.x - left) / scale; val y = (at.y - top) / scale
						scope.launch { val hit = withContext(Dispatchers.Default) { RigParts.at(editing, x, y) }; pick(hit?.layerId) }
					}
				}) {
					image?.let { drawImage(it, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize((cw * scale).roundToInt(), (ch * scale).roundToInt())) }
					bounds?.let { b -> drawRect(Pick, Offset(left + b[0] * scale, top + b[1] * scale), Size((b[2] - b[0]) * scale, (b[3] - b[1]) * scale), style = Stroke(2.5f)) }
				}
			}
			Column(Modifier.width(400.dp).height(860.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = editing.canUndo && busy == null) { editing.undo(); list = RigDrawOrder.list(editing); tick++ }
					Chip("Redo", false, enabled = editing.canRedo && busy == null) { editing.redo(); list = RigDrawOrder.list(editing); tick++ }
				}
				val b = busy
				if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				else Chip("Done - keep the changes", true, enabled = editing.edits > 0) {
					busy = "Writing the runtime files" to 0.2
					scope.launch {
						try { val o = withContext(Dispatchers.Default) { editing.finish(outcome) { s, f -> scope.launch(Dispatchers.Main) { busy = s to f } } }; busy = null; onDone(o) }
						catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
					}
				}
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
				val sel = list.firstOrNull { it.layerId == selected }
				if (sel != null) {
					Text(sel.name, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					Text("Order ${fmtOrder(sel.order)}${if (sel.overridden) " (set by you)" else ""}", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip("Forward a step", false, enabled = busy == null && list.firstOrNull()?.layerId != sel.layerId) { edit("Bringing it forward") { RigDrawOrder.step(editing, list, sel.layerId, true) } }
						Chip("Back a step", false, enabled = busy == null && list.lastOrNull()?.layerId != sel.layerId) { edit("Sending it back") { RigDrawOrder.step(editing, list, sel.layerId, false) } }
					}
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip("To the front", false, enabled = busy == null) { edit("Bringing it to the front") { RigDrawOrder.set(editing, sel.layerId, minOf(1000f, (list.first().order + 1f))) } }
						Chip("To the back", false, enabled = busy == null) { edit("Sending it to the back") { RigDrawOrder.set(editing, sel.layerId, maxOf(0f, list.last().order - 1f)) } }
					}
					Text("Exactly  ${fmtOrder(exact)}", color = Muted, fontSize = 12.sp)
					Slider(exact, { exact = (it * 2).roundToInt() / 2f }, valueRange = 0f..1000f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent), modifier = Modifier.height(28.dp))
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip("Set this order", true, enabled = busy == null && exact != sel.order) { val v = exact; edit("Setting the order") { RigDrawOrder.set(editing, sel.layerId, v) } }
						if (sel.overridden) Chip("Reset", false, enabled = busy == null) { edit("Resetting") { RigDrawOrder.set(editing, sel.layerId, null) } }
					}
				} else Text("Tap a part on the picture, or pick one below.", color = Muted, fontSize = 13.sp)
				Text("Front to back", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
				for (en in list) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (en.layerId == selected) Color(0xFF3A3358) else Color(0xFF24222F))
					.clickable { pick(en.layerId) }.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
					Text(en.name, color = Text1, fontSize = 14.sp, modifier = Modifier.weight(1f))
					Text(fmtOrder(en.order) + if (en.overridden) " ✎" else "", color = if (en.overridden) Accent else Muted, fontSize = 12.sp)
				}
			}
		}
	}
}

private fun fmtOrder(v: Float) = ((v * 10).roundToInt() / 10.0).toString().removeSuffix(".0")

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF5B4B8A) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
		Text(label, color = if (enabled) Text1 else Muted, fontSize = 13.sp)
	}
}
