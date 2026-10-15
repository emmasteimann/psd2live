package io.github.psd2live.ipad

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.psd2live.format.compile.RasterImage
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** ARGB straight alpha as a Compose image: little-endian 0xAARRGGBB is BGRA in memory. */
actual fun RasterImage.toImageBitmap(): ImageBitmap {
	val bytes = ByteArray(argb.size * 4)
	for (i in argb.indices) {
		val p = argb[i]
		bytes[i * 4] = p.toByte(); bytes[i * 4 + 1] = (p ushr 8).toByte(); bytes[i * 4 + 2] = (p ushr 16).toByte(); bytes[i * 4 + 3] = (p ushr 24).toByte()
	}
	return Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4).toComposeImageBitmap()
}
