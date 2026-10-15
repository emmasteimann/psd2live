package io.github.psd2live.ipad.studio

import io.github.psd2live.ipad.RigFiles
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Where the studio keeps projects on the iPad: the web studio's three IndexedDB slots as files in Documents/Studio -
 * `current` (autosaved after every change), `recovery` ("Save recovery checkpoint") and `previous` (the project before
 * the last import or open, for "Back to previous project"). Each is the project's own JSON, written to a temporary file
 * and moved over the old one, so a crash mid-save leaves the last good copy.
 */
object StudioStore {
	enum class Slot(val file: String) { CURRENT("current.json"), RECOVERY("recovery.json"), PREVIOUS("previous.json") }

	private val fs = FileSystem.SYSTEM
	val folder: Path get() = (RigFiles.psdPath("").trimEnd('/') + "/Studio").toPath()

	fun exists(slot: Slot): Boolean = fs.exists(folder / slot.file)

	fun load(slot: Slot): StudioProject? {
		val path = folder / slot.file
		if (!fs.exists(path)) return null
		return try { StudioProject.parse(fs.read(path) { readUtf8() }, requireFormat = false) } catch (_: Throwable) { null }
	}

	/** When [slot] was saved (the field the web adds: `checkpointAt` / `replacedAt`), or null. */
	fun savedAt(slot: Slot, field: String): String? = load(slot)?.json?.str(field)

	fun save(project: StudioProject, slot: Slot) = writeText(folder / slot.file, project.json.toString())

	/** Text saved to a slot as it is (the web studio's copy, already JSON). */
	fun saveRaw(text: String, slot: Slot) = writeText(folder / slot.file, text)

	fun delete(slot: Slot) { fs.delete(folder / slot.file, mustExist = false) }

	/** A saved project file for the share sheet: Documents/Studio/Exports/NAME. */
	fun export(name: String, data: ByteArray): String {
		val path = folder / "Exports" / name
		fs.createDirectories(path.parent!!)
		val temp = path.parent!! / "$name.tmp"
		fs.write(temp) { write(data) }
		fs.atomicMove(temp, path)
		return path.toString()
	}

	fun readFile(path: String): ByteArray = fs.read(path.toPath()) { readByteArray() }

	private fun writeText(path: Path, text: String) {
		fs.createDirectories(path.parent!!)
		val temp = path.parent!! / "${path.name}.tmp"
		fs.write(temp) { writeUtf8(text) }
		fs.atomicMove(temp, path)
	}

	/** Set once the web studio's saved projects have been copied over (or there were none). */
	private val migrated: Path get() = folder / ".copied-from-web"
	var copiedFromWeb: Boolean
		get() = fs.exists(migrated)
		set(value) { if (value) writeText(migrated, nowIso()) else fs.delete(migrated, mustExist = false) }
}
