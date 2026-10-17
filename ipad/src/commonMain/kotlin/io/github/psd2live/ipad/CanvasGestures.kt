package io.github.psd2live.ipad

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

/** Where a canvas sits on screen: [zoom] times the fitted scale, moved by [panX]/[panY] screen pixels. */
data class CanvasView(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
	/** Canvas units to screen pixels in a [boxW] x [boxH] box: scale, left, top. */
	fun layout(boxW: Float, boxH: Float, canvasW: Float, canvasH: Float): FloatArray {
		val scale = minOf(boxW / canvasW, boxH / canvasH) * zoom
		return floatArrayOf(scale, (boxW - canvasW * scale) / 2f + panX, (boxH - canvasH * scale) / 2f + panY)
	}
}

/** What a canvas does with touch, Procreate-style. Points are screen pixels; pressure is 0..1 (1 for a finger). */
interface CanvasTouch {
	fun start(at: Offset, pressure: Float)
	fun move(at: Offset, pressure: Float)
	fun end()
	/** A second finger landed: the stroke in progress is dropped. */
	fun cancel()
	fun undo()
	fun redo()
	fun view(): CanvasView
	fun setView(view: CanvasView)
	/** The layout [CanvasView.layout] gives for the current box. */
	fun layout(view: CanvasView): FloatArray
}

/**
 * One finger or the Pencil draws; two fingers pan and pinch-zoom (the point under the pinch stays under it); a quick
 * two-finger tap undoes and a three-finger tap redoes. A Pencil stroke ignores fingers resting on the glass.
 * With [fingersPan] on (Procreate's "Pencil only"), only the Pencil draws and one finger pans. [onStylus] hears every
 * Pencil touch, so a screen can switch to Pencil-only the first time one is used.
 */
fun Modifier.canvasTouch(key: Any, fingersPan: () -> Boolean = { false }, onStylus: () -> Unit = {}, touch: () -> CanvasTouch): Modifier = pointerInput(key) {
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = false)
		val t = touch()
		val stylus = down.type == PointerType.Stylus
		if (stylus) onStylus()
		val startTime = down.uptimeMillis
		val panning = !stylus && fingersPan()
		var drawing = !panning
		if (drawing) t.start(down.position, if (stylus) down.pressure.coerceIn(0.05f, 1f) else 1f)
		var panFrom: Offset? = if (panning) down.position else null
		var viewAtPan = t.view()
		down.consume()
		var pinchFrom: Pair<Offset, Float>? = null
		var viewAtPinch = t.view()
		var fingers = 1
		var moved = 0f
		var endTime = startTime
		while (true) {
			val event = awaitPointerEvent()
			event.changes.firstOrNull()?.let { endTime = it.uptimeMillis }
			val pressed = event.changes.filter { it.pressed }
			if (pressed.isEmpty()) break
			if (!stylus && pressed.size >= 2) {
				fingers = maxOf(fingers, pressed.size)
				if (drawing) { drawing = false; t.cancel() }
				val a = pressed[0].position; val b = pressed[1].position
				val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f); val dist = hypot(a.x - b.x, a.y - b.y).coerceAtLeast(1f)
				val from = pinchFrom ?: (mid to dist).also { pinchFrom = it; viewAtPinch = t.view() }
				moved = maxOf(moved, hypot(mid.x - from.first.x, mid.y - from.first.y), kotlin.math.abs(dist - from.second))
				val zoom = (viewAtPinch.zoom * dist / from.second).coerceIn(0.1f, 40f)
				val l0 = t.layout(viewAtPinch)
				val cx = (from.first.x - l0[1]) / l0[0]; val cy = (from.first.y - l0[2]) / l0[0]
				val l1 = t.layout(CanvasView(zoom))
				t.setView(CanvasView(zoom, mid.x - (l1[1] + cx * l1[0]), mid.y - (l1[2] + cy * l1[0])))
				event.changes.forEach { it.consume() }
				continue
			}
			if (panFrom != null && pinchFrom == null) {
				// One finger in Pencil-only mode: move the canvas.
				val change = event.changes.firstOrNull { it.id == down.id } ?: continue
				val from = panFrom!!
				moved = maxOf(moved, hypot(change.position.x - from.x, change.position.y - from.y))
				t.setView(viewAtPan.copy(panX = viewAtPan.panX + change.position.x - from.x, panY = viewAtPan.panY + change.position.y - from.y))
				event.changes.forEach { it.consume() }
				continue
			}
			if (!drawing) { event.changes.forEach { it.consume() }; continue }
			val change = event.changes.firstOrNull { it.id == down.id } ?: continue
			t.move(change.position, if (stylus) change.pressure.coerceIn(0.05f, 1f) else 1f)
			change.consume()
		}
		if (panning && fingers < 2) { /* a one-finger pan: nothing to finish */ }
		else if (drawing) t.end()
		// A quick multi-finger tap that barely moved: two fingers undo, three redo.
		else if (moved < 12f && endTime - startTime < 350) {
			if (fingers == 2) t.undo() else if (fingers >= 3) t.redo()
		}
	}
}

/**
 * A screen's undo and redo, described once: its Undo/Redo buttons and the two-/three-finger taps ([undoTaps]) run the
 * same steps under the same conditions.
 */
class UndoRedo(val canUndo: Boolean, val canRedo: Boolean, val undo: () -> Unit, val redo: () -> Unit)

/**
 * Procreate's taps on a whole screen: a quick two-finger tap undoes, three fingers redo, wherever the fingers land (the
 * side panel too). A tap the canvas already took ([canvasTouch] consumes its multi-finger touches) is left to it, so
 * nothing runs twice; a drag, a scroll or a long press is not a tap; the Pencil never counts.
 */
@androidx.compose.runtime.Composable
fun Modifier.undoTaps(steps: UndoRedo): Modifier {
	val current = androidx.compose.runtime.rememberUpdatedState(steps)
	return pointerInput(Unit) {
		awaitEachGesture {
			val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Main)
			if (down.type == PointerType.Stylus) return@awaitEachGesture
			val start = down.uptimeMillis
			var end = start
			val origins = HashMap<androidx.compose.ui.input.pointer.PointerId, Offset>().apply { put(down.id, down.position) }
			var fingers = 1; var moved = 0f; var canvasTook = false
			while (true) {
				val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
				event.changes.firstOrNull()?.let { end = it.uptimeMillis }
				val pressed = event.changes.filter { it.pressed }
				if (pressed.size >= 2 && event.changes.all { it.isConsumed }) canvasTook = true
				for (c in event.changes) { val o = origins.getOrPut(c.id) { c.position }; moved = maxOf(moved, hypot(c.position.x - o.x, c.position.y - o.y)) }
				fingers = maxOf(fingers, pressed.size)
				if (pressed.isEmpty()) break
			}
			if (canvasTook || fingers < 2 || moved >= 12f || end - start >= 350) return@awaitEachGesture
			val s = current.value
			if (fingers == 2) { if (s.canUndo) s.undo() } else if (s.canRedo) s.redo()
		}
	}
}
