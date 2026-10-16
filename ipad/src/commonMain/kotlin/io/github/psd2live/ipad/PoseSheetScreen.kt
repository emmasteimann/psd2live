package io.github.psd2live.ipad

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private val Warn = Color(0xFFFFC46B)

/**
 * The pose sheet (the desktop's AvatarPoseSheetTool, as a button): the rig at the poses that show whether it feels
 * alive - head turns, tilt, eyes, blink, mouth, body lean - the whole figure or the face up close, with how much each
 * pose moved from rest. Look only; Share saves the sheet and each pose as PNGs and opens the share sheet.
 */
@Composable
fun PoseSheetScreen(outcome: RigOutcome, onClose: () -> Unit) {
	var faceMode by remember { mutableStateOf(false) }
	var face by remember { mutableStateOf(RigPoseSheet.Face()) }
	val tiles = remember { mutableStateListOf<Pair<RigPoseSheet.Tile, ImageBitmap>?>() }
	var drawing by remember { mutableStateOf(false) }
	var sharing by remember { mutableStateOf(false) }
	var message by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val poses = RigPoseSheet.poses
	// Each redraw's number: a render still running from before (it blocks, so it can't be cancelled midway) stops at its
	// next tile instead of filling the new grid.
	var generation by remember { mutableStateOf(0) }

	LaunchedEffect(faceMode, face) {
		if (faceMode) delay(250) // the face sliders: draw once they rest
		val mine = ++generation
		drawing = true; message = null
		tiles.clear(); repeat(poses.size) { tiles.add(null) }
		try {
			withContext(Dispatchers.Default) {
				RigPoseSheet.render(outcome.preview, outcome.source.widthPx, outcome.source.heightPx, if (faceMode) face else null, 520) { i, t ->
					if (generation != mine) throw kotlinx.coroutines.CancellationException("redrawn")
					val bitmap = t.image.toImageBitmap()
					scope.launch(Dispatchers.Main) { if (generation == mine && i < tiles.size) tiles[i] = t to bitmap }
				}
			}
		} catch (e: kotlinx.coroutines.CancellationException) { throw e
		} catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { if (generation == mine) drawing = false }
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Pose sheet of ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back to the rig", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onClose))
		}
		Text("Does it feel alive or like a paper cut-out being bent? The rig at the poses that tell; under each, how much of the picture moved from rest.",
			color = Muted, fontSize = 13.sp)
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Chip("Whole figure", !faceMode) { faceMode = false }
			Chip("Face up close", faceMode) { faceMode = true }
			Box(Modifier.width(16.dp))
			if (sharing) Text("Saving…", color = Muted, fontSize = 13.sp)
			else Chip("Share the sheet", false, enabled = !drawing && tiles.all { it != null }) {
				sharing = true; message = null
				val done = tiles.mapNotNull { it?.first }
				scope.launch {
					try {
						val folder = withContext(Dispatchers.Default) { RigPoseSheet.write(outcome, done, faceMode) { w, h, argb -> io.github.psd2live.ipad.studio.encodePng(w, h, argb) } }
						Exchange.share(listOf(folder))
					} catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { sharing = false }
				}
			}
		}
		if (faceMode) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text("Face starts ${(face.top * 100).roundToInt()}% down", color = Muted, fontSize = 12.sp)
				Slider(face.top, { face = face.copy(top = it) }, valueRange = 0f..0.7f, colors = sliderColors(), modifier = Modifier.height(32.dp))
			}
			Column(Modifier.weight(1f)) {
				Text("Face height ${(face.height * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
				Slider(face.height, { face = face.copy(height = it) }, valueRange = 0.1f..0.7f, colors = sliderColors(), modifier = Modifier.height(32.dp))
			}
			Chip("Reset", false) { face = RigPoseSheet.Face() }
		}
		if (drawing) LinearProgressIndicator(progress = tiles.count { it != null }.toFloat() / poses.size, modifier = Modifier.fillMaxWidth(), color = Accent)
		message?.let { Text("Couldn't draw the sheet: $it", color = Danger, fontSize = 13.sp) }
		val aspect = if (faceMode) (outcome.source.widthPx * face.width) / (outcome.source.heightPx * face.height.coerceAtMost(1f - face.top))
			else outcome.source.widthPx.toFloat() / outcome.source.heightPx
		for (row in poses.indices.chunked(4)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			for (i in row) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				val t = tiles.getOrNull(i)
				Text(poses[i].label, color = Text1, fontSize = 13.sp, fontWeight = FontWeight.Bold)
				Box(Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.2f, 5f)).clip(RoundedCornerShape(8.dp)).background(Color(0xFFEEEAF4)), contentAlignment = Alignment.Center) {
					if (t != null) Image(t.second, poses[i].label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
				}
				val tile = t?.first
				when {
					tile == null -> Text(" ", fontSize = 12.sp)
					tile.missing.isNotEmpty() -> Text("Not in this rig: ${tile.missing.joinToString()}", color = Warn, fontSize = 12.sp)
					i == 0 -> Text("rest", color = Muted, fontSize = 12.sp)
					tile.changed < 0.002 -> Text("barely moves (${percent(tile.changed)})", color = Warn, fontSize = 12.sp)
					else -> Text("${percent(tile.changed)} of the picture moved", color = Muted, fontSize = 12.sp)
				}
			}
			repeat(4 - row.size) { Box(Modifier.weight(1f)) }
		}
	}
}

private fun percent(x: Double): String = ((x * 1000).roundToInt() / 10.0).toString() + "%"

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
