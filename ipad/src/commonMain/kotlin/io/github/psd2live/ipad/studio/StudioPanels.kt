package io.github.psd2live.ipad.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)
private val Good = Color(0xFF8FE0A8)

/** The inspector's tabs: the part itself, repairing art, and checking the project. */
enum class InspectorTab(val label: String) { PART("Part"), REPAIR("Repair"), CHECK("Check") }

/**
 * What the Repair and Check panels need from the studio screen: the project and selection, a way to run slow work
 * (busy bar, errors shown), to change the project, to show a picture on the canvas instead of the artwork, to zoom to
 * a spot, and the clone points tapped on the canvas.
 */
class PanelHost(
	val project: StudioProject,
	val selected: StudioPart?,
	val busy: Boolean,
	val run: (label: String, block: suspend () -> Unit) -> Unit,
	val change: (StudioProject) -> Unit,
	val latest: () -> StudioProject?,
	val showOnCanvas: (IntArray?, String?) -> Unit,
	val focus: (IntArray) -> Unit,
	val reloadMask: (partId: String, url: String?) -> Unit,
	val select: (String) -> Unit,
	val say: (String) -> Unit,
	val cloneSource: Pt?, val cloneDest: Pt?,
	val pickPoint: (String?) -> Unit, val picking: String?,
)

// ---- Repair: Cleanup and Clone ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepairPanel(h: PanelHost) {
	val part = h.selected
	if (part == null) { Text("Choose a part on the left first.", color = Muted, fontSize = 13.sp); return }
	val p = h.project
	val index = p.parts.indexOfFirst { it.id == part.id }

	Text("Clean up edges", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
	Text("Paint the part's mask over what should stay (the keep-mask), then trim ${part.name} to it. Soft edge fades the trim inward; what's trimmed off can go back to a part behind or into another part.",
		color = Muted, fontSize = 12.sp)
	var feather by remember(part.id) { mutableStateOf(0f) }
	var leftover by remember(part.id) { mutableStateOf(StudioRepair.Leftover.DROP) }
	var target by remember(part.id) { mutableStateOf<String?>(null) }
	Text("Soft edge ${feather.roundToInt()} px", color = Muted, fontSize = 12.sp)
	Slider(feather, { feather = it }, valueRange = 0f..24f, steps = 23, colors = sliderColors(), modifier = Modifier.height(30.dp))
	Text("What's trimmed off", color = Muted, fontSize = 12.sp)
	for (l in StudioRepair.Leftover.entries) Chip(l.label, leftover == l) { leftover = l; target = null }
	if (leftover != StudioRepair.Leftover.DROP) {
		val choices = if (leftover == StudioRepair.Leftover.RETURN) p.parts.take(maxOf(0, index)) else p.parts.filter { it.id != part.id }
		Text(if (leftover == StudioRepair.Leftover.RETURN) "Return to (parts behind ${part.name})" else "Move into", color = Muted, fontSize = 12.sp)
		if (choices.isEmpty()) Text("No part behind ${part.name}: move one behind it first.", color = Muted, fontSize = 12.sp)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			for (c in choices.asReversed()) Chip(c.name, target == c.id) { target = c.id }
		}
	}
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Chip("Trim to the mask", true, enabled = !h.busy && part.cutout != null && part.mask != null && (leftover == StudioRepair.Leftover.DROP || target != null)) {
			val f = feather.roundToInt(); val l = leftover; val t = target; val id = part.id
			h.run("Trimming ${part.name}") {
				val now = h.latest() ?: return@run
				val r = withContext(Dispatchers.Default) { StudioRepair.cleanup(now, id, f, l, t) }
				h.latest()?.let { h.change(StudioRepair.applyCleanup(it, id, r)) }
				h.say("${r.event}. Undo cleanup puts it back.")
			}
		}
		Chip("Undo cleanup", enabled = !h.busy && part.json["edgeCleanupBackup"] is kotlinx.serialization.json.JsonObject) {
			try { h.change(StudioRepair.undoCleanup(p, part.id)); h.say("Cleanup undone.") } catch (e: IllegalStateException) { h.say(e.message ?: "Couldn't undo.") }
		}
	}
	if (part.cutout == null) Text("${part.name} has no art of its own yet.", color = Muted, fontSize = 12.sp)
	else if (part.mask == null) Text("No keep-mask yet: paint over what should stay.", color = Muted, fontSize = 12.sp)

	Spacer(Modifier.height(10.dp))
	Text("Clone into a part behind", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
	Text("Fill hidden art by copying the drawing: tap a source point and where it lands, paint the part's mask over the area to fill, choose the part behind that receives it. The drawing's own pixels, not AI.",
		color = Muted, fontSize = 12.sp)
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Chip(if (h.picking == "source") "Tap the source…" else if (h.cloneSource != null) "Source ✓" else "Set source", h.picking == "source") { h.pickPoint(if (h.picking == "source") null else "source") }
		Chip(if (h.picking == "dest") "Tap where it lands…" else if (h.cloneDest != null) "Destination ✓" else "Set destination", h.picking == "dest") { h.pickPoint(if (h.picking == "dest") null else "dest") }
	}
	var scale by remember(part.id) { mutableStateOf(1.0) }
	Text("Sample size", color = Muted, fontSize = 12.sp)
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (s in StudioRepair.cloneScales) Chip("×${if (s == s.toInt().toDouble()) s.toInt() else s}", scale == s) { scale = s } }
	var cloneTarget by remember(part.id) { mutableStateOf<String?>(null) }
	val behind = p.parts.take(maxOf(0, index))
	Text("Into (parts behind ${part.name})", color = Muted, fontSize = 12.sp)
	if (behind.isEmpty()) Text("No part behind ${part.name}.", color = Muted, fontSize = 12.sp)
	FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		for (c in behind.asReversed()) Chip(c.name, cloneTarget == c.id) { cloneTarget = c.id }
	}
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Chip("Clone", true, enabled = !h.busy && h.cloneSource != null && h.cloneDest != null && cloneTarget != null && part.mask != null) {
			val src = h.cloneSource!!; val dst = h.cloneDest!!; val t = cloneTarget!!; val sc = scale; val id = part.id
			h.run("Cloning") {
				val now = h.latest() ?: return@run
				val (next, name) = withContext(Dispatchers.Default) { StudioRepair.clone(now, id, src, dst, t, sc) }
				h.change(next); h.say("Cloned into $name. Hide ${part.name} to see it; Undo clone takes it back.")
			}
		}
		Chip("Undo clone", enabled = !h.busy && part.json["cloneUndo"] is kotlinx.serialization.json.JsonObject) {
			try { h.change(StudioRepair.undoClone(p, part.id)); h.say("Clone undone.") } catch (e: IllegalStateException) { h.say(e.message ?: "Couldn't undo.") }
		}
	}
}

// ---- Check: Match, Motion test, Inspect, Preflight, hand-off ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckPanel(h: PanelHost) {
	val p = h.project
	val part = h.selected

	// Match the drawing.
	Text("Match the drawing", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
	Text("Finds places where the layers don't show what the drawing shows. Nothing changes until you tap a fix.", color = Muted, fontSize = 12.sp)
	var match by remember(p.id) { mutableStateOf<StudioMatch.Result?>(null) }
	var skipped by remember(p.id) { mutableStateOf(setOf<String>()) }
	Chip(if (match == null) "Compare with the drawing" else "Compare again", true, enabled = !h.busy) {
		h.run("Comparing the layers with the drawing") {
			val now = h.latest() ?: return@run
			val r = withContext(Dispatchers.Default) { StudioMatch.compare(now) }
			match = r; skipped = emptySet()
			h.showOnCanvas(r.overlay, "Red: wrong layer in front · Blue: painted into two layers · Yellow: in no layer")
		}
	}
	match?.let { m ->
		val open = m.proposals.filter { it.key !in skipped }
		Text(buildString {
			append(if (open.isEmpty()) "Nothing to fix." else "${open.size} to look at.")
			if (m.waiting.isNotEmpty()) append(" ${m.waiting.size} more show after you answer the ${m.waiting.mapNotNull { it.waitsFor }.toSet().joinToString(" and ")} card.")
			if (m.missing > 2000) append(" Yellow: nothing is painted there yet - Peel → Reveal on the part that should have it.")
		}, color = Muted, fontSize = 12.sp)
		Chip("Show the map") { h.showOnCanvas(m.overlay, "Red: wrong layer in front · Blue: painted into two layers · Yellow: in no layer") }
		for (q in open.take(8)) {
			Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF24222F)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(when (q.kind) {
					StudioMatch.Kind.OWNER -> "${q.aName} and ${q.bName} both have this piece painted in. When they move, you'd see it twice. Which layer is it part of?"
					StudioMatch.Kind.MOVE -> "${q.aName} should be in front of ${q.bName}."
					StudioMatch.Kind.SPLIT -> "This bit of ${q.aName} should be in front of ${q.bName}."
				}, color = Text1, fontSize = 13.sp)
				FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Chip("Show") {
						h.run("Showing it") {
							val now = h.latest() ?: return@run
							val img = withContext(Dispatchers.Default) { StudioMatch.highlight(now, q) }
							h.showOnCanvas(img, "The piece, in blue"); h.focus(q.bounds)
						}
					}
					if (q.kind != StudioMatch.Kind.OWNER) Chip("Preview the fix") {
						h.run("Previewing the fix") {
							val now = h.latest() ?: return@run
							val img = withContext(Dispatchers.Default) { StudioRender.composite(StudioMatch.apply(now, q).project) }
							h.showOnCanvas(img, "After the fix (not applied yet)"); h.focus(q.bounds)
						}
					}
					fun accept(upper: Boolean) {
						h.run("Applying") {
							val now = h.latest() ?: return@run
							val r = withContext(Dispatchers.Default) { StudioMatch.apply(now, q, upper) }
							h.change(r.project); r.select?.let(h.select)
							val touched = setOf(q.a, q.b)
							skipped = skipped + m.proposals.filter { it.key == q.key || it.a in touched || it.b in touched }.map { it.key }
							h.showOnCanvas(null, null); h.say("${r.note}. Compare again to see what's left.")
						}
					}
					if (q.kind == StudioMatch.Kind.OWNER) {
						Chip("Part of ${q.aName} (moves with ${q.aMoves})", true, enabled = !h.busy) { accept(true) }
						Chip("Part of ${q.bName} (moves with ${q.bMoves})", true, enabled = !h.busy) { accept(false) }
					} else Chip(if (q.kind == StudioMatch.Kind.MOVE) "Move ${q.aName} in front" else "Bring it to the front", true, enabled = !h.busy) { accept(true) }
					Chip("Leave it") { skipped = skipped + q.key }
				}
			}
		}
		if (open.size > 8) Text("${open.size - 8} more after these.", color = Muted, fontSize = 12.sp)
	}

	// Movement coverage test.
	Spacer(Modifier.height(10.dp))
	Text("Movement test", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
	if (part == null) Text("Choose a part to move it and see the holes it leaves.", color = Muted, fontSize = 12.sp)
	else {
		Text("Moves ${part.name} (with its hidden art) for a look: orange shows holes that open up. Nothing is saved.", color = Muted, fontSize = 12.sp)
		var on by remember(part.id) { mutableStateOf(false) }
		var dx by remember(part.id) { mutableStateOf(0f) }
		var dy by remember(part.id) { mutableStateOf(0f) }
		var gaps by remember(part.id) { mutableStateOf<StudioChecks.Motion?>(null) }
		Chip(if (on) "Movement preview: on" else "Movement preview: off", on) { on = !on; if (!on) { gaps = null; h.showOnCanvas(null, null) } }
		if (on) {
			LaunchedEffect(part.id, dx.roundToInt(), dy.roundToInt()) {
				delay(120)
				val now = h.latest() ?: return@LaunchedEffect
				val r = withContext(Dispatchers.Default) { StudioChecks.motion(now, part.id, dx.roundToInt(), dy.roundToInt(), true) }
				gaps = r; h.showOnCanvas(r.moved, "${part.name} moved ${dx.roundToInt()}, ${dy.roundToInt()} px · orange = new holes")
			}
			Text("Across ${dx.roundToInt()} px", color = Muted, fontSize = 12.sp)
			Slider(dx, { dx = it }, valueRange = -200f..200f, colors = sliderColors(), modifier = Modifier.height(30.dp))
			Text("Down ${dy.roundToInt()} px", color = Muted, fontSize = 12.sp)
			Slider(dy, { dy = it }, valueRange = -200f..200f, colors = sliderColors(), modifier = Modifier.height(30.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Chip("Left") { dx = -40f; dy = 0f }; Chip("Right") { dx = 40f; dy = 0f }; Chip("Reset") { dx = 0f; dy = 0f }
			}
			gaps?.let { g ->
				Text("${g.gaps} newly exposed pixels at this offset.", color = Muted, fontSize = 12.sp)
				Chip("Use the holes as the mask", enabled = g.gapMask != null && !h.busy) {
					val url = g.gapMask; val id = part.id
					h.latest()?.let { h.change(StudioEdits.setMask(it, id, url)) }
					h.reloadMask(id, url)
					h.say("The holes are now ${part.name}'s mask. Refine it, then clone or repair into a part behind.")
				}
			}
		}
	}

	// Inspect the selected part.
	Spacer(Modifier.height(10.dp))
	Text("Inspect", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
	if (part == null) Text("Choose a part to see it alone and check its pixels.", color = Muted, fontSize = 12.sp)
	else {
		var report by remember(part.id, part.cutout) { mutableStateOf<StudioChecks.Inspection?>(null) }
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			for ((label, bg) in listOf("Alone" to null, "On white" to -1, "On black" to (0xFF000000).toInt())) Chip(label) {
				h.run("Inspecting ${part.name}") {
					val now = h.latest() ?: return@run
					val img = withContext(Dispatchers.Default) { StudioChecks.isolated(now, part.id, bg) }
					if (img == null) { h.say("Extract or import ${part.name}'s art first."); return@run }
					val art = withContext(Dispatchers.Default) { StudioPeel.partArt(now, part.id) }!!
					report = withContext(Dispatchers.Default) { StudioChecks.inspect(art, now.width, now.height) }
					h.showOnCanvas(img, "${part.name} alone")
					report?.bounds?.let { b -> h.focus(intArrayOf(b[0], b[1], b[0] + b[2], b[1] + b[3])) }
				}
			}
		}
		report?.let { r ->
			Text("${r.visible} visible · ${r.translucent} soft-edged · ${r.transparent} transparent px" +
				(r.bounds?.let { b -> " · ${b[2]} × ${b[3]} at ${b[0]}, ${b[1]}" } ?: ""), color = Muted, fontSize = 12.sp)
			for (w in r.warnings) Text(w, color = Danger, fontSize = 12.sp)
		}
	}

	// Preflight.
	Spacer(Modifier.height(10.dp))
	Text("Before hand-off", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
	val issues = remember(p) { StudioChecks.preflight(p) }
	val blockers = issues.filter { it.blocker }
	Text(if (blockers.isEmpty()) "No blockers. ${issues.size} thing(s) to review." else "${blockers.size} blocker(s), ${issues.size - blockers.size} to review.",
		color = if (blockers.isEmpty()) Good else Danger, fontSize = 13.sp)
	for (i in issues.sortedBy { !it.blocker }.take(12)) Row(Modifier.fillMaxWidth().clickable(enabled = i.partId != null) { i.partId?.let(h.select) },
		horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Box(Modifier.padding(top = 5.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (i.blocker) Danger else Color(0xFFFFC46B)))
		Text(i.message, color = Muted, fontSize = 12.sp)
	}
	if (issues.size > 12) Text("…and ${issues.size - 12} more.", color = Muted, fontSize = 12.sp)
	Text("Hand-off checklist", color = Muted, fontSize = 12.sp)
	for ((key, label) in StudioChecks.handoff) {
		val on = StudioChecks.handoffChecked(p, key)
		Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { h.change(StudioChecks.setHandoff(p, key, !on)) }.padding(vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(if (on) "☑" else "☐", color = if (on) Accent else Muted, fontSize = 18.sp)
			Text(label, color = Text1, fontSize = 13.sp)
		}
	}
	Text("Ticks clear themselves when the art changes, as on the Mac.", color = Muted, fontSize = 11.sp)
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
