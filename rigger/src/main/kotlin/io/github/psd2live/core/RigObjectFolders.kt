package io.github.psd2live.core
import io.github.psd2live.project.resolve
import io.github.psd2live.project.toAbsolutePath
import io.github.psd2live.project.normalize
import io.github.psd2live.project.Files
import io.github.psd2live.project.Path

import okio.IOException

// The desktop's rig object folders: a project's object folder on disk, read by RigObjects (shared).

/** A folder of `HASH.bin` files. */
class RigObjectPathFolder(folder: Path) : RigObjects.Folder {
	val path: Path = folder.toAbsolutePath().normalize()
	override val key: Any get() = path
	override fun read(hash: String): ByteArray? = path.resolve("$hash.bin").takeIf { Files.isRegularFile(it) }?.let(Files::readAllBytes)
	override fun has(hash: String): Boolean = Files.isRegularFile(path.resolve("$hash.bin"))
}

/** Reads objects from [folder] too (an archive's or a working store's object folder). */
fun RigObjects.addFolder(folder: Path) = addFolder(RigObjectPathFolder(folder))

/**
 * Writes the objects [hashes] name into [folder], linking or copying those another folder has. Objects held in
 * memory that [folder] - a registered folder - now holds are let go.
 */
fun RigObjects.writeTo(folder: Path, hashes: Collection<String>) {
	Files.createDirectories(folder)
	val normalized = folder.toAbsolutePath().normalize()
	val registered = folders().any { it.key == normalized }
	for (hash in hashes.toSet()) {
		require(hash.matches(HASH)) { "Invalid rig object name" }
		val target = folder.resolve("$hash.bin")
		if (!Files.exists(target)) {
			val bytes = held(hash)
			if (bytes != null) Files.write(target, bytes) else {
				val source = folders().filterIsInstance<RigObjectPathFolder>().map { it.path.resolve("$hash.bin") }
					.firstOrNull { it != target && Files.isRegularFile(it) }
					?: throw IOException("Rig object is missing: $hash")
				try { Files.createLink(target, source) } catch (_: Exception) { Files.copy(source, target) }
			}
		}
		if (registered) release(hash)
	}
}
