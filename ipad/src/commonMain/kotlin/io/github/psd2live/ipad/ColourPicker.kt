package io.github.psd2live.ipad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)

private enum class PickerTab(val label: String) { DISC("Disc"), CLASSIC("Classic"), VALUE("Value") }

/** Recently used colours, newest first, shared by every paint session in the app's run. */
private val history = mutableStateListOf<Int>()

/** Remembers [argb] as used (the paint screen calls it when a stroke lands). */
fun rememberColourUse(argb: Int) {
	val c = argb or (0xFF shl 24)
	history.remove(c); history.add(0, c)
	while (history.size > 16) history.removeAt(history.lastIndex)
}

/**
 * A Procreate-style colour picker: Disc (a hue ring around a saturation/brightness disc), Classic (a
 * saturation x brightness square over a hue bar) and Value (HSB and RGB sliders with a hex field), with the colour
 * history below. Hue is kept while saturation or brightness reach zero, so dragging back keeps the colour you had.
 */
@Composable
fun ColourPicker(color: Int, onChange: (Int) -> Unit) {
	var tab by remember { mutableStateOf(PickerTab.DISC) }
	// HSB held here so a grey or black keeps its hue; resynced when the colour changes from outside (eyedropper, swatch).
	var hsv by remember { mutableStateOf(rgbToHsv(color)) }
	var last by remember { mutableStateOf(color) }
	if (color != last) { val (h, s, v) = rgbToHsv(color); hsv = Triple(if (s < 0.01f || v < 0.01f) hsv.first else h, s, v); last = color }
	fun set(h: Float, s: Float, v: Float) {
		hsv = Triple(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)); val c = hsvToRgb(hsv.first, hsv.second, hsv.third); last = c; onChange(c)
	}
	val (hue, sat, value) = hsv

	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
		Box(Modifier.size(40.dp).clip(CircleShape).background(Color(color)).border(2.dp, Accent, CircleShape))
		Text("#" + hex(color), color = Muted, fontSize = 13.sp)
	}
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		for (t in PickerTab.entries) Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (tab == t) Color(0xFF4A3F78) else Color(0xFF2B283C))
			.clickable { tab = t }.padding(horizontal = 12.dp, vertical = 7.dp)) { Text(t.label, color = Accent, fontSize = 13.sp) }
	}
	when (tab) {
		PickerTab.DISC -> Disc(hue, sat, value, ::set)
		PickerTab.CLASSIC -> Classic(hue, sat, value, ::set)
		PickerTab.VALUE -> Values(color, hue, sat, value, ::set) { c -> val (h, s, v) = rgbToHsv(c); set(h, s, v) }
	}
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		Text("History", color = Muted, fontSize = 12.sp)
		if (history.isNotEmpty()) Text("Clear", color = Accent, fontSize = 12.sp, modifier = Modifier.clickable { history.clear() })
	}
	for (row in (history + List(maxOf(0, 16 - history.size)) { 0 }).chunked(8)) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
		for (c in row) Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(if (c == 0) Color(0xFF2B283C) else Color(c))
			.then(if (c == 0) Modifier else Modifier.clickable { val (h, s, v) = rgbToHsv(c); set(h, s, v) }))
	}
}

/** The hue ring (drag round it) around the disc: saturation from the centre out, brightness from top to bottom. */
@Composable
private fun Disc(hue: Float, sat: Float, value: Float, set: (Float, Float, Float) -> Unit) {
	// The gesture handlers outlive a recomposition: they read the current values through these.
	val now by rememberUpdatedState(Triple(hue, sat, value))
	val setNow by rememberUpdatedState(set)
	Canvas(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit) {
		awaitEachGesture {
			val down = awaitFirstDown()
			val c = Offset(size.width / 2f, size.height / 2f); val outer = minOf(size.width, size.height) / 2f
			val ring = hypot(down.position.x - c.x, down.position.y - c.y) > outer * 0.70f
			fun at(p: Offset) {
				val dx = p.x - c.x; val dy = p.y - c.y
				if (ring) { setNow(((atan2(dy, dx) * 180f / PI.toFloat()) + 450f) % 360f, now.second, now.third); return }
				val r = outer * 0.62f
				val x = (dx / r).coerceIn(-1f, 1f); val y = (dy / r).coerceIn(-1f, 1f)
				// Inner disc: across = saturation (white on the left, full colour on the right), down = darker.
				setNow(now.first, (x + 1f) / 2f, 1f - (y + 1f) / 2f)
			}
			at(down.position); down.consume()
			while (true) {
				val e = awaitPointerEvent(); val ch = e.changes.firstOrNull { it.id == down.id } ?: break
				if (!ch.pressed) break
				at(ch.position); ch.consume()
			}
		}
	}) {
		val c = Offset(size.width / 2f, size.height / 2f); val outer = minOf(size.width, size.height) / 2f
		val hues = List(13) { Color(hsvToRgb((it * 30f + 90f) % 360f, 1f, 1f)) }
		drawCircle(Brush.sweepGradient(hues, c), outer * 0.86f, c, style = Stroke(outer * 0.26f))
		val r = outer * 0.62f
		drawCircle(Color(0xFF1C1C27), outer * 0.70f, c)
		// The disc: a horizontal white-to-hue ramp, darkened downward.
		drawCircle(Brush.horizontalGradient(listOf(Color.White, Color(hsvToRgb(hue, 1f, 1f))), c.x - r, c.x + r), r, c)
		drawCircle(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), c.y - r, c.y + r), r, c)
		val a = (hue - 90f) * PI.toFloat() / 180f
		val ringAt = Offset(c.x + cos(a) * outer * 0.86f, c.y + sin(a) * outer * 0.86f)
		drawCircle(Color(hsvToRgb(hue, 1f, 1f)), outer * 0.12f, ringAt); drawCircle(Color.White, outer * 0.12f, ringAt, style = Stroke(3f))
		// The corners of the saturation x brightness square lie outside the disc: the marker stays on its edge.
		var mx = sat * 2f - 1f; var my = (1f - value) * 2f - 1f
		hypot(mx, my).let { if (it > 1f) { mx /= it; my /= it } }
		val discAt = Offset(c.x + mx * r, c.y + my * r)
		drawCircle(Color(hsvToRgb(hue, sat, value)), 14f, discAt); drawCircle(Color.White, 14f, discAt, style = Stroke(3f))
	}
}

/** Saturation across, brightness up, on a square of the current hue, with a hue bar under it. */
@Composable
private fun Classic(hue: Float, sat: Float, value: Float, set: (Float, Float, Float) -> Unit) {
	// The gesture handlers outlive a recomposition: they read the current values through these.
	val now by rememberUpdatedState(Triple(hue, sat, value))
	val setNow by rememberUpdatedState(set)
	Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).pointerInput(Unit) {
		awaitEachGesture {
			val down = awaitFirstDown()
			fun at(p: Offset) = setNow(now.first, p.x / size.width, 1f - p.y / size.height)
			at(down.position); down.consume()
			while (true) {
				val e = awaitPointerEvent(); val ch = e.changes.firstOrNull { it.id == down.id } ?: break
				if (!ch.pressed) break
				at(ch.position); ch.consume()
			}
		}
	}) {
		drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hsvToRgb(hue, 1f, 1f)))))
		drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
		val p = Offset(sat * size.width, (1f - value) * size.height)
		drawCircle(Color.White, 12f, p, style = Stroke(3f)); drawCircle(Color.Black, 15f, p, style = Stroke(1.5f))
	}
	Canvas(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp)).pointerInput(Unit) {
		awaitEachGesture {
			val down = awaitFirstDown()
			fun at(p: Offset) = setNow((p.x / size.width).coerceIn(0f, 1f) * 360f, now.second, now.third)
			at(down.position); down.consume()
			while (true) {
				val e = awaitPointerEvent(); val ch = e.changes.firstOrNull { it.id == down.id } ?: break
				if (!ch.pressed) break
				at(ch.position); ch.consume()
			}
		}
	}) {
		drawRect(Brush.horizontalGradient(List(13) { Color(hsvToRgb(it * 30f % 360f, 1f, 1f)) }))
		drawCircle(Color.White, size.height / 2f - 3f, Offset(hue / 360f * size.width, size.height / 2f), style = Stroke(3f))
	}
}

/** HSB and RGB sliders, each track showing its own range, and a hex field. */
@Composable
private fun Values(color: Int, hue: Float, sat: Float, value: Float, set: (Float, Float, Float) -> Unit, setRgb: (Int) -> Unit) {
	val r = (color ushr 16) and 0xFF; val g = (color ushr 8) and 0xFF; val b = color and 0xFF
	@Composable fun row(label: String, shown: String, v: Float, range: ClosedFloatingPointRange<Float>, onV: (Float) -> Unit) {
		Text("$label  $shown", color = Muted, fontSize = 12.sp)
		Slider(v, onV, valueRange = range, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent), modifier = Modifier.height(28.dp))
	}
	row("Hue", "${hue.roundToInt()}°", hue, 0f..360f) { set(it, sat, value) }
	row("Saturation", "${(sat * 100).roundToInt()}%", sat, 0f..1f) { set(hue, it, value) }
	row("Brightness", "${(value * 100).roundToInt()}%", value, 0f..1f) { set(hue, sat, it) }
	row("Red", "$r", r.toFloat(), 0f..255f) { setRgb((0xFF shl 24) or (it.roundToInt() shl 16) or (g shl 8) or b) }
	row("Green", "$g", g.toFloat(), 0f..255f) { setRgb((0xFF shl 24) or (r shl 16) or (it.roundToInt() shl 8) or b) }
	row("Blue", "$b", b.toFloat(), 0f..255f) { setRgb((0xFF shl 24) or (r shl 16) or (g shl 8) or it.roundToInt()) }
	var text by remember(color) { mutableStateOf(hex(color)) }
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		Text("Hex #", color = Muted, fontSize = 13.sp)
		BasicTextField(text, { t ->
			text = t.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
			if (text.length == 6) setRgb((0xFF shl 24) or text.toInt(16))
		}, singleLine = true, textStyle = TextStyle(color = Text1, fontSize = 15.sp), cursorBrush = SolidColor(Accent),
			modifier = Modifier.width(120.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B283C)).padding(8.dp))
	}
}

private fun hex(c: Int) = (c and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

internal fun rgbToHsv(argb: Int): Triple<Float, Float, Float> {
	val r = ((argb ushr 16) and 0xFF) / 255f; val g = ((argb ushr 8) and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
	val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
	val h = when {
		d == 0f -> 0f
		max == r -> 60f * (((g - b) / d) % 6f)
		max == g -> 60f * ((b - r) / d + 2f)
		else -> 60f * ((r - g) / d + 4f)
	}.let { if (it < 0f) it + 360f else it }
	return Triple(h, if (max == 0f) 0f else d / max, max)
}

internal fun hsvToRgb(h: Float, s: Float, v: Float): Int {
	val c = v * s; val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f)); val m = v - c
	val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
		0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
		3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
	}
	fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
	return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}
