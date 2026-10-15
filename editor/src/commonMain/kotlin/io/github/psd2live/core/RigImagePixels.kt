package io.github.psd2live.core

// Pixel access the paint engine needs from an image. On the desktop each is the BufferedImage call
// the engine always made; on the iPad they read and write the image's own 0xAARRGGBB array.

/** The image's size in pixels, under the names the desktop code always used. */
expect val RigImage.width: Int
expect val RigImage.height: Int

/** The pixels of the [width] x [height] block at ([x], [y]), row by row - BufferedImage.getRGB. */
expect fun RigImage.readArgb(x: Int, y: Int, width: Int, height: Int): IntArray

/** Reads the block at ([x], [y]) into [into] (rows [scan] apart, from [offset]) - getRGB with an array. */
expect fun RigImage.readArgbInto(x: Int, y: Int, width: Int, height: Int, into: IntArray, offset: Int, scan: Int)

/** Writes [pixels] (rows [scan] apart, from [offset]) over the block at ([x], [y]) - BufferedImage.setRGB. */
expect fun RigImage.writeArgb(x: Int, y: Int, width: Int, height: Int, pixels: IntArray, offset: Int, scan: Int)

expect fun RigImage.argbAt(x: Int, y: Int): Int

expect fun RigImage.setArgbAt(x: Int, y: Int, argb: Int)

/** Replaces this image's pixels with [source]'s, from the corner: a Src copy, alpha included. */
expect fun RigImage.copyPixelsFrom(source: RigImage)

/** Replaces the pixels under ([left], [top], [width], [height]) with [source] stretched over them, nearest pixel. */
expect fun RigImage.copyPixelsScaled(source: RigImage, left: Int, top: Int, width: Int, height: Int)

/** Every pixel to transparent black. */
expect fun RigImage.clearPixels()

/**
 * Draws one shape antialiased, source-over, in [argb] (alpha included), with a round [strokeWidth]
 * pen: a line from ([x0], [y0]) to ([x1], [y1]), or the rectangle or ellipse whose corner is
 * ([left], [top]), outlined or [filled]. Nothing lands outside [clip].
 */
expect fun RigImage.paintShape(
	shape: RasterPaintShape,
	x0: Int, y0: Int, x1: Int, y1: Int,
	left: Int, top: Int, width: Int, height: Int,
	argb: Int, strokeWidth: Float, filled: Boolean, clip: Rectangle?,
)

fun RigImage.writeArgb(x: Int, y: Int, width: Int, height: Int, pixels: IntArray) = writeArgb(x, y, width, height, pixels, 0, width)

enum class RasterPaintShape { LINE, RECTANGLE, ELLIPSE }
