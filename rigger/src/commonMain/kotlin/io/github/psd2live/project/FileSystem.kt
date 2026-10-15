package io.github.psd2live.project

import okio.IOException

// The file system for project storage in shared code (Layerloom iPad plan, phase 5). The names are Java's own so the
// storage code reads as it did: on the desktop Path *is* java.nio.file.Path (a typealias) and every Files function
// calls java.nio.file.Files with the same options (FileSystemJvm.kt); on the iPad they are okio's (FileSystemIos.kt).

/** A file system path (java.nio.file.Path on the desktop). */
expect interface Path : Comparable<Path>

/** Path.of(first, more...). */
expect fun pathOf(first: String, vararg more: String): Path

expect fun Path.resolve(other: String): Path
expect fun Path.resolve(other: Path): Path
expect fun Path.resolveSibling(other: String): Path
expect val Path.parent: Path?
expect val Path.fileName: Path?
expect val Path.isAbsolute: Boolean
expect fun Path.toAbsolutePath(): Path
expect fun Path.normalize(): Path
expect fun Path.startsWith(other: Path): Boolean
expect fun Path.relativize(other: Path): Path
expect fun Path.toRealPath(): Path

/** java.nio.file.StandardCopyOption's. */
enum class StandardCopyOption { REPLACE_EXISTING, ATOMIC_MOVE, COPY_ATTRIBUTES }

/** java.nio.file.StandardOpenOption's that storage uses. */
enum class StandardOpenOption { READ, WRITE, APPEND, CREATE, CREATE_NEW, TRUNCATE_EXISTING }

/** java.nio.file's exceptions storage tells apart (Java's own on the desktop). */
expect open class AtomicMoveNotSupportedException : IOException
expect open class AccessDeniedException : IOException
expect open class FileAlreadyExistsException : IOException
expect open class NoSuchFileException : IOException

/**
 * What Files.walk / Files.list give: the paths, closed after use (`Files.list(dir).use { it.filter(...).toList() }`).
 * Listed eagerly; the order is the platform's listing order, as a Java stream's.
 */
class PathStream<T>(private val items: List<T>) : AutoCloseable {
	fun filter(predicate: (T) -> Boolean): PathStream<T> = PathStream(items.filter(predicate))
	fun <R> map(transform: (T) -> R): PathStream<R> = PathStream(items.map(transform))
	fun sorted(comparator: Comparator<in T>): PathStream<T> = PathStream(items.sortedWith(comparator))
	fun forEach(action: (T) -> Unit) = items.forEach(action)
	fun toList(): List<T> = items
	operator fun iterator(): Iterator<T> = items.iterator()
	override fun close() {}
}

fun <T : Comparable<T>> PathStream<T>.sorted(): PathStream<T> = sorted(naturalOrder())

/** java.nio.file.Files for project storage. */
expect object Files {
	fun exists(path: Path): Boolean
	fun isRegularFile(path: Path): Boolean
	fun isDirectory(path: Path): Boolean
	fun createDirectories(path: Path): Path
	fun readAllBytes(path: Path): ByteArray
	fun readString(path: Path): String
	fun write(path: Path, bytes: ByteArray, vararg options: StandardOpenOption): Path
	fun writeString(path: Path, text: String, vararg options: StandardOpenOption): Path
	fun move(source: Path, target: Path, vararg options: StandardCopyOption): Path
	fun copy(source: Path, target: Path, vararg options: StandardCopyOption): Path
	fun delete(path: Path)
	fun deleteIfExists(path: Path): Boolean
	fun size(path: Path): Long
	fun list(directory: Path): PathStream<Path>
	fun walk(start: Path): PathStream<Path>
	fun createTempDirectory(prefix: String): Path
	fun createTempDirectory(directory: Path, prefix: String): Path
	fun createTempFile(directory: Path, prefix: String, suffix: String): Path
	fun createLink(link: Path, existing: Path): Path
	/** Files.mismatch: the first differing byte's offset, or -1 when the files are equal. */
	fun mismatch(a: Path, b: Path): Long
	fun lastModifiedMillis(path: Path): Long
	fun setLastModifiedMillis(path: Path, millis: Long)
	fun isEmptyDirectory(path: Path): Boolean
}

/** [bytes] gzip-compressed (java.util.zip.GZIPOutputStream on the desktop, so stored files stay byte-identical). */
expect fun gzipBytes(bytes: ByteArray): ByteArray

/** The first [limit] bytes [gzipped] decompresses to (GZIPInputStream.readNBytes on the desktop). */
expect fun gunzipBytes(gzipped: ByteArray, limit: Int): ByteArray

/** Where workspaces live by default: the psd2live.agent.store property, else the user's folder (desktop); Documents (iPad). */
expect fun defaultStoreRoot(): Path

/** [rgba] (straight alpha, [width] × [height]) as the PNG the store keeps (ImageIO's on the desktop). */
expect fun encodeStoredPng(width: Int, height: Int, rgba: ByteArray): ByteArray

/** The store's own RGBA PNG decoded directly (PngRgbaDecoder on the desktop); null when it is not one of those. */
expect fun decodeStoredPng(encoded: ByteArray, width: Int, height: Int): ByteArray?

/** Any PNG as straight RGBA of [width] × [height] (ImageIO on the desktop); null when it cannot be read. */
expect fun decodeAnyPng(encoded: ByteArray, width: Int, height: Int): ByteArray?

/** [load] for each value of [items] on [threads] threads, results by key (a fixed thread pool on the desktop). */
expect fun <K, V, R> loadInParallel(items: Map<K, V>, threads: Int, load: (V) -> R): Map<K, R>

/** The heap the process may use (Runtime.maxMemory on the desktop). */
expect fun maxMemoryBytes(): Long

expect fun sleepMillis(millis: Long)

/** Copies [source] to [target] (created or replaced), returning the copied bytes' SHA-256 in hex. */
expect fun copyWithSha256(source: Path, target: Path): String
