package io.github.psd2live.ipad

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A rendered piece of the canvas: the image and the canvas rectangle (x0, y0, x1, y1) it covers. The editing screens
 * keep two: the sharp picture of what is on screen, and under it the whole canvas at the size it fits the box, so a
 * pan or a zoom out shows her at once (a little soft) instead of empty canvas until the sharp one is redrawn.
 */
typealias CanvasPicture = Pair<ImageBitmap, FloatArray>

/** A canvas rectangle and the output size to draw it at. */
class CanvasRegion(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val width: Int, val height: Int) {
	val rect: FloatArray get() = floatArrayOf(x0, y0, x1, y1)
	/** Whether it is the whole canvas, so the whole-canvas picture would only repeat it. */
	fun whole(cw: Float, ch: Float) = x0 <= 0f && y0 <= 0f && x1 >= cw && y1 >= ch
}

/** The canvas rectangle a [w] x [h] box shows under layout [l] (scale, left, top), at most [budget] output pixels; null when none shows. */
fun visibleRegion(w: Float, h: Float, l: FloatArray, cw: Float, ch: Float, budget: Float): CanvasRegion? {
	val x0 = maxOf(0f, -l[1] / l[0]); val y0 = maxOf(0f, -l[2] / l[0])
	val x1 = minOf(cw, (w - l[1]) / l[0]); val y1 = minOf(ch, (h - l[2]) / l[0])
	if (x1 - x0 < 1f || y1 - y0 < 1f) return null
	return sized(x0, y0, x1, y1, l[0], budget)
}

/** The whole canvas at the scale it fits a [w] x [h] box with, at most [budget] output pixels. */
fun wholeCanvas(w: Float, h: Float, cw: Float, ch: Float, budget: Float = WHOLE_CANVAS_PIXELS): CanvasRegion =
	sized(0f, 0f, cw, ch, minOf(w / cw, h / ch), budget)

/** Output pixels of the whole-canvas picture: enough to look right while it moves, a quarter of a sharp frame's cost. */
const val WHOLE_CANVAS_PIXELS = 600_000f

private fun sized(x0: Float, y0: Float, x1: Float, y1: Float, scale: Float, budget: Float): CanvasRegion {
	var ow = ((x1 - x0) * scale).roundToInt().coerceAtLeast(1); var oh = ((y1 - y0) * scale).roundToInt().coerceAtLeast(1)
	val k = minOf(1f, sqrt(budget / (ow.toFloat() * oh)))
	ow = (ow * k).roundToInt().coerceAtLeast(1); oh = (oh * k).roundToInt().coerceAtLeast(1)
	return CanvasRegion(x0, y0, x1, y1, ow, oh)
}

/** Draws [picture] where its canvas rectangle sits under layout [l], however the view moved since it was drawn. */
fun DrawScope.drawCanvasPicture(picture: CanvasPicture?, l: FloatArray) {
	val (bitmap, r) = picture ?: return
	drawImage(bitmap, dstOffset = IntOffset((l[1] + r[0] * l[0]).roundToInt(), (l[2] + r[1] * l[0]).roundToInt()),
		dstSize = IntSize(((r[2] - r[0]) * l[0]).roundToInt().coerceAtLeast(1), ((r[3] - r[1]) * l[0]).roundToInt().coerceAtLeast(1)))
}
