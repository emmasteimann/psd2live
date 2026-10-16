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
import io.github.psd2live.project.config
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/**
 * History (the desktop's history tree): every change kept in the Rig tab, newest first, with the Mac's own history
 * when the project came from there. Tap one to see it; "Go back to this" makes it the current rig - nothing is
 * deleted, later changes stay in the tree as another branch, so you can come forward again.
 */
@Composable
fun HistoryScreen(outcome: RigOutcome, history: RigProjectHistory, onPicked: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val nodes = remember(history) { history.tree.nodes() }
	val headId = remember(history) { history.tree.head().node.id }
	var selected by remember { mutableStateOf(headId) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	// Depth of each node from the root, to indent branches.
	val depth = remember(nodes) {
		val byId = nodes.associateBy { it.id }
		nodes.associate { n -> var d = 0; var p = n.parentId; while (p != null) { d++; p = byId[p]?.parentId }; n.id to d }
	}
	val children = remember(nodes) { nodes.groupBy { it.parentId } }

	LaunchedEffect(selected) {
		image = null
		image = withContext(Dispatchers.Default) {
			runCatching {
				val doc = history.tree.selectionAt(selected).snapshot
				val model = io.github.psd2live.core.PSD2LivePipeline().buildPreview(doc.source, doc.config(io.github.psd2live.core.PipelineConfig(exportCmo3 = false)))
				RigPreview(model).render(emptyMap(), 700).toImageBitmap()
			}.getOrNull()
		}
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("History of ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		Text("Every change you kept, newest first. Going back never deletes anything: later changes stay as another branch.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Box(Modifier.weight(1f).height(820.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017)), contentAlignment = Alignment.Center) {
				image?.let { Image(it, contentDescription = "The rig at this point", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) } ?: Text("Building this version…", color = Muted)
			}
			Column(Modifier.width(400.dp).height(820.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				val b = busy
				if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				else Chip(if (selected == headId) "This is the current rig" else "Go back to this", true, enabled = selected != headId) {
					val id = selected
					busy = "Building that version" to 0.1
					scope.launch {
						try { val o = withContext(Dispatchers.Default) { checkoutRigHistory(outcome, history, id) { st, f -> scope.launch(Dispatchers.Main) { busy = st to f } } }; busy = null; onPicked(o) }
						catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
					}
				}
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
				for (n in nodes.sortedByDescending { it.createdAt }) {
					val time = n.createdAt.toString().replace('T', ' ').take(16)
					Row(Modifier.fillMaxWidth().padding(start = (minOf(depth[n.id] ?: 0, 8) * 6).dp).clip(RoundedCornerShape(8.dp))
						.background(if (n.id == selected) Color(0xFF3A3358) else Color(0xFF24222F)).clickable { selected = n.id }.padding(horizontal = 12.dp, vertical = 8.dp),
						horizontalArrangement = Arrangement.SpaceBetween) {
						Column(Modifier.weight(1f)) {
							Text(n.summary + if (n.id == headId) "  ·  current" else "", color = Text1, fontSize = 14.sp)
							Text("$time  ·  ${n.actor}" + if ((children[n.id]?.size ?: 0) > 1) "  ·  branches here" else "", color = Muted, fontSize = 11.sp)
						}
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
