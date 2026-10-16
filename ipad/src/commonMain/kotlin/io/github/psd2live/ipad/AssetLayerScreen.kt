package io.github.psd2live.ipad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/**
 * An image as a new layer (the desktop's "add layer from asset"): pick a PNG or JPEG, drag it into place over the rig
 * and size it, choose which layer it sits above - it then moves with that layer - and add it. It gets its own mesh;
 * rig it further like any layer.
 */
@Composable
fun AssetLayerScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	val canvasW = outcome.source.widthPx.toFloat(); val canvasH = outcome.source.heightPx.toFloat()
	var tick by remember { mutableStateOf(0) }
	var image by remember { mutableStateOf<io.github.psd2live.ipad.studio.Pixels?>(null) }
	var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
	var placement by remember { mutableStateOf(RigAssetLayers.Placement(0f, 0f, 1f)) }
	var name by remember { mutableStateOf("Image") }
	var above by remember { mutableStateOf<String?>(null) }
	var followNothing by remember { mutableStateOf(false) }
	var rig by remember { mutableStateOf<ImageBitmap?>(null) }
	var view by remember { mutableStateOf(CanvasView()) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	val layers = remember(tick) { editing.source?.layers?.sortedBy { it.order }.orEmpty() /* top of the stack first */ }
	val rest = remember(outcome) { outcome.preview.parameters.associate { it.id to it.default } }
	fun setAssetView(v: CanvasView) { view = v }

	LaunchedEffect(tick) {
		rig = withContext(worker) { editing.frame(rest, 0f, 0f, canvasW, canvasH, 1400, (1400 * canvasH / canvasW).roundToInt().coerceAtLeast(1)).toImageBitmap() }
	}

	fun pick() {
		Exchange.pick(setOf("png", "jpg", "jpeg", "webp"), multiple = false) { names ->
			val file = names.firstOrNull() ?: return@pick
			scope.launch {
				val px = withContext(worker) { runCatching { io.github.psd2live.ipad.studio.decodeImage(RigFiles.read(file)) }.getOrNull() }
				if (px == null) { message = "Couldn't read $file as an image"; return@launch }
				image = px; name = file.substringBeforeLast('.')
				imageBitmap = withContext(worker) { RasterImageOf(px).toImageBitmap() }
				// Start it a quarter of the canvas wide, in the middle.
				val scale = (canvasW * 0.25f / px.width).coerceAtLeast(0.01f)
				placement = RigAssetLayers.Placement((canvasW - px.width * scale) / 2f, (canvasH - px.height * scale) / 2f, scale)
			}
		}
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Add an image as a layer of ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		Text("Pick an image, drag it into place with one finger (two fingers move and zoom the view), size it, and choose what it sits above.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(820.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				Canvas(Modifier.fillMaxSize().canvasTouch(listOf(w, h, image != null)) {
					object : CanvasTouch {
						private var last: Offset? = null
						private fun toCanvas(o: Offset): Offset { val l = view.layout(w, h, canvasW, canvasH); return Offset((o.x - l[1]) / l[0], (o.y - l[2]) / l[0]) }
						override fun start(at: Offset, pressure: Float) { if (image != null && busy == null) last = toCanvas(at) }
						override fun move(at: Offset, pressure: Float) {
							val from = last ?: return; val to = toCanvas(at); last = to
							placement = placement.copy(left = placement.left + to.x - from.x, top = placement.top + to.y - from.y)
						}
						override fun end() { last = null }
						override fun cancel() { last = null }
						override fun undo() {}
						override fun redo() {}
						override fun view() = view
						override fun setView(view: CanvasView) { setAssetView(view) }
						override fun layout(view: CanvasView) = view.layout(w, h, canvasW, canvasH)
					}
				}) {
					val l = view.layout(w, h, canvasW, canvasH)
					rig?.let { drawImage(it, dstOffset = IntOffset(l[1].roundToInt(), l[2].roundToInt()), dstSize = IntSize((canvasW * l[0]).roundToInt(), (canvasH * l[0]).roundToInt())) }
					val px = image; val bmp = imageBitmap
					if (px != null && bmp != null) {
						val x = placement.left * l[0] + l[1]; val y = placement.top * l[0] + l[2]
						val dw = px.width * placement.scale * l[0]; val dh = px.height * placement.scale * l[0]
						drawImage(bmp, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)))
						drawRect(Accent, Offset(x, y), Size(dw, dh), style = Stroke(2f))
					}
				}
				if (rig == null) Text("Drawing…", color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
			}
			Column(Modifier.width(360.dp).height(820.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Chip(if (image == null) "Choose an image" else "Choose another image", image == null, enabled = busy == null) { pick() }
				val px = image
				if (px != null) {
					Text("${px.width} x ${px.height} px", color = Muted, fontSize = 12.sp)
					Text("Name", color = Muted, fontSize = 12.sp)
					BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = Text1, fontSize = 15.sp), cursorBrush = SolidColor(Accent),
						modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B283C)).padding(10.dp))
					val widthNow = px.width * placement.scale
					Text("Size  ${widthNow.roundToInt()} px wide on the canvas", color = Muted, fontSize = 12.sp)
					Slider(widthNow, { v ->
						// Keep the middle where it is while the size changes.
						val cx = placement.left + px.width * placement.scale / 2f; val cy = placement.top + px.height * placement.scale / 2f
						val s = v / px.width
						placement = RigAssetLayers.Placement(cx - px.width * s / 2f, cy - px.height * s / 2f, s)
					}, valueRange = 8f..canvasW, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent), modifier = Modifier.height(32.dp))
					val b = busy
					if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
					else Chip("Add as a layer", true) {
						busy = "Adding the layer" to 0.3; message = null
						scope.launch {
							try {
								withContext(worker) { RigAssetLayers.add(editing, px.argb, px.width, px.height, name, placement, above, followNothing) }
								image = null; imageBitmap = null; tick++
							} catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { busy = null }
						}
					}
					Text("Sits above", color = Muted, fontSize = 12.sp)
					Chip("Everything (on top)", above == null) { above = null }
					for (layer in layers.take(60)) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (above == layer.id.raw) Color(0xFF332C4E) else Color(0xFF24222F))
						.clickable { above = layer.id.raw }.padding(horizontal = 12.dp, vertical = 8.dp)) {
						Text(layer.name, color = if (above == layer.id.raw) Accent else Text1, fontSize = 14.sp)
					}
					Chip(if (followNothing) "Moves with nothing" else "Moves with the layer under it", false) { followNothing = !followNothing }
					Text("Moving with the layer under it: a bow above the hair turns with the head.", color = Muted, fontSize = 12.sp)
				}
				Box(Modifier.height(8.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = editing.canUndo && busy == null) { editing.undo(); tick++ }
					Chip("Redo", false, enabled = editing.canRedo && busy == null) { editing.redo(); tick++ }
					Chip("Fit view", false) { view = CanvasView() }
				}
				if (busy == null) Chip("Done - keep the changes", true, enabled = editing.canUndo) {
					busy = "Writing the runtime files" to 0.1
					scope.launch {
						try { val o = withContext(worker) { editing.finish(outcome) { s, f -> scope.launch(Dispatchers.Main) { busy = s to f } } }; busy = null; onDone(o) }
						catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
					}
				}
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
			}
		}
	}
}

/** [px] as the rig's raster image type, for drawing. */
private fun RasterImageOf(px: io.github.psd2live.ipad.studio.Pixels) = io.github.psd2live.format.compile.RasterImage(px.width, px.height, px.argb)

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
