package io.github.psd2live.project

import io.github.psd2live.core.AtlasPagePng
import io.github.psd2live.core.MessageDigest
import io.github.psd2live.core.hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/**
 * The iPad's project archive: the desktop's format (entries sorted by name, the manifest last with each file's SHA-256,
 * already-compressed files STORED, the rest DEFLATEd), written by a small streaming zip writer and read with okio's zip
 * file system. As on the desktop the written archive is read back before it replaces the target.
 */
actual object ProjectArchive {
	actual val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
	private const val MANIFEST = "manifest.json"
	actual val readableVersions: Set<Int> = setOf(1, PROJECT_FORMAT_VERSION)
	private val fs = FileSystem.SYSTEM
	private val Path.o: okio.Path get() = (this as OkioPath).okio

	actual fun writeJson(path: Path, value: JsonElement) {
		Files.createDirectories(path.parent!!)
		Files.writeString(path, json.encodeToString(JsonElement.serializer(), value))
	}

	actual fun readJson(path: Path): JsonObject = json.parseToJsonElement(Files.readString(path)).jsonObject

	actual fun isStored(name: String): Boolean = name.endsWith(".png", ignoreCase = true) || name.endsWith(".cmo3", ignoreCase = true)

	actual fun copyKeepingTime(source: Path, target: Path) {
		Files.copy(source, target)
		Files.setLastModifiedMillis(target, Files.lastModifiedMillis(source))
	}

	actual fun copyKeepingTime(source: Path, target: Path, option: StandardCopyOption, vararg more: StandardCopyOption) {
		Files.copy(source, target, option, *more)
		Files.setLastModifiedMillis(target, Files.lastModifiedMillis(source))
	}

	private class Digest(val sha256: String, val crc: Long, val size: Long)

	/** Streams [path] through SHA-256 and CRC-32, handing each chunk to [chunk]. */
	private fun digest(path: Path, chunk: (ByteArray, Int) -> Unit = { _, _ -> }): Digest {
		val sha = MessageDigest.getInstance("SHA-256")
		var crc = 0L
		var size = 0L
		val buffer = ByteArray(1 shl 16)
		fs.source(path.o).buffer().use { source ->
			while (true) {
				val n = source.read(buffer, 0, buffer.size); if (n <= 0) break
				sha.update(buffer, 0, n)
				crc = AtlasPagePng.crc32(if (n == buffer.size) buffer else buffer.copyOf(n), crc)
				size += n
				chunk(buffer, n)
			}
		}
		return Digest(hex(sha.digest()), crc, size)
	}

	actual fun sourceDigest(name: String, file: Path): String = digest(file).sha256

	actual fun write(directory: Path, target: Path, projectId: String, version: Int, progress: (Double) -> Unit, beforeReplace: () -> Unit) {
		val files = Files.walk(directory).filter { Files.isRegularFile(it) }.toList()
			.map { directory.relativize(it).toString().replace('\\', '/') to it }
			.filter { it.first != MANIFEST }.sortedBy { it.first }
		val total = files.sumOf { Files.size(it.second) }.coerceAtLeast(1L).toDouble()
		var done = 0L
		val destination = target.toAbsolutePath().normalize()
		Files.createDirectories(destination.parent!!)
		val temporary = Files.createTempFile(destination.parent!!, ".psd2live-", ".tmp")
		try {
			val written = LinkedHashMap<String, Digest>()
			val zip = ZipWriter(fs.sink(temporary.o).buffer())
			for ((name, file) in files) {
				val before = done
				if (isStored(name)) {
					val d = digest(file)
					zip.stored(name, d.crc, d.size) { out -> digest(file) { b, n -> out.write(b, 0, n); done = before; progress(0.8 * (before + n) / total) } }
					written[name] = d
				} else {
					var d: Digest? = null
					zip.deflated(name) { out -> d = digest(file) { b, n -> out.write(b, 0, n) }; Triple(d!!.crc, d!!.size, Unit) }
					written[name] = d!!
				}
				done = before + written.getValue(name).size
				progress(0.8 * done / total)
			}
			val manifest = json.encodeToString(JsonElement.serializer(), buildJsonObject {
				put("format", "PSD2Live"); put("version", version); put("projectId", projectId)
				putJsonObject("files") { written.forEach { (name, digest) -> put(name, digest.sha256) } }
			}).encodeToByteArray()
			zip.deflated(MANIFEST) { out -> out.write(manifest); Triple(AtlasPagePng.crc32(manifest), manifest.size.toLong(), Unit) }
			zip.finish()
			verify(temporary, written, manifest) { read -> progress(0.8 + 0.2 * (read / total).coerceAtMost(1.0)) }
			beforeReplace()
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		} finally { Files.deleteIfExists(temporary) }
	}

	/** Exactly the written entries and the manifest, each with its length and CRC. */
	private fun verify(archive: Path, expected: Map<String, Digest>, manifest: ByteArray, counted: (Long) -> Unit) {
		val zip = fs.openZip(archive.o)
		val names = zip.listRecursively("/".toPath()).filter { zip.metadata(it).isRegularFile }.map { it.toString().removePrefix("/") }.toList()
		require(names.size == expected.size + 1 && MANIFEST in names) { "Project archive is missing entries" }
		var read = 0L
		for (name in names) {
			val bytes = zip.read(("/$name").toPath()) { readByteArray() }
			read += bytes.size; counted(read)
			if (name == MANIFEST) require(bytes.contentEquals(manifest)) { "Project manifest does not match what was written" }
			else {
				val digest = requireNotNull(expected[name]) { "Unexpected project entry: $name" }
				require(bytes.size.toLong() == digest.size && AtlasPagePng.crc32(bytes) == digest.crc) { "Project entry does not match what was written: $name" }
			}
		}
	}

	actual fun extract(file: Path, progress: (Double) -> Unit): Path {
		val root = Files.createTempDirectory("psd2live-project-").toAbsolutePath().normalize()
		try {
			val zip = fs.openZip(file.o)
			val entries = zip.listRecursively("/".toPath()).filter { zip.metadata(it).isRegularFile }.toList()
			val declared = entries.sumOf { zip.metadata(it).size ?: 0L }.coerceAtLeast(1L).toDouble()
			val names = mutableSetOf<String>()
			val folded = HashSet<String>()
			val digests = HashMap<String, String>()
			var total = 0L
			for (entry in entries) {
				val name = entry.toString().removePrefix("/")
				require(name.isNotBlank() && !name.contains('\\') && !name.contains(':') && name.split('/').none { it == ".." || it == "." }) { "Invalid project entry: $name" }
				require(name.split('/').none { it.endsWith('.') || it.endsWith(' ') }) { "Invalid project entry: $name" }
				require(names.add(name) && folded.add(name.lowercase())) { "Duplicate project entry: $name" }
				require(names.size <= 1_000_000) { "Too many project entries" }
				val path = root.resolve(name).normalize()
				require(path.startsWith(root)) { "Project entry escapes archive" }
				Files.createDirectories(path.parent!!)
				val sha = MessageDigest.getInstance("SHA-256")
				val buffer = ByteArray(1 shl 16)
				zip.source(entry).buffer().use { source -> fs.sink(path.o).buffer().use { sink ->
					while (true) {
						val n = source.read(buffer, 0, buffer.size); if (n <= 0) break
						sha.update(buffer, 0, n); sink.write(buffer, 0, n)
						total += n; progress((total / declared).coerceAtMost(1.0))
						require(total <= 64L * 1024 * 1024 * 1024) { "Project exceeds 64 GiB unpacked limit" }
					}
				} }
				digests[name] = hex(sha.digest())
			}
			val manifest = readJson(root.resolve(MANIFEST))
			require(manifest["format"]?.jsonPrimitive?.content == "PSD2Live" && manifest["version"]?.jsonPrimitive?.intOrNull in readableVersions) {
				"Unsupported project format/version"
			}
			val inventory = manifest.getValue("files").jsonObject
			require(names.filterNot { it == MANIFEST }.toSet() == inventory.keys) { "Project inventory mismatch" }
			inventory.forEach { (name, hash) -> require(digests.getValue(name) == hash.jsonPrimitive.content) { "Project resource checksum mismatch: $name" } }
			return root
		} catch (failure: Throwable) { deleteTemporaryDirectory(root); throw failure }
	}

	/** Only directories this subsystem made in the temporary directory. */
	actual fun deleteTemporaryDirectory(root: Path) {
		val path = root.toAbsolutePath().normalize()
		val temp = platform.Foundation.NSTemporaryDirectory().toPath().normalized()
		require(path.o.parent?.normalized() == temp && path.fileName.toString().startsWith("psd2live-project-")) { "Not a project temporary directory: $root" }
		if (fs.exists(path.o)) fs.deleteRecursively(path.o)
	}
}

/**
 * A minimal streaming zip writer (no zip64): STORED entries with known CRC and size, DEFLATED entries with a data
 * descriptor written after the data. Every reader uses the central directory written by [finish].
 */
private class ZipWriter(private val out: okio.BufferedSink) {
	private class Central(val name: ByteArray, val method: Int, val flags: Int, val crc: Long, val compressed: Long, val size: Long, val offset: Long)
	private val entries = ArrayList<Central>()
	private var offset = 0L

	private fun u16(v: Int) { out.writeShortLe(v); offset += 2 }
	private fun u32(v: Long) { out.writeIntLe(v.toInt()); offset += 4 }
	private fun bytes(b: ByteArray) { out.write(b); offset += b.size }

	private fun localHeader(name: ByteArray, method: Int, flags: Int, crc: Long, compressed: Long, size: Long) {
		u32(0x04034b50); u16(20); u16(flags); u16(method); u16(0); u16(0x21) // version, flags, method, time, date (1980-01-01)
		u32(crc); u32(compressed); u32(size); u16(name.size); u16(0); bytes(name)
	}

	fun stored(name: String, crc: Long, size: Long, write: (okio.BufferedSink) -> Unit) {
		val n = name.encodeToByteArray(); val at = offset
		localHeader(n, 0, 0x800, crc, size, size)
		write(out); offset += size
		entries += Central(n, 0, 0x800, crc, size, size, at)
	}

	fun deflated(name: String, write: (okio.BufferedSink) -> Triple<Long, Long, Unit>) {
		val n = name.encodeToByteArray(); val at = offset
		localHeader(n, 8, 0x808, 0, 0, 0)
		val compressed = Buffer()
		val deflater = okio.DeflaterSink(compressed, okio.Deflater(-1, true))
		val sink = deflater.buffer()
		val (crc, size, _) = write(sink)
		sink.close()
		val clen = compressed.size
		out.writeAll(compressed); offset += clen
		u32(0x08074b50); u32(crc); u32(clen); u32(size)
		entries += Central(n, 8, 0x808, crc, clen, size, at)
	}

	fun finish() {
		val start = offset
		for (e in entries) {
			u32(0x02014b50); u16(20); u16(20); u16(e.flags); u16(e.method); u16(0); u16(0x21)
			u32(e.crc); u32(e.compressed); u32(e.size); u16(e.name.size); u16(0); u16(0); u16(0); u16(0); u32(0); u32(e.offset); bytes(e.name)
		}
		val size = offset - start
		u32(0x06054b50); u16(0); u16(0); u16(entries.size); u16(entries.size); u32(size); u32(start); u16(0)
		out.close()
	}
}
