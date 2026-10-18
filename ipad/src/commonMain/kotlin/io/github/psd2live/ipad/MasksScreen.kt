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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)
private val Warn = Color(0xFFFFC46B)
private val MaskColor = Color(0xFFFFB347)

/**
 * Masks & blend (the desktop Inspector's drawing settings for a part): what clips it (irises inside the eye white), a
 * reversed clip, its colour blend (multiply for blush, screen for glow) and alpha blend, culling, user data, and
 * whether it is a hit area. Tap a part on her; each change is one undoable step and shows at once.
 */
@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MasksScreen(outcome: RigOutcome, onDone: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val editing = remember(outcome) { RigEditing(outcome) }
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	val scope = rememberCoroutineScope()
	var tick by remember { mutableStateOf(0) }
	var chosen by remember { mutableStateOf<String?>(null) }
	val chosenNow by rememberUpdatedState(chosen)
	var pickingMask by remember { mutableStateOf(false) }
	val pickingMaskNow by rememberUpdatedState(pickingMask)
	var busy by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var message by remember { mutableStateOf<String?>(null) }
	var view by remember { mutableStateOf(CanvasView()) }
	var box by remember { mutableStateOf(0f to 0f) }
	var image by remember { mutableStateOf<CanvasPicture?>(null) }
	var overview by remember { mutableStateOf<CanvasPicture?>(null) }
	var wires by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
	var maskWires by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
	val info = remember(chosen, tick) { chosen?.let { RigPartLook.info(editing, it) } }
	var userData by remember(chosen, tick) { mutableStateOf(info?.userData.orEmpty()) }
	var hitName by remember(chosen, tick) { mutableStateOf(info?.hitArea.orEmpty()) }
	val cw = editing.puppet.canvasWidth; val ch = editing.puppet.canvasHeight

	fun step(label: String, block: () -> Unit) {
		if (busy != null) return
		busy = label to 0.3; message = null
		scope.launch { try { withContext(worker) { block() }; tick++ } catch (e: Throwable) { message = e.message ?: e::class.simpleName } finally { busy = null } }
	}

	LaunchedEffect(editing) {
		var lastView: CanvasView? = null
		snapshotFlow { Triple(tick, view, box) }.collectLatest { (_, v, b) ->
			val (w, h) = b
			if (w < 2f || h < 2f) return@collectLatest
			val viewOnly = lastView != null && lastView != v
			lastView = v
			if (viewOnly) delay(140)
			val l = v.layout(w, h, cw, ch)
			image = withContext(worker) {
				val r = visibleRegion(w, h, l, cw, ch, 2_200_000f) ?: return@withContext null
				editing.frame(emptyMap(), r.x0, r.y0, r.x1 - r.x0, r.y1 - r.y0, r.width, r.height).toImageBitmap() to r.rect
			}
			if (!viewOnly) overview = if (visibleRegion(w, h, l, cw, ch, 1f)?.whole(cw, ch) == true) image else withContext(worker) {
				val r = wholeCanvas(w, h, cw, ch)
				editing.frame(emptyMap(), 0f, 0f, cw, ch, r.width, r.height).toImageBitmap() to r.rect
			}
		}
	}
	LaunchedEffect(chosen, tick) {
		val c = chosen
		wires = if (c == null) emptyList() else withContext(worker) { io.github.psd2live.core.PoseTweaks.wireframes(editing.puppet, emptyMap(), setOf(c)).map { it.second } }
		val m = info?.maskedBy.orEmpty().toSet()
		maskWires = if (m.isEmpty()) emptyList() else withContext(worker) { io.github.psd2live.core.PoseTweaks.wireframes(editing.puppet, emptyMap(), m).map { it.second } }
	}

	fun tapped(x: Float, y: Float) {
		scope.launch {
			val id = withContext(worker) { io.github.psd2live.core.PoseTweaks.meshAt(editing.puppet, emptyMap(), x, y) }
			if (id == null) { message = "No part there."; return@launch }
			message = null
			val c = chosenNow
			if (pickingMaskNow && c != null) {
				if (id == c) { message = "A part can't clip itself - tap the part it should show inside."; return@launch }
				val now = RigPartLook.info(editing, c)?.maskedBy.orEmpty()
				val next = if (id in now) now - id else now + id
				step(if (id in now) "Taking the mask off…" else "Clipping…") { RigPartLook.masks(editing, c, next) }
			} else chosen = id
		}
	}

	val undoRedo = UndoRedo(canUndo = editing.canUndo && busy == null, canRedo = editing.canRedo && busy == null,
		undo = { editing.undo(); tick++ }, redo = { editing.redo(); tick++ })
	Column(Modifier.undoTaps(undoRedo).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Masks & blend for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (busy == null) onCancel() })
		}
		HowTo("What this is for", listOf(
			"Tap a part on her. Its outline shows in purple; what clips it shows in orange.",
			"Clipped by: the part only shows inside those parts - an iris inside the eye white, a highlight inside the iris. Pick mask on her, then tap the part it should stay inside (tap again to take it off). Reversed shows it only outside them.",
			"Colour blend: Multiply darkens (blush, shadows), Screen and Add brighten (glow, shine).",
			"Culling hides a part's back when it flips. User data and Hit area are for apps: a hit area is what a tap on her in VTube Studio hits.",
			"Done keeps the changes."))
		Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
			BoxWithConstraints(Modifier.weight(1f).height(800.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				if (box != (w to h)) box = w to h
				Canvas(Modifier.fillMaxSize().canvasTouch(listOf(editing, w, h)) {
					object : CanvasTouch {
						private var at0: Offset? = null; private var moved = false
						override fun start(at: Offset, pressure: Float) { at0 = at; moved = false }
						override fun move(at: Offset, pressure: Float) { at0?.let { if (kotlin.math.hypot(at.x - it.x, at.y - it.y) > 12f) moved = true } }
						override fun end() {
							val a = at0 ?: return; at0 = null
							if (moved || busy != null) return
							val l = view.layout(w, h, cw, ch); tapped((a.x - l[1]) / l[0], (a.y - l[2]) / l[0])
						}
						override fun cancel() { at0 = null }
						override fun undo() { undoRedo.undo() }
						override fun redo() { undoRedo.redo() }
						override fun view() = view
						override fun setView(view: CanvasView) { setter(view) }
						override fun layout(view: CanvasView) = view.layout(w, h, cw, ch)
						private val setter: (CanvasView) -> Unit = { v -> view = v }
					}
				}) {
					val l = view.layout(w, h, cw, ch)
					drawCanvasPicture(overview, l); drawCanvasPicture(image, l)
					fun outline(list: List<FloatArray>, color: Color) {
						val pts = ArrayList<Offset>()
						for (wire in list) { var i = 0; while (i + 1 < wire.size) { pts += Offset(l[1] + wire[i] * l[0], l[2] + wire[i + 1] * l[0]); i += 2 } }
						drawPoints(pts, androidx.compose.ui.graphics.PointMode.Lines, color.copy(alpha = 0.85f), strokeWidth = 1.3f)
					}
					outline(maskWires, MaskColor); outline(wires, Accent)
				}
				if (image == null) Text("Drawing…", color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
			}
			Column(Modifier.width(400.dp).height(800.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("Undo", false, enabled = undoRedo.canUndo) { undoRedo.undo() }
					Chip("Redo", false, enabled = undoRedo.canRedo) { undoRedo.redo() }
					Chip("Fit view", false) { view = CanvasView() }
				}
				Chip("Done - keep the changes", true, enabled = editing.canUndo && busy == null) {
					busy = "Writing the runtime files" to 0.1
					scope.launch {
						try { val o = withContext(worker) { editing.finish(outcome) { s, f -> scope.launch(Dispatchers.Main) { busy = s to f } } }; busy = null; onDone(o) }
						catch (e: Throwable) { busy = null; message = e.message ?: e::class.simpleName }
					}
				}
				val b = busy
				if (b != null) { Text(b.first, color = Muted, fontSize = 13.sp); LinearProgressIndicator(progress = b.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent) }
				message?.let { Text(it, color = Danger, fontSize = 13.sp) }
				val i = info
				if (i == null) { Text("Tap a part on her.", color = Muted, fontSize = 13.sp); return@Column }
				Text(i.name, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)

				Text("Clipped by", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
				if (i.maskedBy.isEmpty()) Text("Nothing - it shows wherever it is drawn.", color = Muted, fontSize = 12.sp)
				androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					for (m in i.maskedBy) Chip("${RigPartLook.name(editing, m)}  ✕", false, enabled = busy == null) {
						val next = i.maskedBy - m; val id = i.id; step("Taking the mask off…") { RigPartLook.masks(editing, id, next) }
					}
				}
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip(if (pickingMask) "Tapping her adds a mask: on" else "Pick mask on her", pickingMask) { pickingMask = !pickingMask }
					Chip(if (i.invertMask) "Reversed (shows outside): on" else "Reversed", i.invertMask, enabled = busy == null && i.maskedBy.isNotEmpty()) {
						val id = i.id; val on = !i.invertMask; step("Changing the mask…") { RigPartLook.invert(editing, id, on) }
					}
				}
				if (pickingMask) Text("Tap the part ${i.name} should stay inside. Turn this off to choose another part.", color = Muted, fontSize = 12.sp)

				Text("Colour blend", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
				val shown = RigPartLook.shownBlend(i)
				androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					for ((mode, label) in RigPartLook.blends) Chip(label, shown == mode, enabled = busy == null) {
						val id = i.id; step("Blending…") { RigPartLook.blend(editing, id, mode) }
					}
				}
				Text("Alpha blend (how its see-through edges combine)", color = Muted, fontSize = 12.sp)
				androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					for ((mode, label) in RigPartLook.alphas) Chip(label, i.alpha == mode, enabled = busy == null) {
						val id = i.id; step("Blending…") { RigPartLook.alpha(editing, id, mode) }
					}
				}
				if (RigPartLook.needs53(i)) Text("This blend needs Cubism 5.3: apps on an older Cubism draw it as Normal. Check it in VTube Studio.", color = Warn, fontSize = 12.sp)

				Chip(if (i.culling) "Culling: on (its back is hidden)" else "Culling: off", i.culling, enabled = busy == null) {
					val id = i.id; val on = !i.culling; step("Changing culling…") { RigPartLook.culling(editing, id, on) }
				}

				Text("Hit area (what a tap on her hits in an app)", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
					Field(hitName, "Name (e.g. Head)", Modifier.weight(1f)) { hitName = it }
					Chip("Set", true, enabled = busy == null && hitName.trim() != (i.hitArea ?: "")) {
						val id = i.id; val n = hitName; step("Setting the hit area…") { RigPartLook.hitArea(editing, id, n) }
					}
					if (i.hitArea != null) Chip("Remove", false, enabled = busy == null) { val id = i.id; step("Removing the hit area…") { RigPartLook.hitArea(editing, id, null) } }
				}
				val areas = editing.model.config.rigEdits.hitAreas
				Text(if (areas.isEmpty()) "No hit areas set: the generated Head and Body are used." else "Hit areas: " + areas.joinToString { "${it.first} (${RigPartLook.name(editing, it.second)})" },
					color = Muted, fontSize = 12.sp)

				Text("User data (a note apps can read)", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
					Field(userData, "Text", Modifier.weight(1f)) { userData = it }
					Chip("Set", true, enabled = busy == null && userData != i.userData) { val id = i.id; val t = userData; step("Saving…") { RigPartLook.userData(editing, id, t) } }
				}
			}
		}
	}
}

@Composable
private fun Field(value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
	BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = Text1, fontSize = 14.sp), cursorBrush = SolidColor(Accent),
		decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = Muted, fontSize = 14.sp); inner() } },
		modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B283C)).padding(10.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF5B4B8A) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 13.sp)
	}
}
