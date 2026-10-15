package io.github.psd2live.ipad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

private sealed interface RigState {
	data object Idle : RigState
	data class Rigging(val name: String, val stage: String, val fraction: Double) : RigState
	data class Done(val outcome: RigOutcome) : RigState
	data class Failed(val name: String, val message: String) : RigState
}

/**
 * The Rig workspace: the PSDs in the app's folder (Files > On My iPad > Layerloom), rigged on the iPad with the desktop's
 * pipeline; the moc3 runtime files go to the Rigs folder beside them, ready for VTube Studio or any Cubism runtime.
 */
@Composable
fun RigWorkspace(modifier: Modifier = Modifier) {
	var psds by remember { mutableStateOf(emptyList<String>()) }
	var state by remember { mutableStateOf<RigState>(RigState.Idle) }
	val scope = rememberCoroutineScope()
	fun refresh() { psds = runCatching { RigFiles.psds() }.getOrDefault(emptyList()) }
	LaunchedEffect(Unit) { refresh() }

	fun rig(name: String) {
		if (state is RigState.Rigging) return
		state = RigState.Rigging(name, "Starting", 0.0)
		scope.launch {
			state = try {
				val outcome = withContext(Dispatchers.Default) {
					rigPsd(name, RigFiles.read(name)) { stage, fraction ->
						scope.launch(Dispatchers.Main) { if (state is RigState.Rigging) state = RigState.Rigging(name, stage, fraction) }
					}
				}
				RigState.Done(outcome)
			} catch (failure: Throwable) {
				RigState.Failed(name, failure.message ?: failure::class.simpleName ?: "Rigging failed")
			}
		}
	}

	Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
		Text("Rig a PSD", color = Text1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
		Text("Put PSDs in Files > On My iPad > Layerloom. The rig is built here on the iPad, with the same rigger as the Mac " +
			"app; its moc3 runtime files go to the Rigs folder there.", color = Muted, fontSize = 14.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Button("Refresh the list") { refresh() }
		}
		if (psds.isEmpty()) Text("No PSDs in the Layerloom folder yet.", color = Muted, fontSize = 15.sp)
		for (name in psds) {
			Row(
				Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).clickable { rig(name) }.padding(16.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(name, color = Text1, fontSize = 16.sp)
				Text("Rig", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			}
		}
		when (val s = state) {
			RigState.Idle -> {}
			is RigState.Rigging -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp)) {
				Text("Rigging ${s.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
				Spacer(Modifier.height(8.dp))
				LinearProgressIndicator(progress = s.fraction.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent)
				Spacer(Modifier.height(6.dp))
				Text(s.stage, color = Muted, fontSize = 13.sp)
			}
			is RigState.Done -> RigResult(s.outcome)
			is RigState.Failed -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp)) {
				Text("Couldn't rig ${s.name}", color = Danger, fontSize = 16.sp, fontWeight = FontWeight.Bold)
				Text(s.message, color = Text1, fontSize = 14.sp)
			}
		}
	}
}

@Composable
private fun Button(label: String, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2B283C)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
		Text(label, color = Accent, fontSize = 15.sp)
	}
}

/** A rigged PSD: what was made and saved, and the rig drawn at the pose the sliders set. */
@Composable
private fun RigResult(o: RigOutcome) {
	var values by remember(o) { mutableStateOf(o.preview.parameters.associate { it.id to it.default }) }
	var saveStatus by remember(o) { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	var image by remember(o) { mutableStateOf<ImageBitmap?>(null) }
	LaunchedEffect(o) {
		// Frames draw one after another off the main thread; while one draws, only the latest pose waits.
		snapshotFlow { values }.conflate().collect { pose ->
			image = withContext(Dispatchers.Default) { o.preview.render(pose, 900).toImageBitmap() }
		}
	}
	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text("Rigged ${o.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
		Text("${o.drawables} art meshes, ${o.deformers} deformers, ${o.parameters} parameters in ${(o.seconds * 10).toInt() / 10.0} s",
			color = Text1, fontSize = 14.sp)
		Text("Saved to Rigs/${o.folder.substringAfterLast('/')}: ${o.files.joinToString()}", color = Muted, fontSize = 13.sp)
		for (w in o.warnings) Text("• $w", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
			Button("Save as a PSD2Live project") {
				if (saveStatus == "Saving…") return@Button
				saveStatus = "Saving…"
				scope.launch {
					saveStatus = try {
						val path = withContext(Dispatchers.Default) { saveRigProject(o) }
						"Saved Rigs/${path.substringAfterLast('/')} - it opens in the Mac editor"
					} catch (failure: Throwable) { "Couldn't save: ${failure.message ?: failure::class.simpleName}" }
				}
			}
			saveStatus?.let { Text(it, color = Muted, fontSize = 13.sp) }
		}
		Spacer(Modifier.height(8.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
			Box(Modifier.weight(1f).height(720.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017)), contentAlignment = androidx.compose.ui.Alignment.Center) {
				image?.let { Image(it, contentDescription = "The rig at the chosen pose", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
					?: Text("Drawing…", color = Muted, fontSize = 14.sp)
			}
			Column(Modifier.width(340.dp)) {
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text("Parameters", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					Text("Reset", color = Accent, fontSize = 14.sp, modifier = Modifier.clickable { values = o.preview.parameters.associate { it.id to it.default } })
				}
				for (p in o.preview.parameters) {
					val value = values[p.id] ?: p.default
					Text("${p.name}  ${(value * 100).toInt() / 100.0}", color = Muted, fontSize = 12.sp)
					Slider(value = value, onValueChange = { values = values + (p.id to it) }, valueRange = p.min..p.max,
						colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent), modifier = Modifier.height(32.dp))
				}
			}
		}
	}
}
