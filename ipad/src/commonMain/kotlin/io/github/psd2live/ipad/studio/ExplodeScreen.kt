package io.github.psd2live.ipad.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Good = Color(0xFF8FE0A8)

/**
 * Explode into pieces (the Mac's Explode wizard): her part list, one paid parts sheet, the pieces cut and numbered;
 * she taps which pieces are which part, and they go onto the drawing as new parts to finish with Fit by hand.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExplodeScreen(project: StudioProject, onChange: (StudioProject) -> Unit, onFit: (String) -> Unit, onClose: () -> Unit) {
	val now by rememberUpdatedState(project)
	val scope = rememberCoroutineScope()
	var parts by remember { mutableStateOf(ExplodeCut.parts(project)) }
	var chosen by remember { mutableStateOf(0) }
	var cut by remember { mutableStateOf<ExplodeCut.Cut?>(null) }
	var sheetImage by remember { mutableStateOf<ImageBitmap?>(null) }
	var splitting by remember { mutableStateOf(false) }
	var splitRows by remember { mutableStateOf(false) }
	var busy by remember { mutableStateOf<String?>(null) }
	var status by remember { mutableStateOf("") }
	var signed by remember { mutableStateOf(false) }
	var confirmPaid by remember { mutableStateOf(false) }
	val map = ExplodeCut.map(project)

	fun work(label: String, block: suspend () -> Unit) {
		busy = label
		scope.launch {
			try { block() } catch (e: Throwable) { status = e.message ?: "Something went wrong." }
			busy = null
		}
	}

	suspend fun show(c: ExplodeCut.Cut) {
		cut = c
		sheetImage = withContext(Dispatchers.Default) { argbRegionBitmap(c.keyed, c.width, c.height, 0f, 0f, c.width.toFloat(), c.height.toFloat(), c.width, c.height) }
	}

	suspend fun recut(p: StudioProject) {
		val url = ExplodeCut.sheet(p) ?: return
		show(withContext(Dispatchers.Default) {
			val px = decodeImage(dataUrlBytes(url)) ?: throw IllegalStateException("The saved sheet is unreadable.")
			ExplodeCut.recut(px, ExplodeCut.splits(p))
		})
	}

	fun saveParts(next: List<ExplodeCut.Part>) { parts = next; onChange(ExplodeCut.withParts(now, next)) }

	LaunchedEffect(Unit) {
		PrivateStudio.signedIn { signed = it }
		if (ExplodeCut.sheet(project) != null) work("Cutting the sheet from before") { recut(now) }
	}

	Column(Modifier.fillMaxSize()) {
		Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("Explode into pieces", color = Text1, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
			Chip("Done", true) { onClose() }
		}
		busy?.let { Column(Modifier.padding(horizontal = 14.dp)) { Text(it, color = Muted, fontSize = 12.sp); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent) } }
		if (status.isNotEmpty()) Text(status, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
		Row(Modifier.fillMaxSize()) {
			// ---- Steps ----
			Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text("1. The parts sheet", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
				Text("One image draws every part in the list below apart, in your drawing's style, on green. It's kept in the project, so cutting it again is free.", color = Muted, fontSize = 12.sp)
				if (!signed) Chip("Sign in to your private studio", true) { PrivateStudio.signIn { ok -> signed = ok; status = if (ok) "Signed in - this iPad stays signed in for 90 days." else "Not signed in." } }
				else if (!confirmPaid) Chip(if (ExplodeCut.sheet(project) == null) "Generate the sheet (1 paid image)" else "Generate a new sheet (1 paid image)", true, busy == null && parts.isNotEmpty()) { confirmPaid = true }
				else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					Chip("Yes, generate", true, busy == null) {
						confirmPaid = false
						work("Drawing the parts sheet (a minute or two)") {
							val p = now
							val png = withContext(Dispatchers.Default) {
								val src = decodeImage(dataUrlBytes(p.source)) ?: throw IllegalStateException("The drawing is unreadable.")
								val k = min(1.0, 2048.0 / max(src.width, src.height))
								val ow = max(1, (src.width * k).roundToInt()); val oh = max(1, (src.height * k).roundToInt())
								val small = if (k < 1.0) StudioAI.resample(src.argb, src.width, src.height, 0, 0, src.width, src.height, ow, oh) else src.argb
								encodePng(ow, oh, ExplodeCut.onWhite(small))
							}
							val sheet = StudioAI.transport(png, null, ExplodeCut.prompt(parts), "parts-sheet")
							val next = ExplodeCut.withSheet(now, withContext(Dispatchers.Default) { encodePng(sheet.width, sheet.height, sheet.argb) })
							onChange(next); recut(next)
							status = "Cut into ${cut?.pieces?.size ?: 0} pieces. Pick a part on the left, then tap its pieces."
						}
					}
					Chip("Cancel") { confirmPaid = false }
				}
				val c = cut
				if (c != null) {
					Text("2. Which pieces are which", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
					Text("Pick a part in the list below, then tap its pieces on the sheet (tap again to let go). Two pieces that touched cut as one: split it.", color = Muted, fontSize = 12.sp)
					FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip(if (splitting) "Tap a piece to split it" else "Split a piece", splitting) { splitting = !splitting }
						if (splitting) Chip(if (splitRows) "Across (rows)" else "Down (columns)", splitRows) { splitRows = !splitRows }
					}

					Text("3. Onto her", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
					Text("Each part goes in as a new part: sized to the part of the same name when there is one, else roughly. Then finish it with Fit by hand.", color = Muted, fontSize = 12.sp)
					Chip("Put the pieces on her", true, busy == null && map.values.any { it.isNotEmpty() }) {
						work("Placing the pieces") {
							val p = now
							val next = withContext(Dispatchers.Default) {
								val placed = parts.mapNotNull { part ->
									val nums = ExplodeCut.map(p)[part.name].orEmpty()
									val (art, w, h) = ExplodeCut.combine(c, nums) ?: return@mapNotNull null
									val box = ExplodeCut.boxFor(p, part.name) ?: ExplodeCut.sheetBox(intArrayOf(
										c.pieces.filter { it.n in nums }.minOf { it.box[0] }, c.pieces.filter { it.n in nums }.minOf { it.box[1] },
										c.pieces.filter { it.n in nums }.maxOf { it.box[2] }, c.pieces.filter { it.n in nums }.maxOf { it.box[3] }), c.width, c.height, p.width, p.height)
									part.name to ExplodeCut.place(art, w, h, box, p.width, p.height)
								}
								ExplodeCut.addParts(p, placed)
							}
							onChange(next)
							val n = next.parts.size - p.parts.size
							status = "Added $n part${if (n == 1) "" else "s"}. Fit each one by hand below."
						}
					}
					val exploded = project.parts.filter { it.json.str("explodedFrom") != null }
					if (exploded.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						for (part in exploded) Chip("Fit ${part.name} by hand", true) { onFit(part.id) }
					}
				}
				Text("The parts", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
				Text("Name each part and say what it is, the way you'd tell an artist (that is what the sheet is drawn from). Mark the ones hidden in the drawing (a neck under the hair) as underneath. Tap a part to pick it, then tap its pieces on the sheet.", color = Muted, fontSize = 12.sp)
				parts.forEachIndexed { i, part ->
					val pieces = map[part.name].orEmpty()
					Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (i == chosen) Color(0xFF332E4A) else Color(0xFF24222F))
						.clickable { chosen = i; splitting = false }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Field(part.name, 14) { saveParts(parts.toMutableList().also { l -> l[i] = ExplodeCut.Part(it, part.desc, part.under) }) }
						Field(part.desc, 12) { saveParts(parts.toMutableList().also { l -> l[i] = ExplodeCut.Part(part.name, it, part.under) }) }
						Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							Chip(if (part.under) "Underneath" else "Visible", part.under) { saveParts(parts.toMutableList().also { l -> l[i] = ExplodeCut.Part(part.name, part.desc, !part.under) }) }
							Chip("Remove") { saveParts(parts.filterIndexed { k, _ -> k != i }); chosen = min(chosen, max(0, parts.size - 1)) }
							if (pieces.isNotEmpty()) Text("pieces ${pieces.joinToString()}", color = Good, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
						}
					}
				}
				Chip("Add a part") { saveParts(parts + ExplodeCut.Part("New part", "")); chosen = parts.size - 1 }

			}
			// ---- The sheet ----
			val c = cut; val image = sheetImage
			BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1A24))) {
				if (c == null || image == null) {
					Text(if (ExplodeCut.sheet(project) == null) "The parts sheet shows here once it's generated." else "Cutting…", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
					return@BoxWithConstraints
				}
				val measurer = rememberTextMeasurer()
				val selected = parts.getOrNull(chosen)?.name
				Canvas(Modifier.fillMaxSize().pointerInput(c, chosen, splitting, splitRows, parts) {
					detectTapGestures { at ->
						val s = min(size.width.toFloat() / c.width, size.height.toFloat() / c.height)
						val ox = (size.width - c.width * s) / 2; val oy = (size.height - c.height * s) / 2
						val x = (at.x - ox) / s; val y = (at.y - oy) / s
						val hit = c.pieces.filter { x >= it.box[0] && x < it.box[2] && y >= it.box[1] && y < it.box[3] }.minByOrNull { it.w * it.h } ?: return@detectTapGestures
						if (splitting) {
							val next = ExplodeCut.withSplit(now, hit.n, splitRows)
							onChange(next); splitting = false
							work("Splitting piece ${hit.n}") { recut(next) }
							return@detectTapGestures
						}
						val name = selected ?: return@detectTapGestures
						val m = ExplodeCut.map(now).toMutableMap()
						val mine = m[name].orEmpty()
						for (k in m.keys.toList()) m[k] = m.getValue(k) - hit.n  // a piece belongs to one part
						m[name] = if (hit.n in mine) mine - hit.n else (mine + hit.n).sorted()
						onChange(ExplodeCut.withMap(now, m))
					}
				}) {
					val s = min(size.width / c.width, size.height / c.height)
					val ox = (size.width - c.width * s) / 2; val oy = (size.height - c.height * s) / 2
					val cell = 16f * s.coerceAtLeast(0.5f)
					var yy = 0f
					while (yy < c.height * s) { var xx = 0f; while (xx < c.width * s) {
						if (((xx / cell).toInt() + (yy / cell).toInt()) % 2 == 0) drawRect(Color(0xFF2A2836), Offset(ox + xx, oy + yy), Size(min(cell, c.width * s - xx), min(cell, c.height * s - yy)))
						xx += cell }; yy += cell }
					drawImage(image, dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = IntSize((c.width * s).roundToInt(), (c.height * s).roundToInt()))
					val owner = map.flatMap { (k, v) -> v.map { it to k } }.toMap()
					for (piece in c.pieces) {
						val mine = owner[piece.n] == selected && selected != null
						val colour = if (mine) Accent else if (owner[piece.n] != null) Good else Color(0xFFFF4FA8)
						val tl = Offset(ox + piece.box[0] * s, oy + piece.box[1] * s)
						drawRect(colour, tl, Size(piece.w * s, piece.h * s), style = Stroke(if (mine) 4f else 2f))
						val label = owner[piece.n]?.let { "${piece.n} · $it" } ?: "${piece.n}"
						val text = measurer.measure(label, TextStyle(color = colour, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
						drawRect(Color(0xCC14131C), tl, Size(text.size.width + 8f, text.size.height + 2f))
						drawText(text, topLeft = tl + Offset(4f, 1f))
					}
				}
			}
		}
	}
}

@Composable
private fun Field(value: String, size: Int, onDone: (String) -> Unit) {
	var text by remember(value) { mutableStateOf(value) }
	BasicTextField(text, { text = it; onDone(it) }, singleLine = true, textStyle = TextStyle(color = Text1, fontSize = size.sp),
		cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF1B1A24)).padding(6.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
