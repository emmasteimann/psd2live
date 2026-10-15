package io.github.psd2live.core

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.image.BufferedImage

@Suppress("EXTENSION_SHADOWED_BY_MEMBER") actual val RigImage.width: Int get() = getWidth()
@Suppress("EXTENSION_SHADOWED_BY_MEMBER") actual val RigImage.height: Int get() = getHeight()

actual fun RigImage.readArgb(x: Int, y: Int, width: Int, height: Int): IntArray = getRGB(x, y, width, height, null, 0, width)

actual fun RigImage.readArgbInto(x: Int, y: Int, width: Int, height: Int, into: IntArray, offset: Int, scan: Int) {
	getRGB(x, y, width, height, into, offset, scan)
}

actual fun RigImage.writeArgb(x: Int, y: Int, width: Int, height: Int, pixels: IntArray, offset: Int, scan: Int) =
	setRGB(x, y, width, height, pixels, offset, scan)

actual fun RigImage.argbAt(x: Int, y: Int): Int = getRGB(x, y)

actual fun RigImage.setArgbAt(x: Int, y: Int, argb: Int) = setRGB(x, y, argb)

actual fun RigImage.copyPixelsFrom(source: RigImage) {
	val g = createGraphics()
	try {
		g.composite = AlphaComposite.Src
		g.drawImage(source, 0, 0, null)
	} finally {
		g.dispose()
	}
}

actual fun RigImage.copyPixelsScaled(source: RigImage, left: Int, top: Int, width: Int, height: Int) {
	val graphics = createGraphics()
	try {
		graphics.composite = AlphaComposite.Src
		graphics.drawImage(source, left, top, width, height, null)
	} finally { graphics.dispose() }
}

actual fun RigImage.clearPixels() {
	val g = createGraphics()
	try {
		g.composite = AlphaComposite.Clear
		g.fillRect(0, 0, width, height)
	} finally {
		g.dispose()
	}
}

actual fun RigImage.paintShape(
	shape: RasterPaintShape,
	x0: Int, y0: Int, x1: Int, y1: Int,
	left: Int, top: Int, width: Int, height: Int,
	argb: Int, strokeWidth: Float, filled: Boolean, clip: Rectangle?,
) {
	val g = createGraphics()
	try {
		if (clip != null) g.clip = clip.toAwt()
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.color = java.awt.Color(argb, true)
		g.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
		when (shape) {
			RasterPaintShape.LINE -> g.drawLine(x0, y0, x1, y1)
			RasterPaintShape.RECTANGLE -> if (filled) g.fillRect(left, top, width, height) else g.drawRect(left, top, width, height)
			RasterPaintShape.ELLIPSE -> if (filled) g.fillOval(left, top, width, height) else g.drawOval(left, top, width, height)
		}
	} finally {
		g.dispose()
	}
}
