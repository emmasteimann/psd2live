package io.github.psd2live.project

import java.nio.file.attribute.FileTime

actual typealias Path = java.nio.file.Path

actual fun pathOf(first: String, vararg more: String): Path = java.nio.file.Path.of(first, *more)

actual fun Path.resolve(other: String): Path = resolve(other)
actual fun Path.resolve(other: Path): Path = resolve(other)
actual fun Path.resolveSibling(other: String): Path = resolveSibling(other)
actual val Path.parent: Path? get() = getParent()
actual val Path.fileName: Path? get() = getFileName()
actual val Path.isAbsolute: Boolean get() = isAbsolute()
actual fun Path.toAbsolutePath(): Path = toAbsolutePath()
actual fun Path.normalize(): Path = normalize()
actual fun Path.startsWith(other: Path): Boolean = startsWith(other)
actual fun Path.relativize(other: Path): Path = relativize(other)
actual fun Path.toRealPath(): Path = toRealPath()

actual typealias AtomicMoveNotSupportedException = java.nio.file.AtomicMoveNotSupportedException
actual typealias AccessDeniedException = java.nio.file.AccessDeniedException
actual typealias FileAlreadyExistsException = java.nio.file.FileAlreadyExistsException
actual typealias NoSuchFileException = java.nio.file.NoSuchFileException

private fun StandardCopyOption.java(): java.nio.file.StandardCopyOption = java.nio.file.StandardCopyOption.valueOf(name)
private fun StandardOpenOption.java(): java.nio.file.StandardOpenOption = java.nio.file.StandardOpenOption.valueOf(name)

/** Each function is java.nio.file.Files' own, with the same options. */
actual object Files {
	actual fun exists(path: Path): Boolean = java.nio.file.Files.exists(path)
	actual fun isRegularFile(path: Path): Boolean = java.nio.file.Files.isRegularFile(path)
	actual fun isDirectory(path: Path): Boolean = java.nio.file.Files.isDirectory(path)
	actual fun createDirectories(path: Path): Path = java.nio.file.Files.createDirectories(path)
	actual fun readAllBytes(path: Path): ByteArray = java.nio.file.Files.readAllBytes(path)
	actual fun readString(path: Path): String = java.nio.file.Files.readString(path)
	actual fun write(path: Path, bytes: ByteArray, vararg options: StandardOpenOption): Path =
		java.nio.file.Files.write(path, bytes, *options.map { it.java() }.toTypedArray())
	actual fun writeString(path: Path, text: String, vararg options: StandardOpenOption): Path =
		java.nio.file.Files.writeString(path, text, *options.map { it.java() }.toTypedArray())
	actual fun move(source: Path, target: Path, vararg options: StandardCopyOption): Path =
		java.nio.file.Files.move(source, target, *options.map { it.java() }.toTypedArray())
	actual fun copy(source: Path, target: Path, vararg options: StandardCopyOption): Path =
		java.nio.file.Files.copy(source, target, *options.map { it.java() }.toTypedArray())
	actual fun delete(path: Path) = java.nio.file.Files.delete(path)
	actual fun deleteIfExists(path: Path): Boolean = java.nio.file.Files.deleteIfExists(path)
	actual fun size(path: Path): Long = java.nio.file.Files.size(path)
	actual fun list(directory: Path): PathStream<Path> = PathStream(java.nio.file.Files.list(directory).use { it.toList() })
	actual fun walk(start: Path): PathStream<Path> = PathStream(java.nio.file.Files.walk(start).use { it.toList() })
	actual fun createTempDirectory(prefix: String): Path = java.nio.file.Files.createTempDirectory(prefix)
	actual fun createTempDirectory(directory: Path, prefix: String): Path = java.nio.file.Files.createTempDirectory(directory, prefix)
	actual fun createTempFile(directory: Path, prefix: String, suffix: String): Path = java.nio.file.Files.createTempFile(directory, prefix, suffix)
	actual fun createLink(link: Path, existing: Path): Path = java.nio.file.Files.createLink(link, existing)
	actual fun mismatch(a: Path, b: Path): Long = java.nio.file.Files.mismatch(a, b)
	actual fun lastModifiedMillis(path: Path): Long = java.nio.file.Files.getLastModifiedTime(path).toMillis()
	actual fun setLastModifiedMillis(path: Path, millis: Long) { java.nio.file.Files.setLastModifiedTime(path, FileTime.fromMillis(millis)) }
	actual fun isEmptyDirectory(path: Path): Boolean = java.nio.file.Files.list(path).use { it.findFirst().isEmpty }
}

actual fun gzipBytes(bytes: ByteArray): ByteArray =
	java.io.ByteArrayOutputStream().use { out -> java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }; out.toByteArray() }

actual fun gunzipBytes(gzipped: ByteArray, limit: Int): ByteArray =
	java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(gzipped)).use { it.readNBytes(limit) }

actual fun defaultStoreRoot(): Path {
	System.getProperty("psd2live.agent.store")?.takeIf(String::isNotBlank)?.let { return pathOf(it) }
	val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
	return if (localAppData != null) pathOf(localAppData, "PSD2Live", "agent-workspaces")
	else pathOf(System.getProperty("user.home"), ".psd2live", "agent-workspaces")
}

actual fun encodeStoredPng(width: Int, height: Int, rgba: ByteArray): ByteArray {
	val image = io.github.psd2live.core.PreviewRenderer.rasterImage(width, height, rgba)
	return java.io.ByteArrayOutputStream().use { out -> javax.imageio.ImageIO.write(image, "png", out); out.toByteArray() }
}

actual fun decodeStoredPng(encoded: ByteArray, width: Int, height: Int): ByteArray? = PngRgbaDecoder.decode(encoded, width, height)

actual fun decodeAnyPng(encoded: ByteArray, width: Int, height: Int): ByteArray? {
	val image = javax.imageio.ImageIO.read(javax.imageio.stream.MemoryCacheImageInputStream(java.io.ByteArrayInputStream(encoded))) ?: return null
	require(image.width == width && image.height == height) { "Raster dimensions mismatch" }
	return rgbaOf(image)
}

actual fun <K, V, R> loadInParallel(items: Map<K, V>, threads: Int, load: (V) -> R): Map<K, R> {
	val pool = java.util.concurrent.Executors.newFixedThreadPool(threads) { task -> Thread(task, "psd2live-raster-load").apply { isDaemon = true } }
	try {
		val futures = items.mapValues { (_, item) -> pool.submit<R> { load(item) } }
		return futures.mapValues { (_, future) ->
			try { future.get() } catch (failure: java.util.concurrent.ExecutionException) {
				futures.values.forEach { it.cancel(true) }
				throw failure.cause ?: failure
			} catch (interrupted: InterruptedException) {
				futures.values.forEach { it.cancel(true) }
				Thread.currentThread().interrupt()
				throw interrupted
			}
		}
	} finally {
		pool.shutdownNow()
	}
}

actual fun maxMemoryBytes(): Long = Runtime.getRuntime().maxMemory()

actual fun sleepMillis(millis: Long) = Thread.sleep(millis)

/**
 * [image]'s pixels as non-premultiplied RGBA bytes, exactly as [BufferedImage.getRGB] gives them. 8-bit
 * sRGB interleaved rasters (what the PNG reader returns for RGB and RGBA files) and int ARGB images are read
 * from their buffer directly; any other layout goes through `getRGB` a block of rows at a time.
 */
internal fun rgbaOf(image: java.awt.image.BufferedImage): ByteArray {
	val width = image.width
	val height = image.height
	val out = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), 4))
	if (copyInterleaved(image, out) || copyIntArgb(image, out)) return out
	val rows = maxOf(1, 65536 / maxOf(1, width))
	val argb = IntArray(width * minOf(rows, height))
	var y = 0
	while (y < height) {
		val count = minOf(rows, height - y)
		image.getRGB(0, y, width, count, argb, 0, width)
		var o = y * width * 4
		for (i in 0 until width * count) {
			val pixel = argb[i]
			out[o] = (pixel ushr 16).toByte(); out[o + 1] = (pixel ushr 8).toByte()
			out[o + 2] = pixel.toByte(); out[o + 3] = (pixel ushr 24).toByte()
			o += 4
		}
		y += count
	}
	return out
}

/** An 8-bit sRGB RGB or non-premultiplied RGBA image with one interleaved byte buffer, copied into [out]. */
private fun copyInterleaved(image: java.awt.image.BufferedImage, out: ByteArray): Boolean {
	val model = image.colorModel as? java.awt.image.ComponentColorModel ?: return false
	val raster = image.raster
	val sample = raster.sampleModel as? java.awt.image.PixelInterleavedSampleModel ?: return false
	val buffer = raster.dataBuffer as? java.awt.image.DataBufferByte ?: return false
	val bands = sample.numBands
	val alpha = model.hasAlpha()
	if (!model.colorSpace.isCS_sRGB || model.isAlphaPremultiplied || bands != (if (alpha) 4 else 3) ||
		model.componentSize.any { it != 8 } || buffer.numBanks != 1 || raster.parent != null ||
		raster.sampleModelTranslateX != 0 || raster.sampleModelTranslateY != 0) return false
	val stride = sample.scanlineStride
	val step = sample.pixelStride
	val offsets = sample.bandOffsets
	val base = buffer.offset
	val data = buffer.data
	val r = offsets[0]; val g = offsets[1]; val b = offsets[2]; val a = if (alpha) offsets[3] else -1
	var o = 0
	for (y in 0 until image.height) {
		var i = base + y * stride
		for (x in 0 until image.width) {
			out[o] = data[i + r]; out[o + 1] = data[i + g]; out[o + 2] = data[i + b]
			out[o + 3] = if (a >= 0) data[i + a] else -1
			o += 4; i += step
		}
	}
	return true
}

/** A `TYPE_INT_ARGB` image with one int buffer, copied into [out]. */
private fun copyIntArgb(image: java.awt.image.BufferedImage, out: ByteArray): Boolean {
	if (image.type != java.awt.image.BufferedImage.TYPE_INT_ARGB) return false
	val raster = image.raster
	val sample = raster.sampleModel as? java.awt.image.SinglePixelPackedSampleModel ?: return false
	val buffer = raster.dataBuffer as? java.awt.image.DataBufferInt ?: return false
	if (buffer.numBanks != 1 || raster.parent != null || raster.sampleModelTranslateX != 0 || raster.sampleModelTranslateY != 0) return false
	val data = buffer.data
	var o = 0
	for (y in 0 until image.height) {
		var i = buffer.offset + y * sample.scanlineStride
		for (x in 0 until image.width) {
			val pixel = data[i++]
			out[o] = (pixel ushr 16).toByte(); out[o + 1] = (pixel ushr 8).toByte()
			out[o + 2] = pixel.toByte(); out[o + 3] = (pixel ushr 24).toByte()
			o += 4
		}
	}
	return true
}

actual fun copyWithSha256(source: Path, target: Path): String {
	val digest = java.security.MessageDigest.getInstance("SHA-256")
	java.nio.file.Files.newInputStream(source).use { input -> java.nio.file.Files.newOutputStream(target).use { output ->
		val bytes = ByteArray(64 * 1024)
		while (true) {
			val count = input.read(bytes)
			if (count < 0) break
			digest.update(bytes, 0, count)
			output.write(bytes, 0, count)
		}
	} }
	return io.github.psd2live.core.hex(digest.digest())
}

actual class StoreLogger actual constructor(name: String) {
	private val logger = System.getLogger(name)
	actual fun warning(message: String, failure: Throwable?) {
		if (failure == null) logger.log(System.Logger.Level.WARNING, message) else logger.log(System.Logger.Level.WARNING, message, failure)
	}
	actual fun debug(message: String) = logger.log(System.Logger.Level.DEBUG, message)
}

actual fun writePsd(source: org.umamo.format.art.SourceArt): ByteArray = org.umamo.format.psd.PsdWriter.write(source)
