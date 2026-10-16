package io.github.psd2live.ipad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's workspaces, as the Mac app's tabs: the studio, rigging with the shared rigger, and the old web studio. */
private enum class Workspace(val title: String) { STUDIO("Studio"), RIG("Rig"), WEB("Web studio") }

private val Background = Color(0xFF14141C)
private val Bar = Color(0xFF1C1C27)
private val Accent = Color(0xFFC9B7FF)
private val Muted = Color(0xFF9793A7)

/** Layerloom on the iPad: a top bar with the workspaces, and the chosen one below it. */
@Composable
fun LayerloomApp() {
	var workspace by remember { mutableStateOf(Workspace.STUDIO) }
	var rigStart by remember { mutableStateOf<String?>(null) }
	Column(Modifier.fillMaxSize().background(Background).safeDrawingPadding()) {
		Row(
			Modifier.fillMaxWidth().height(52.dp).background(Bar).padding(horizontal = 16.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text("Layerloom", color = Color(0xFFF2ECFD), fontSize = 20.sp, fontWeight = FontWeight.Bold)
			Box(Modifier.padding(start = 12.dp))
			for (w in Workspace.entries) {
				val active = w == workspace
				Text(
					w.title,
					color = if (active) Accent else Muted,
					fontSize = 15.sp,
					modifier = Modifier.clip(RoundedCornerShape(8.dp))
						.background(if (active) Color(0xFF2B283C) else Color.Transparent)
						.clickable { workspace = w }
						.padding(horizontal = 14.dp, vertical = 8.dp),
				)
			}
		}
		Box(Modifier.fillMaxSize()) {
			when (workspace) {
				Workspace.STUDIO -> io.github.psd2live.ipad.studio.NativeStudio(Modifier.fillMaxSize(), onSendToRig = { rigStart = it; workspace = Workspace.RIG }) { workspace = Workspace.WEB }
				// The web studio, kept until the native one covers everything it does.
				Workspace.WEB -> StudioView(Modifier.fillMaxSize())
				Workspace.RIG -> RigWorkspace(Modifier.fillMaxSize(), start = rigStart, onStarted = { rigStart = null })
			}
		}
	}
}

/** The studio (the web app), running offline from the app's own files. */
@Composable
expect fun StudioView(modifier: Modifier)

/** The web studio's saved projects, read out of its storage once for the native studio: slot name to project JSON. */
expect object WebStudioCopy {
	fun read(onDone: (slots: Map<String, String>, error: String?) -> Unit)
}
