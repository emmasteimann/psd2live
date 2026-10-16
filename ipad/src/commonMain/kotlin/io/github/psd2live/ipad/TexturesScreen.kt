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

/**
 * Textures (the desktop's atlas view and texture settings): the texture pages VTube Studio loads, each part's tile on
 * them, the page size and count, and how much detail each part gets. A change rebuilds the rig. Undo; Done writes it.
 */
@Composable
fun TexturesScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	var tick by remember { mutableStateOf(0) }
	var page by remember { mutableStateOf(0) }
	var selected by remember { mutableStateOf<String?>(null) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val parts = remember(tick) { RigTextures.parts(editing) }
	val pages = remember(tick) { RigTextures.pages(editing) }
	val budget = remember(tick) { RigTextures.budget(editing) }

	fun run(label: String, block: () -> Unit) {
		if (busy != null) return
		busy = label to 0.3; message = null
		scope.launch {
			try { withContext(Dispatchers.Default) { block() }; tick++; page = page.coerceAtMost(RigTextures.pages(editing).lastIndex.coerceAtLeast(0)) }
			catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { busy = null }
		}
	}
	LaunchedEffect(tick, page) {
		image = withContext(Dispatchers.Default) { RigTextures.pages(editing).getOrNull(page)?.image?.toImageBitmap(1600) }
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Textures of ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		Text("The texture pages VTube Studio loads. Tap a part to see its tile and give it more or less detail.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (i in pages.indices) Chip("Page ${i + 1}", page == i) { page = i } }
				BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2838))) {
					val density = LocalDensity.current
					val side = with(density) { maxWidth.toPx() }
					Canvas(Modifier.fillMaxSize()) {
						image?.let { drawImage(it, dstSize = IntSize(side.roundToInt(), side.roundToInt())) }
						val pageSize = budget.pageSize
						val k = side / pageSize
						for (p in parts) if (p.page == page) {
							val on = p.layerId == selected
							drawRect(if (on) Warn else Color.White.copy(alpha = 0.25f), Offset(p.x * k, p.y * k), Size(p.w * k, p.h * k), style = Stroke(if (on) 3f else 1f))
						}
					}
				}
				Text("${pages.size} page${if (pages.size == 1) "" else "s"} of ${budget.pageSize} px" + if (RigTextures.fit(editing) < 1f) "  ·  textures shrunk to ${(RigTextures.fit(editing) * 100).roundToInt()}% to fit" else "",
					color = if (RigTextures.fit(editing) < 1f) Warn else Muted, fontSize = 12.sp)
			}
			Column(Modifier.width(380.dp).height(860.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = editing.canUndo && busy == null) { editing.undo(); tick++ }
					Chip("Redo", false, enabled = editing.canRedo && busy == null) { editing.redo(); tick++ }
				}
				val b = busy
				if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				else Chip("Done - keep the changes", true, enabled = editing.canUndo) {
					busy = "Writing the runtime files" to 0.2
					scope.launch {
						try { val o = withContext(Dispatchers.Default) { editing.finish(outcome) { s, f -> scope.launch(Dispatchers.Main) { busy = s to f } } }; busy = null; onDone(o) }
						catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
					}
				}
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
				for (n in RigTextures.notices(editing).take(3)) Text(n, color = Warn, fontSize = 12.sp)
				Text("Page size", color = Muted, fontSize = 12.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					for (s in listOf(1024, 2048, 4096, 8192)) Chip("$s", budget.pageSize == s, enabled = busy == null) { run("Repacking the textures…") { RigTextures.setBudget(editing, s, budget.maxPages, budget.padding) } }
				}
				Text("Pages at most", color = Muted, fontSize = 12.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					for (n in (listOf(1, 2, 4) + budget.maxPages).distinct().sorted()) Chip("$n", budget.maxPages == n, enabled = busy == null) { run("Repacking the textures…") { RigTextures.setBudget(editing, budget.pageSize, n, budget.padding) } }
				}
				val sel = parts.firstOrNull { it.layerId == selected }
				if (sel != null) {
					Text(sel.name, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					Text("Tile ${sel.w} × ${sel.h} px on page ${sel.page + 1}  ·  detail: ${sel.density?.let { "${(it * 100).roundToInt()}%" } ?: "automatic"}", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						for ((label, d) in listOf("Auto" to null, "50%" to 0.5f, "100%" to 1f, "150%" to 1.5f, "200%" to 2f))
							Chip(label, sel.density == d, enabled = busy == null) { val id = sel.layerId; run("Repacking the textures…") { RigTextures.setDensity(editing, id, d) } }
					}
				} else Text("Pick a part below to set its detail.", color = Muted, fontSize = 13.sp)
				Text("Parts", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
				for (p in parts) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (p.layerId == selected) Color(0xFF3A3358) else Color(0xFF24222F))
					.clickable { selected = p.layerId; page = p.page }.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
					Text(p.name, color = Text1, fontSize = 14.sp, modifier = Modifier.weight(1f))
					Text("${p.w}×${p.h}" + (p.density?.let { " · ${(it * 100).roundToInt()}%" } ?: ""), color = Muted, fontSize = 12.sp)
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
