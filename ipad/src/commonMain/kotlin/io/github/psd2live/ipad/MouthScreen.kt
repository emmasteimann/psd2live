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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize


private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)
private val Warn = Color(0xFFFFC46B)
private val Handle = Color(0xFFFFC46B)

/**
 * Mouth settings (the desktop's mouth popup): the shape of the closed mouth's lip line - flat, smile, W, or your own
 * curve (drag its points with the Pencil) - and the line's thickness and colour. The lip line is drawn into the
 * texture, so applying rebuilds the rig. Undo; Done writes it.
 */
@Composable
fun MouthScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	val start = editing.model.config
	var shape by remember { mutableStateOf(start.mouthShape) }
	var curve by remember { mutableStateOf(start.mouthCurve) }
	var thickness by remember { mutableStateOf(start.mouthThickness) }
	var color by remember { mutableStateOf(start.mouthColor) }
	var picking by remember { mutableStateOf(false) }
	var selected by remember { mutableStateOf(3) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var tick by remember { mutableStateOf(0) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val cfg = editing.model.config
	// Her own closed-mouth art, when it clearly draws a line, is the mouth at rest: the line this screen shapes then fades
	// in only as she talks (its opacity follows Mouth Open). Such a rig previews talking, or nothing here would show.
	val paintedClosed = remember(outcome) {
		editing.puppet.drawables.any { d -> d.name.lowercase().startsWith("mouth") && !d.name.lowercase().contains("close") &&
			d.channelGrids.gridsByChannel[org.umamo.runtime.model.FormChannel.OPACITY]?.axes?.any { it.parameterId.raw == "ParamMouthOpenY" } == true }
	}
	var talking by remember(outcome) { mutableStateOf(paintedClosed) }
	val changed = shape != cfg.mouthShape || curve != cfg.mouthCurve || thickness != cfg.mouthThickness || color != cfg.mouthColor

	// The face up close: the mouth's box, padded.
	val mouthBox = remember(tick) {
		val ids = editing.puppet.drawables.filter { it.name.lowercase().startsWith("mouth") }.map { it.id.raw }.toSet()
		var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
		for ((id, pts) in io.github.psd2live.core.PoseTweaks.vertices(editing.puppet, emptyMap())) if (id in ids)
			for (i in 0 until pts.size / 2) { x0 = minOf(x0, pts[i * 2]); x1 = maxOf(x1, pts[i * 2]); y0 = minOf(y0, pts[i * 2 + 1]); y1 = maxOf(y1, pts[i * 2 + 1]) }
		if (x1 < x0) null else { val pad = maxOf(x1 - x0, y1 - y0) * 1.5f; floatArrayOf(x0 - pad, y0 - pad, x1 + pad, y1 + pad) }
	}
	LaunchedEffect(tick, talking) {
		image = withContext(Dispatchers.Default) {
			val b = mouthBox ?: floatArrayOf(0f, 0f, editing.puppet.canvasWidth, editing.puppet.canvasHeight)
			val w = b[2] - b[0]; val h = b[3] - b[1]; val k = 700f / maxOf(w, h)
			editing.frame(if (talking) mapOf("ParamMouthOpenY" to 0.35f) else emptyMap(), b[0], b[1], w, h, (w * k).roundToInt().coerceAtLeast(1), (h * k).roundToInt().coerceAtLeast(1)).toImageBitmap()
		}
	}
	fun apply() {
		if (busy != null) return
		val next = cfg.copy(mouthShape = shape, mouthCurve = curve, mouthThickness = thickness, mouthColor = color)
		busy = "Redrawing the mouth…" to 0.3; message = null
		scope.launch {
			try { withContext(Dispatchers.Default) { editing.rebuild(next) }; tick++ } catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { busy = null }
		}
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Mouth of ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		Text("The closed mouth's lip line. Pick a shape or drag the curve's points, then Apply to see it on her.", color = Muted, fontSize = 13.sp)
		if (paintedClosed) Text("Her art has its own closed mouth, so that painted line is what shows at rest (it smiles and frowns with her). " +
			"The line shaped here shows while she talks - the preview starts on Talking for that reason.", color = Warn, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Chip("Preview: closed", !talking) { talking = false }
			Chip("Preview: talking", talking) { talking = true }
		}
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Box(Modifier.weight(1f).height(760.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017)), contentAlignment = Alignment.Center) {
				image?.let { Image(it, contentDescription = "Her mouth", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) } ?: Text("Drawing…", color = Muted)
			}
			Column(Modifier.width(420.dp).height(860.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = editing.canUndo && busy == null) { editing.undo(); val c = editing.model.config; shape = c.mouthShape; curve = c.mouthCurve; thickness = c.mouthThickness; color = c.mouthColor; tick++ }
					Chip("Apply", true, enabled = changed && busy == null) { apply() }
				}
				val b = busy
				if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				else Chip("Done - keep the mouth", true, enabled = editing.canUndo && !changed) {
					busy = "Writing the runtime files" to 0.2
					scope.launch {
						try { val o = withContext(Dispatchers.Default) { editing.finish(outcome) { s, f -> scope.launch(Dispatchers.Main) { busy = s to f } } }; busy = null; onDone(o) }
						catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
					}
				}
				if (changed) Text("Apply first to see the change on her.", color = Muted, fontSize = 12.sp)
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
				Text("Shape", color = Muted, fontSize = 12.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					for ((id, label) in listOf("flat" to "Flat", "smile" to "Smile", "w" to "W (cat)")) Chip(label, shape == id) { shape = id; curve = io.github.psd2live.core.MouthCurve.preset(id) }
					Chip("Custom", shape == "custom") { shape = "custom" }
				}
				// The curve: 7 points (anchors 1, 4, 7; handles between). x 0..1 across the mouth, y -0.5..0.5.
				BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF24222F))) {
					val density = LocalDensity.current
					val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
					val pad = 24f
					fun toScreen(x: Float, y: Float) = Offset(pad + x * (w - 2 * pad), h / 2 + y * (h - 2 * pad))
					fun fromScreen(o: Offset) = ((o.x - pad) / (w - 2 * pad)) to ((o.y - h / 2) / (h - 2 * pad))
					Canvas(Modifier.fillMaxSize().pointerInput(w, h) {
						detectDragGestures(onDragStart = { at ->
							selected = curve.points.indices.minByOrNull { i -> val p = toScreen(curve.points[i].x, curve.points[i].y); (p - at).getDistance() } ?: selected
						}) { change, _ ->
							val (x, y) = fromScreen(change.position)
							curve = curve.move(selected, x.coerceIn(0f, 1f), y.coerceIn(-0.5f, 0.5f)); shape = "custom"
						}
					}) {
						drawLine(Color.White.copy(alpha = 0.1f), Offset(pad, h / 2), Offset(w - pad, h / 2), 1f)
						val pts = curve.points.map { toScreen(it.x, it.y) }
						val path = androidx.compose.ui.graphics.Path().apply {
							moveTo(pts[0].x, pts[0].y)
							cubicTo(pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y)
							cubicTo(pts[4].x, pts[4].y, pts[5].x, pts[5].y, pts[6].x, pts[6].y)
						}
						drawPath(path, Color(color ?: 0xFF5A2A2A.toInt()).copy(alpha = 1f), style = Stroke(3f + thickness * 2f))
						drawLine(Handle.copy(alpha = 0.5f), pts[0], pts[1], 1f); drawLine(Handle.copy(alpha = 0.5f), pts[2], pts[3], 1f)
						drawLine(Handle.copy(alpha = 0.5f), pts[3], pts[4], 1f); drawLine(Handle.copy(alpha = 0.5f), pts[5], pts[6], 1f)
						pts.forEachIndexed { i, p -> drawCircle(if (i == selected) Handle else if (i % 3 == 0) Text1 else Muted, if (i % 3 == 0) 9f else 7f, p) }
					}
				}
				Text("Drag a point. The ends stay at the mouth's corners; the middle anchor and the handles shape it.", color = Muted, fontSize = 11.sp)
				Text("Line thickness  ${(thickness * 10).roundToInt() / 10.0}", color = Muted, fontSize = 12.sp)
				Slider(thickness, { thickness = (it * 10).roundToInt() / 10f }, valueRange = 0.5f..6f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent), modifier = Modifier.height(28.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
					Text("Colour", color = Muted, fontSize = 12.sp)
					Chip("From the art", color == null) { color = null; picking = false }
					Chip(if (picking) "Close the picker" else "Pick a colour", color != null) { picking = !picking; if (color == null) color = 0xFF5A2A2A.toInt() }
				}
				if (picking) ColourPicker(color ?: 0xFF5A2A2A.toInt()) { color = it }
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
