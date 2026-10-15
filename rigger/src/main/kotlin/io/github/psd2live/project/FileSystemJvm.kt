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
