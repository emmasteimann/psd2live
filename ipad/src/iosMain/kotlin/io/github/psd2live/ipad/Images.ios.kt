package io.github.psd2live.ipad

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.psd2live.format.compile.RasterImage
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** ARGB straight alpha as a Compose image: little-endian 0xAARRGGBB is BGRA in memory, so the ints copy over as they are. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun RasterImage.toImageBitmap(): ImageBitmap {
	val bytes = ByteArray(argb.size * 4)
	if (argb.isNotEmpty()) argb.usePinned { from -> bytes.usePinned { to -> platform.posix.memcpy(to.addressOf(0), from.addressOf(0), (argb.size * 4).convert()) } }
	return Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4).toComposeImageBitmap()
}

/** Nearest-pixel shrink to [maxEdge], then the same straight-alpha BGRA hand-over. */
actual fun io.github.psd2live.core.RigImage.toImageBitmap(maxEdge: Int): ImageBitmap {
	val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
	val w = maxOf(1, (width * scale).toInt()); val h = maxOf(1, (height * scale).toInt())
	val bytes = ByteArray(w * h * 4)
	for (y in 0 until h) {
		val sy = minOf(height - 1, (y / scale).toInt()) * width
		for (x in 0 until w) {
			val p = argb[sy + minOf(width - 1, (x / scale).toInt())]
			val o = (y * w + x) * 4
			bytes[o] = p.toByte(); bytes[o + 1] = (p ushr 8).toByte(); bytes[o + 2] = (p ushr 16).toByte(); bytes[o + 3] = (p ushr 24).toByte()
		}
	}
	return Image.makeRaster(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, w * 4).toComposeImageBitmap()
}

actual fun io.github.psd2live.core.RigImage.regionBitmap(x: Float, y: Float, width: Float, height: Float, outWidth: Int, outHeight: Int): ImageBitmap {
	val w = maxOf(1, outWidth); val h = maxOf(1, outHeight)
	val bytes = ByteArray(w * h * 4)
	for (row in 0 until h) {
		val sy = kotlin.math.floor(y + (row + 0.5f) * height / h).toInt()
		if (sy !in 0 until this.height) continue
		for (col in 0 until w) {
			val sx = kotlin.math.floor(x + (col + 0.5f) * width / w).toInt()
			if (sx !in 0 until this.width) continue
			val p = argb[sy * this.width + sx]
			val o = (row * w + col) * 4
			bytes[o] = p.toByte(); bytes[o + 1] = (p ushr 8).toByte(); bytes[o + 2] = (p ushr 16).toByte(); bytes[o + 3] = (p ushr 24).toByte()
		}
	}
	return Image.makeRaster(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, w * 4).toComposeImageBitmap()
}
