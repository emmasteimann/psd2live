package io.github.psd2live.ipad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

private enum class PaintTool(val label: String) { BRUSH("Brush"), ERASER("Eraser"), FILL("Fill") }

private val Swatches = listOf(
	0xFF000000, 0xFFFFFFFF, 0xFF6B6B7A, 0xFFF6D7C3, 0xFFE8B49A, 0xFF8A5A44, 0xFFE0453A, 0xFFF29A38,
	0xFFF5D547, 0xFF5FBF6A, 0xFF4A90D9, 0xFF6A5ACD, 0xFFB57EDC, 0xFFF4A6C8, 0xFF2E2440, 0xFFC9B7FF,
).map { it.toInt() }

/**
 * Painting a rigged PSD's layers on the iPad: pick a layer, paint it with the desktop's engine (brush, eraser, bucket,
 * undo), then apply - the rig is rebuilt with the painted pixels and keeps its meshes.
 */
@Composable
fun PaintScreen(outcome: RigOutcome, onApplied: (RigOutcome) -> Unit, onClose: () -> Unit) {
	var painting by remember(outcome) { mutableStateOf<LayerPainting?>(null) }
	val layers = remember(outcome) { paintableLayers(outcome) }
	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		val current = painting
		if (current == null) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
				Text("Paint ${outcome.name}: choose a layer", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
				Text("Back to the rig", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onClose))
			}
			for (layer in layers) {
				Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF24222F))
					.clickable { painting = LayerPainting(outcome, layer) }.padding(horizontal = 14.dp, vertical = 10.dp),
					horizontalArrangement = Arrangement.SpaceBetween) {
					Text(layer.name, color = Text1, fontSize = 15.sp)
					Text(layer.groupPath, color = Muted, fontSize = 13.sp)
				}
			}
		} else {
			LayerPaintView(current, onApplied, onBack = { painting = null })
		}
	}
}

@Composable
private fun LayerPaintView(painting: LayerPainting, onApplied: (RigOutcome) -> Unit, onBack: () -> Unit) {
	var tool by remember { mutableStateOf(PaintTool.BRUSH) }
	var radius by remember { mutableStateOf(12f) }
	var hardness by remember { mutableStateOf(0.7f) }
	var opacity by remember { mutableStateOf(1f) }
	var color by remember { mutableStateOf(Swatches[0]) }
	var ghost by remember { mutableStateOf(true) }
	var tick by remember { mutableStateOf(0L) }
	var layerImage by remember { mutableStateOf<ImageBitmap?>(null) }
	var ghostImage by remember { mutableStateOf<ImageBitmap?>(null) }
	var applying by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val current by rememberUpdatedState(PaintSettings(tool, radius, hardness, opacity, color))

	LaunchedEffect(painting) {
		ghostImage = withContext(Dispatchers.Default) {
			painting.outcome.preview.render(painting.outcome.preview.parameters.associate { it.id to it.default }, 1400).toImageBitmap()
		}
	}
	LaunchedEffect(painting) {
		// Redraws run off the main thread, one at a time; while one runs only the newest change waits.
		snapshotFlow { tick }.conflate().collect { layerImage = withContext(Dispatchers.Default) { painting.image.toImageBitmap(1600) } }
	}

	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		Text("Painting ${painting.layer.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
		Text(if (painting.isDirty) "Choose another layer (drops this layer's paint)" else "Choose another layer", color = Accent, fontSize = 15.sp,
			modifier = Modifier.clickable { if (applying == null) onBack() })
	}
	Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
		BoxWithConstraints(Modifier.weight(1f).height(760.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
			val density = LocalDensity.current
			val boxW = with(density) { maxWidth.toPx() }; val boxH = with(density) { maxHeight.toPx() }
			// The canvas fitted into the box: canvas units to screen pixels.
			val scale = minOf(boxW / painting.canvasWidth, boxH / painting.canvasHeight)
			val left = (boxW - painting.canvasWidth * scale) / 2f; val top = (boxH - painting.canvasHeight * scale) / 2f
			fun toCanvas(o: Offset) = Offset((o.x - left) / scale, (o.y - top) / scale)
			Canvas(Modifier.fillMaxSize().pointerInput(painting, scale, left, top) {
				awaitEachGesture {
					val down = awaitFirstDown()
					if (applying != null) return@awaitEachGesture
					val s = current
					var last = toCanvas(down.position)
					down.consume()
					if (s.tool == PaintTool.FILL) {
						painting.fill(last.x, last.y, s.color, s.opacity, 24); tick++
						return@awaitEachGesture
					}
					painting.beginStroke()
					painting.segment(last.x, last.y, last.x, last.y, s.radius, s.hardness, s.color, s.opacity, s.tool == PaintTool.ERASER); tick++
					while (true) {
						val event = awaitPointerEvent()
						val change = event.changes.firstOrNull { it.id == down.id } ?: break
						if (!change.pressed) { change.consume(); break }
						val next = toCanvas(change.position)
						if (next != last) {
							painting.segment(last.x, last.y, next.x, next.y, s.radius, s.hardness, s.color, s.opacity, s.tool == PaintTool.ERASER); tick++
							last = next
						}
						change.consume()
					}
					painting.endStroke(s.tool.label); tick++
				}
			}) {
				val cw = painting.canvasWidth * scale; val ch = painting.canvasHeight * scale
				// A checkerboard under the canvas, so transparent pixels read as transparent.
				val cell = 24f
				var y = 0f; var row = 0
				while (y < ch) {
					var x = 0f; var col = 0
					while (x < cw) {
						drawRect(if ((row + col) % 2 == 0) Color(0xFF2A2A33) else Color(0xFF34343F), Offset(left + x, top + y),
							Size(minOf(cell, cw - x), minOf(cell, ch - y)))
						x += cell; col++
					}
					y += cell; row++
				}
				if (ghost) ghostImage?.let {
					drawImage(it, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(cw.roundToInt(), ch.roundToInt()), alpha = 0.3f)
				}
				layerImage?.let {
					val sp = painting.space
					drawImage(it, dstOffset = IntOffset((left + sp.originX * scale).roundToInt(), (top + sp.originY * scale).roundToInt()),
						dstSize = IntSize((sp.canvasWidth * scale).roundToInt(), (sp.canvasHeight * scale).roundToInt()))
				}
			}
		}
		Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			// Read on every change, so undo, redo and apply follow the session.
			@Suppress("UNUSED_VARIABLE") val changes = tick
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				for (t in PaintTool.entries) Chip(t.label, tool == t) { tool = t }
			}
			if (tool != PaintTool.FILL) {
				Text("Size  ${radius.roundToInt() * 2} px", color = Muted, fontSize = 12.sp)
				Slider(radius, { radius = it }, valueRange = 0.5f..150f, colors = sliderColors(), modifier = Modifier.height(32.dp))
				Text("Hardness  ${(hardness * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
				Slider(hardness, { hardness = it }, valueRange = 0f..1f, colors = sliderColors(), modifier = Modifier.height(32.dp))
			}
			Text("Opacity  ${(opacity * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
			Slider(opacity, { opacity = it }, valueRange = 0.02f..1f, colors = sliderColors(), modifier = Modifier.height(32.dp))
			if (tool != PaintTool.ERASER) {
				Text("Colour", color = Muted, fontSize = 12.sp)
				for (chunk in Swatches.chunked(8)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					for (swatch in chunk) Box(Modifier.size(30.dp).clip(CircleShape).background(Color(swatch))
						.border(if (swatch == color) 3.dp else 1.dp, if (swatch == color) Accent else Color(0xFF55526A), CircleShape)
						.clickable { color = swatch })
				}
			}
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Chip("Undo", false, enabled = painting.canUndo) { painting.undo(); tick++ }
				Chip("Redo", false, enabled = painting.canRedo) { painting.redo(); tick++ }
				Chip(if (ghost) "Hide the rest" else "Show the rest", false) { ghost = !ghost }
			}
			Spacer(Modifier.height(8.dp))
			val progress = applying
			if (progress != null) {
				Text(progress.first, color = Muted, fontSize = 13.sp)
				LinearProgressIndicator(progress = progress.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent)
			} else {
				Chip("Apply to the rig", true, enabled = painting.isDirty) {
					applying = "Starting" to 0.0; failure = null
					scope.launch {
						try {
							val updated = withContext(Dispatchers.Default) {
								painting.apply { stage, fraction -> scope.launch(Dispatchers.Main) { if (applying != null) applying = stage to fraction } }
							}
							applying = null
							onApplied(updated)
						} catch (error: Throwable) {
							applying = null; failure = error.message ?: error::class.simpleName
						}
					}
				}
				Text("The rig is rebuilt with this layer's new pixels; its meshes and deformers stay as they are.", color = Muted, fontSize = 12.sp)
			}
			failure?.let { Text("Couldn't apply: $it", color = Danger, fontSize = 13.sp) }
		}
	}
}

private class PaintSettings(val tool: PaintTool, val radius: Float, val hardness: Float, val opacity: Float, val color: Int)

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
