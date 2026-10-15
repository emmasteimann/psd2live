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

actual fun <T> parallelMap(count: Int, parallel: Boolean, body: (Int) -> T): List<T> {
	val stream = IntStream.range(0, count).mapToObj { body(it) }
	return (if (parallel) stream.parallel() else stream).toList()
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

actual typealias PriorityQueue<E> = java.util.PriorityQueue<E>
actual typealias WeakHashMap<K, V> = java.util.WeakHashMap<K, V>
actual typealias IdentityHashMap<K, V> = java.util.IdentityHashMap<K, V>
actual typealias CopyOnWriteArrayList<E> = java.util.concurrent.CopyOnWriteArrayList<E>
actual typealias SoftReference<T> = java.lang.ref.SoftReference<T>
actual typealias WeakReference<T> = java.lang.ref.WeakReference<T>
actual typealias AtomicLong = java.util.concurrent.atomic.AtomicLong
actual typealias AtomicInteger = java.util.concurrent.atomic.AtomicInteger
actual typealias AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean

actual fun <K, V> synchronizedMapOf(map: MutableMap<K, V>): MutableMap<K, V> = java.util.Collections.synchronizedMap(map)
actual fun <T> synchronizedListOf(list: MutableList<T>): MutableList<T> = java.util.Collections.synchronizedList(list)

actual fun normalizeNfkc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)


actual fun warn(message: String) = System.err.println(message)

actual fun nanoTime(): Long = System.nanoTime()

actual typealias InterruptedException = java.lang.InterruptedException

actual fun callerInterruptCheck(): () -> Unit {
	val caller = Thread.currentThread()
	return { if (caller.isInterrupted) throw InterruptedException() }
}

actual fun newRigImage(width: Int, height: Int): RigImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

actual fun RigImage.writablePixels(): IntArray {
	val buffer = raster.dataBuffer
	check(type == BufferedImage.TYPE_INT_ARGB && buffer is java.awt.image.DataBufferInt && buffer.numBanks == 1 &&
		buffer.data.size == width * height) { "Not a plain ARGB image" }
	return buffer.data
}

actual fun rigImageOf(width: Int, height: Int, rgba: ByteArray): RigImage = PreviewRenderer.rasterImage(width, height, rgba)

actual fun compositePreview(source: org.umamo.format.art.SourceArt): RigImage = PreviewRenderer.composite(source)

actual typealias TextureFile = java.nio.file.Path

actual fun readTexturePixels(file: TextureFile): TexturePixels {
	val image = requireNotNull(ImageIO.read(file.toFile())) { "Invalid upscaled PNG" }
	return TexturePixels(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width), image.colorModel.hasAlpha())
}

/** Read off Java2D itself once; null should it ever not be one per-channel function of (a, v) that keeps alpha. */
actual fun platformSourceOverTable(): ByteArray? {
	val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
	for (a in 0 until 256) for (v in 0 until 256) source.setRGB(v, a, (a shl 24) or (v shl 16) or ((255 - v) shl 8) or v)
	val drawn = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
	val graphics = drawn.createGraphics()
	try { graphics.drawImage(source, 0, 0, 256, 256, null) } finally { graphics.dispose() }
	val table = ByteArray(256 * 256)
	for (a in 0 until 256) for (v in 0 until 256) {
		val pixel = drawn.getRGB(v, a)
		if (a == 0) { if (pixel != 0) return null; continue }
		if ((pixel ushr 24) != a || (pixel ushr 16 and 255) != (pixel and 255)) return null
		if (a == 255 && (pixel and 255) != v) return null
		table[(a shl 8) or v] = pixel.toByte()
	}
	// Green carried 255 - v: the same function must give it.
	for (a in 1 until 256) for (v in 0 until 256)
		if ((drawn.getRGB(v, a) ushr 8 and 255) != (table[(a shl 8) or (255 - v)].toInt() and 255)) return null
	return table
}

/** The padding as it was always made: the raster drawn into the larger image with Java2D. */
actual fun legacyPaddedRaster(current: org.umamo.format.art.SourceLayer, bounds: org.umamo.format.art.LayerBounds): org.umamo.format.art.LayerRaster {
	val left = bounds.left; val top = bounds.top
	val raster = BufferedImage(current.raster.width, current.raster.height, BufferedImage.TYPE_INT_ARGB)
	for (y in 0 until raster.height) for (x in 0 until raster.width) {
		val offset = (y * raster.width + x) * 4
		val rgba = current.raster.rgba
		raster.setRGB(x, y, ((rgba[offset + 3].toInt() and 255) shl 24) or
			((rgba[offset].toInt() and 255) shl 16) or ((rgba[offset + 1].toInt() and 255) shl 8) or
			(rgba[offset + 2].toInt() and 255))
	}
	val image = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
	val graphics = image.createGraphics()
	try { graphics.drawImage(raster, current.bounds.left - left, current.bounds.top - top,
		current.bounds.width, current.bounds.height, null) } finally { graphics.dispose() }
	val rgba = ByteArray(Math.multiplyExact(Math.multiplyExact(bounds.width, bounds.height), 4))
	for (y in 0 until bounds.height) for (x in 0 until bounds.width) {
		val pixel = image.getRGB(x, y)
		val offset = (y * bounds.width + x) * 4
		rgba[offset] = (pixel ushr 16).toByte(); rgba[offset + 1] = (pixel ushr 8).toByte()
		rgba[offset + 2] = pixel.toByte(); rgba[offset + 3] = (pixel ushr 24).toByte()
	}
	return org.umamo.format.art.LayerRaster(bounds.width, bounds.height, rgba)
}
