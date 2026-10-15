package io.github.psd2live.ipad.studio

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.psd2live.core.RigImage
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

// Little-endian 0xAARRGGBB is B, G, R, A in memory: Skia's BGRA_8888, unpremultiplied.

actual fun decodeImage(bytes: ByteArray): Pixels? {
	val image = try { Image.makeFromEncoded(bytes) } catch (_: Throwable) { return null }
	val w = image.width; val h = image.height
	val bitmap = Bitmap()
	bitmap.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
	if (!image.readPixels(bitmap, 0, 0)) return null
	val raw = bitmap.readPixels() ?: return null
	bitmap.close(); image.close()
	val argb = IntArray(w * h)
	for (i in argb.indices) {
		val o = i * 4
		argb[i] = (raw[o].toInt() and 0xFF) or ((raw[o + 1].toInt() and 0xFF) shl 8) or ((raw[o + 2].toInt() and 0xFF) shl 16) or ((raw[o + 3].toInt() and 0xFF) shl 24)
	}
	return Pixels(w, h, argb)
}

actual fun encodePng(width: Int, height: Int, argb: IntArray): ByteArray {
	val bytes = ByteArray(width * height * 4)
	for (i in argb.indices) {
		val p = argb[i]; val o = i * 4
		bytes[o] = p.toByte(); bytes[o + 1] = (p ushr 8).toByte(); bytes[o + 2] = (p ushr 16).toByte(); bytes[o + 3] = (p ushr 24).toByte()
	}
	val image = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4)
	val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
	return data.bytes.also { image.close() }
}

actual fun argbRegionBitmap(argb: IntArray, stride: Int, rows: Int, x: Float, y: Float, width: Float, height: Float, outWidth: Int, outHeight: Int): ImageBitmap {
	val w = maxOf(1, outWidth); val h = maxOf(1, outHeight)
	val bytes = ByteArray(w * h * 4)
	val stepX = width / w; val stepY = height / h
	// Shrinking: average a few samples across each screen pixel's footprint (smooth, not jagged); enlarging or about
	// 1:1: the nearest pixel (crisp pixels when zoomed in).
	val k = if (stepX > 1.5f || stepY > 1.5f) minOf(4, kotlin.math.ceil(maxOf(stepX, stepY)).toInt()) else 1
	for (row in 0 until h) {
		var o = row * w * 4
		for (col in 0 until w) {
			var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
			for (sy in 0 until k) {
				val py = kotlin.math.floor(y + (row + (sy + 0.5f) / k) * stepY).toInt()
				if (py !in 0 until rows) continue
				for (sx in 0 until k) {
					val px = kotlin.math.floor(x + (col + (sx + 0.5f) / k) * stepX).toInt()
					if (px !in 0 until stride) continue
					val p = argb[py * stride + px]; val pa = p ushr 24
					// Colour weighted by alpha, so transparent pixels don't darken edges.
					a += pa; r += ((p ushr 16) and 0xFF) * pa; g += ((p ushr 8) and 0xFF) * pa; b += (p and 0xFF) * pa; n++
				}
			}
			if (n > 0 && a > 0) {
				bytes[o] = (b / a).toByte(); bytes[o + 1] = (g / a).toByte(); bytes[o + 2] = (r / a).toByte(); bytes[o + 3] = (a / n).toByte()
			}
			o += 4
		}
	}
	return Image.makeRaster(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, w * 4).toComposeImageBitmap()
}

actual fun rigImageOver(width: Int, height: Int, argb: IntArray): RigImage = RigImage(width, height, argb)

actual val RigImage.pixels: IntArray get() = argb
