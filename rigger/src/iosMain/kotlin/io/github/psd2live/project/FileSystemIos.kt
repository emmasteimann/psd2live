@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.psd2live.project

import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.use
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

/** The iPad's path: an okio path. Paths compare by their text, as java.nio's do on one file system. */
class OkioPath(val okio: okio.Path) : Path {
	override fun compareTo(other: Path): Int = okio.toString().compareTo(other.toString())
	override fun equals(other: Any?): Boolean = other is OkioPath && other.okio == okio
	override fun hashCode(): Int = okio.hashCode()
	override fun toString(): String = okio.toString()
}

actual interface Path : Comparable<Path>

private val Path.o: okio.Path get() = (this as OkioPath).okio
private fun okio.Path.wrap(): Path = OkioPath(this)
private val fs = FileSystem.SYSTEM

actual fun pathOf(first: String, vararg more: String): Path = more.fold(first.toPath()) { p, s -> p / s }.wrap()

actual fun Path.resolve(other: String): Path = (o / other).wrap()
actual fun Path.resolve(other: Path): Path = if (other.o.isAbsolute) other else (o / other.o).wrap()
actual fun Path.resolveSibling(other: String): Path = (o.parent?.div(other) ?: other.toPath()).wrap()
actual val Path.parent: Path? get() = o.parent?.wrap()
actual val Path.fileName: Path? get() = o.name.takeIf { it.isNotEmpty() }?.toPath()?.wrap()
actual val Path.isAbsolute: Boolean get() = o.isAbsolute
actual fun Path.toAbsolutePath(): Path = if (o.isAbsolute) this else (fs.canonicalize(".".toPath()) / o).wrap()
actual fun Path.normalize(): Path = o.normalized().wrap()
actual fun Path.startsWith(other: Path): Boolean = o.normalized().segments.let { s -> other.o.normalized().segments.let { t -> s.size >= t.size && s.subList(0, t.size) == t } } &&
	o.isAbsolute == other.o.isAbsolute
actual fun Path.relativize(other: Path): Path = other.o.relativeTo(o).wrap()
actual fun Path.toRealPath(): Path = fs.canonicalize(o).wrap()

actual open class AtomicMoveNotSupportedException(message: String? = null) : IOException(message)
actual open class AccessDeniedException(message: String? = null) : IOException(message)
actual open class FileAlreadyExistsException(message: String? = null) : IOException(message)
actual open class NoSuchFileException(message: String? = null) : IOException(message)

private var tempCounter = 0L

actual object Files {
	actual fun exists(path: Path): Boolean = fs.exists(path.o)
	actual fun isRegularFile(path: Path): Boolean = fs.metadataOrNull(path.o)?.isRegularFile == true
	actual fun isDirectory(path: Path): Boolean = fs.metadataOrNull(path.o)?.isDirectory == true
	actual fun createDirectories(path: Path): Path { fs.createDirectories(path.o); return path }
	actual fun readAllBytes(path: Path): ByteArray = if (!fs.exists(path.o)) throw NoSuchFileException(path.toString()) else fs.read(path.o) { readByteArray() }
	actual fun readString(path: Path): String = readAllBytes(path).decodeToString()

	actual fun write(path: Path, bytes: ByteArray, vararg options: StandardOpenOption): Path {
		if (StandardOpenOption.CREATE_NEW in options && fs.exists(path.o)) throw FileAlreadyExistsException(path.toString())
		if (StandardOpenOption.APPEND in options) fs.appendingSink(path.o).use { s -> val b = okio.Buffer().write(bytes); s.write(b, b.size) }
		else fs.write(path.o) { write(bytes) }
		return path
	}

	actual fun writeString(path: Path, text: String, vararg options: StandardOpenOption): Path = write(path, text.encodeToByteArray(), *options)

	actual fun move(source: Path, target: Path, vararg options: StandardCopyOption): Path {
		if (StandardCopyOption.REPLACE_EXISTING !in options && fs.exists(target.o)) throw FileAlreadyExistsException(target.toString())
		fs.atomicMove(source.o, target.o)
		return target
	}

	actual fun copy(source: Path, target: Path, vararg options: StandardCopyOption): Path {
		if (StandardCopyOption.REPLACE_EXISTING !in options && fs.exists(target.o)) throw FileAlreadyExistsException(target.toString())
		fs.copy(source.o, target.o)
		if (StandardCopyOption.COPY_ATTRIBUTES in options) setLastModifiedMillis(target, lastModifiedMillis(source))
		return target
	}

	actual fun delete(path: Path) { if (!fs.exists(path.o)) throw NoSuchFileException(path.toString()); fs.delete(path.o) }
	actual fun deleteIfExists(path: Path): Boolean = fs.exists(path.o).also { if (it) fs.delete(path.o) }
	actual fun size(path: Path): Long = fs.metadata(path.o).size ?: 0L
	actual fun list(directory: Path): PathStream<Path> = PathStream(fs.list(directory.o).map { it.wrap() })
	/** Files.walk's order: the start, then each directory before what it holds. */
	actual fun walk(start: Path): PathStream<Path> = PathStream(listOf(start) + fs.listRecursively(start.o).map { it.wrap() }.toList())

	actual fun createTempDirectory(prefix: String): Path = createTempDirectory(NSTemporaryDirectory().toPath().wrap(), prefix)
	actual fun createTempDirectory(directory: Path, prefix: String): Path {
		while (true) {
			val candidate = directory.o / "$prefix${platform.Foundation.NSUUID().UUIDString}"
			if (!fs.exists(candidate)) { fs.createDirectory(candidate, mustCreate = true); return candidate.wrap() }
		}
	}
	actual fun createTempFile(directory: Path, prefix: String, suffix: String): Path {
		while (true) {
			val candidate = directory.o / "$prefix${platform.Foundation.NSUUID().UUIDString}$suffix"
			if (!fs.exists(candidate)) { fs.write(candidate, mustCreate = true) {}; return candidate.wrap() }
		}
	}
	actual fun createLink(link: Path, existing: Path): Path {
		if (fs.exists(link.o)) throw FileAlreadyExistsException(link.toString())
		val ok = NSFileManager.defaultManager.linkItemAtPath(existing.toString(), toPath = link.toString(), error = null)
		if (!ok) throw IOException("Could not link $link")
		return link
	}

	actual fun mismatch(a: Path, b: Path): Long {
		val x = readAllBytes(a); val y = readAllBytes(b)
		for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return i.toLong()
		return if (x.size == y.size) -1L else minOf(x.size, y.size).toLong()
	}

	actual fun lastModifiedMillis(path: Path): Long = fs.metadata(path.o).lastModifiedAtMillis ?: 0L
	actual fun setLastModifiedMillis(path: Path, millis: Long) {
		NSFileManager.defaultManager.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)),
			ofItemAtPath = path.toString(), error = null)
	}
	actual fun isEmptyDirectory(path: Path): Boolean = fs.list(path.o).isEmpty()
}

actual fun gzipBytes(bytes: ByteArray): ByteArray {
	val out = okio.Buffer()
	okio.GzipSink(out).use { sink -> val b = okio.Buffer().write(bytes); sink.write(b, b.size) }
	return out.readByteArray()
}

actual fun gunzipBytes(gzipped: ByteArray, limit: Int): ByteArray {
	val source = okio.GzipSource(okio.Buffer().write(gzipped))
	val out = okio.Buffer()
	while (out.size < limit) { if (source.read(out, (limit - out.size).coerceAtMost(65536)) == -1L) break }
	return out.readByteArray(minOf(out.size, limit.toLong()))
}

actual fun defaultStoreRoot(): Path {
	val documents = platform.Foundation.NSSearchPathForDirectoriesInDomains(platform.Foundation.NSDocumentDirectory,
		platform.Foundation.NSUserDomainMask, true).first() as String
	return pathOf(documents, ".layerloom", "agent-workspaces")
}

actual fun encodeStoredPng(width: Int, height: Int, rgba: ByteArray): ByteArray = io.github.psd2live.format.compile.PngEncoder.encode(width, height,
	IntArray(width * height) { i -> val o = i * 4
		((rgba[o + 3].toInt() and 0xff) shl 24) or ((rgba[o].toInt() and 0xff) shl 16) or ((rgba[o + 1].toInt() and 0xff) shl 8) or (rgba[o + 2].toInt() and 0xff) })

actual fun decodeStoredPng(encoded: ByteArray, width: Int, height: Int): ByteArray? =
	runCatching { org.umamo.format.png.PngCodec.read(encoded) }.getOrNull()?.takeIf { it.width == width && it.height == height }?.rgba

actual fun decodeAnyPng(encoded: ByteArray, width: Int, height: Int): ByteArray? {
	val image = runCatching { org.umamo.format.png.PngCodec.read(encoded) }.getOrNull() ?: return null
	require(image.width == width && image.height == height) { "Raster dimensions mismatch" }
	return image.rgba
}

actual fun <K, V, R> loadInParallel(items: Map<K, V>, threads: Int, load: (V) -> R): Map<K, R> = items.mapValues { (_, item) -> load(item) }

actual fun maxMemoryBytes(): Long = platform.Foundation.NSProcessInfo.processInfo.physicalMemory.toLong() / 2

actual fun sleepMillis(millis: Long) { platform.posix.usleep((millis * 1000).toUInt()) }
