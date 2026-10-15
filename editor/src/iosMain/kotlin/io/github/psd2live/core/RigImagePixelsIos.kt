package io.github.psd2live.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.*
import kotlin.math.max
import kotlin.math.min

@Suppress("EXTENSION_SHADOWED_BY_MEMBER") actual val RigImage.width: Int get() = this.width
@Suppress("EXTENSION_SHADOWED_BY_MEMBER") actual val RigImage.height: Int get() = this.height

private fun RigImage.checkBlock(x: Int, y: Int, width: Int, height: Int) {
	if (x < 0 || y < 0 || width < 0 || height < 0 || x + width > this.width || y + height > this.height)
		throw IndexOutOfBoundsException("Coordinate out of bounds!")
}

actual fun RigImage.readArgb(x: Int, y: Int, width: Int, height: Int): IntArray {
	checkBlock(x, y, width, height)
	val out = IntArray(width * height)
	for (row in 0 until height) argb.copyInto(out, row * width, (y + row) * this.width + x, (y + row) * this.width + x + width)
	return out
}

actual fun RigImage.readArgbInto(x: Int, y: Int, width: Int, height: Int, into: IntArray, offset: Int, scan: Int) {
	checkBlock(x, y, width, height)
	for (row in 0 until height) argb.copyInto(into, offset + row * scan, (y + row) * this.width + x, (y + row) * this.width + x + width)
}

actual fun RigImage.writeArgb(x: Int, y: Int, width: Int, height: Int, pixels: IntArray, offset: Int, scan: Int) {
	checkBlock(x, y, width, height)
	for (row in 0 until height) pixels.copyInto(argb, (y + row) * this.width + x, offset + row * scan, offset + row * scan + width)
}

actual fun RigImage.argbAt(x: Int, y: Int): Int {
	checkBlock(x, y, 1, 1)
	return argb[y * width + x]
}

actual fun RigImage.setArgbAt(x: Int, y: Int, argb: Int) {
	checkBlock(x, y, 1, 1)
	this.argb[y * width + x] = argb
}

actual fun RigImage.copyPixelsFrom(source: RigImage) {
	val w = min(width, source.width); val h = min(height, source.height)
	for (row in 0 until h) source.argb.copyInto(argb, row * width, row * source.width, row * source.width + w)
}

actual fun RigImage.copyPixelsScaled(source: RigImage, left: Int, top: Int, width: Int, height: Int) {
	if (width <= 0 || height <= 0) return
	for (y in maxOf(0, top) until minOf(this.height, top + height)) {
		val sy = (((y - top) + 0.5) * source.height / height).toInt().coerceIn(0, source.height - 1)
		for (x in maxOf(0, left) until minOf(this.width, left + width)) {
			val sx = (((x - left) + 0.5) * source.width / width).toInt().coerceIn(0, source.width - 1)
			argb[y * this.width + x] = source.argb[sy * source.width + sx]
		}
	}
}

actual fun RigImage.clearPixels() = argb.fill(0)

/**
 * Core Graphics draws the shape's coverage into an alpha-only mask the size of the area it can reach;
 * the colour then lands source-over in unpremultiplied ARGB, as Java2D does on an INT_ARGB image.
 * Pens are centred on pixel centres, as Java2D's normalised strokes are.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun RigImage.paintShape(
	shape: RasterPaintShape,
	x0: Int, y0: Int, x1: Int, y1: Int,
	left: Int, top: Int, width: Int, height: Int,
	argb: Int, strokeWidth: Float, filled: Boolean, clip: Rectangle?,
) {
	val pad = (strokeWidth / 2f).toInt() + 2
	val reach = Rectangle(minOf(x0, x1, left) - pad, minOf(y0, y1, top) - pad, 0, 0).also {
		it.add(maxOf(x0, x1, left + width) + pad + 1, maxOf(y0, y1, top + height) + pad + 1)
	}
	var area = reach.intersection(Rectangle(0, 0, this.width, this.height))
	if (clip != null) area = area.intersection(clip)
	if (area.isEmpty) return
	val mask = ByteArray(area.width * area.height)
	mask.usePinned { pinned ->
		val context = CGBitmapContextCreate(pinned.addressOf(0), area.width.toULong(), area.height.toULong(), 8u,
			area.width.toULong(), null, CGImageAlphaInfo.kCGImageAlphaOnly.value)
		try {
			// y down, with the mask's corner at the area's.
			CGContextTranslateCTM(context, 0.0, area.height.toDouble())
			CGContextScaleCTM(context, 1.0, -1.0)
			CGContextTranslateCTM(context, -area.x.toDouble(), -area.y.toDouble())
			CGContextSetShouldAntialias(context, true)
			CGContextSetGrayFillColor(context, 1.0, 1.0)
			CGContextSetGrayStrokeColor(context, 1.0, 1.0)
			CGContextSetLineWidth(context, strokeWidth.toDouble())
			CGContextSetLineCap(context, CGLineCap.kCGLineCapRound)
			CGContextSetLineJoin(context, CGLineJoin.kCGLineJoinRound)
			val l = left.toDouble(); val t = top.toDouble(); val w = width.toDouble(); val h = height.toDouble()
			when (shape) {
				RasterPaintShape.LINE -> {
					CGContextBeginPath(context)
					CGContextMoveToPoint(context, x0 + 0.5, y0 + 0.5)
					CGContextAddLineToPoint(context, x1 + 0.5, y1 + 0.5)
					CGContextStrokePath(context)
				}
				RasterPaintShape.RECTANGLE ->
					if (filled) CGContextFillRect(context, CGRectMake(l, t, w, h))
					else CGContextStrokeRect(context, CGRectMake(l + 0.5, t + 0.5, w, h))
				RasterPaintShape.ELLIPSE ->
					if (filled) CGContextFillEllipseInRect(context, CGRectMake(l, t, w, h))
					else CGContextStrokeEllipseInRect(context, CGRectMake(l + 0.5, t + 0.5, w, h))
			}
		} finally {
			CGContextRelease(context)
		}
	}
	val alpha = ((argb ushr 24) and 0xFF) / 255f
	val red = (argb ushr 16) and 0xFF; val green = (argb ushr 8) and 0xFF; val blue = argb and 0xFF
	for (row in 0 until area.height) for (column in 0 until area.width) {
		val source = ((mask[row * area.width + column].toInt() and 0xFF) / 255f) * alpha
		if (source <= 0f) continue
		val at = (area.y + row) * this.width + area.x + column
		val destination = this.argb[at]
		val destinationAlpha = ((destination ushr 24) and 0xFF) / 255f
		val back = destinationAlpha * (1f - source)
		val outAlpha = source + back
		fun channel(src: Int, shift: Int) = ((src * source + ((destination ushr shift) and 0xFF) * back) / outAlpha + 0.5f).toInt().coerceIn(0, 255)
		this.argb[at] = ((outAlpha * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or
			(channel(red, 16) shl 16) or (channel(green, 8) shl 8) or channel(blue, 0)
	}
}
