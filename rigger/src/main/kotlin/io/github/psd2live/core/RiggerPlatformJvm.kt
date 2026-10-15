package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.stream.IntStream
import java.util.zip.Deflater
import javax.imageio.ImageIO

actual typealias RigImage = BufferedImage

actual val RigImage.pixelWidth: Int get() = width
actual val RigImage.pixelHeight: Int get() = height

actual fun RigImage.argbPixels(): IntArray = AtlasPagePixels.argb(this)

actual fun canonicalPng(image: RigImage): ByteArray = AtlasPagePixels.canonical(image)

actual typealias BitSet = java.util.BitSet

actual fun BitSet.copy(): BitSet = clone() as BitSet

actual fun bitSetOf(bytes: ByteArray): BitSet = java.util.BitSet.valueOf(bytes)

actual fun deflateRawFullFlush(raw: ByteArray, level: Int): ByteArray {
	val deflater = Deflater(level, true)
	try {
		deflater.setInput(raw)
		val output = ByteArrayOutputStream(raw.size / 8 + 64)
		val buffer = ByteArray(64 * 1024)
		while (true) {
			val written = deflater.deflate(buffer, 0, buffer.size, Deflater.FULL_FLUSH)
			output.write(buffer, 0, written)
			// A full flush is complete once it no longer fills the buffer.
			if (written < buffer.size) break
		}
		return output.toByteArray()
	} finally {
		deflater.end()
	}
}

actual fun parallelFor(indices: IntArray, body: (Int) -> Unit) {
	val work = IntStream.of(*indices)
	(if (indices.size > 2) work.parallel() else work).forEach(body)
}

actual fun checkInterrupted() {
	if (Thread.currentThread().isInterrupted) throw InterruptedException()
}

/** The desktop's side of atlas page PNGs: ImageIO's canonical encoding, and the pixels behind a live one. */
object AtlasPagePixels {
	fun canonical(image: BufferedImage): ByteArray {
		val output = ByteArrayOutputStream()
		check(ImageIO.write(image, "png", output)) { tr("error.pngEncoder") }
		return output.toByteArray().also { png -> synchronized(imagesByPng) { imagesByPng[png] = java.lang.ref.WeakReference(image) } }
	}

	/**
	 * The image each canonical encoding was made from, by the encoding's identity (a ByteArray hashes by
	 * identity) and only while the encoding lives, so an export renderer handed the PNG can read the pixels
	 * instead of decoding it. Neither is kept alive by the entry: it serves only while the page holds both.
	 */
	private val imagesByPng = java.util.WeakHashMap<ByteArray, java.lang.ref.WeakReference<BufferedImage>>()

	/**
	 * The ARGB pixels (row by row, not to be modified) [png] decodes to, when it is a live canonical encoding of
	 * a straight-alpha image (whose pixels the PNG holds exactly).
	 */
	fun pixelsOf(png: ByteArray): IntArray? =
		synchronized(imagesByPng) { imagesByPng[png]?.get() }?.takeIf { it.type == BufferedImage.TYPE_INT_ARGB }?.let(::argb)

	fun argb(image: BufferedImage): IntArray {
		val buffer = image.raster.dataBuffer
		if (image.type == BufferedImage.TYPE_INT_ARGB && buffer is java.awt.image.DataBufferInt && buffer.numBanks == 1 &&
			image.raster.sampleModelTranslateX == 0 && image.raster.sampleModelTranslateY == 0 && buffer.data.size == image.width * image.height) {
			return buffer.data
		}
		return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
	}
}

@Suppress("NOTHING_TO_INLINE")
actual inline fun <R> synchronized(lock: Any, block: () -> R): R = kotlin.synchronized(lock, block)
