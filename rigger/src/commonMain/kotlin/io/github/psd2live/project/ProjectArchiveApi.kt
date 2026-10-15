package io.github.psd2live.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A project archive (`.psd2live`, a zip with a manifest) as shared code uses it. The desktop's is java.util.zip with its
 * digest cache, unchanged (ProjectArchive.kt); the iPad's reads with okio's zip file system and writes its own zip.
 */
expect object ProjectArchive {
	val json: Json
	/** Versions [extract] opens. */
	val readableVersions: Set<Int>
	fun writeJson(path: Path, value: JsonElement)
	fun readJson(path: Path): JsonObject
	/** Entries written without compression: their content is compressed already. */
	fun isStored(name: String): Boolean
	/** Copies [source] to [target] keeping its modification time. */
	fun copyKeepingTime(source: Path, target: Path)
	fun copyKeepingTime(source: Path, target: Path, option: StandardCopyOption, vararg more: StandardCopyOption)
	/** The SHA-256 of [file] as the archive's manifest records it under [name]. */
	fun sourceDigest(name: String, file: Path): String
	/** Packs [directory] into [target] (manifest last, read back before it replaces [target]). */
	fun write(directory: Path, target: Path, projectId: String, version: Int = PROJECT_FORMAT_VERSION,
	          progress: (Double) -> Unit = {}, beforeReplace: () -> Unit = {})
	/** Unpacks [file] into a new temporary directory, checking it against its manifest. */
	fun extract(file: Path, progress: (Double) -> Unit = {}): Path
	fun deleteTemporaryDirectory(root: Path)
}

/** The project format saves write (ProjectFormatV2.VERSION). */
const val PROJECT_FORMAT_VERSION = 2
