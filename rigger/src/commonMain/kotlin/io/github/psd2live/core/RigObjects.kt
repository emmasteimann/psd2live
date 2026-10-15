package io.github.psd2live.core

import okio.IOException

/**
 * Stored rig objects ([io.github.psd2live.format.compile.RigIrObjects]) by the SHA-256 of their bytes: those this
 * process made, held in memory until a folder that is read back holds them, and those folders hold. A project's
 * working store and an opened archive register their object folders (on the desktop, RigObjectFolders.kt); a save
 * copies what its revisions reference.
 */
object RigObjects {
	/** A place stored objects are read from: a project's object folder. [key] tells two registrations of one apart. */
	interface Folder {
		val key: Any
		/** The bytes stored under [hash], or null when this folder has none. */
		fun read(hash: String): ByteArray?
		fun has(hash: String): Boolean
	}

	internal val HASH = Regex("[0-9a-f]{64}")
	private val held = Collections.synchronizedMap(HashMap<String, ByteArray>())
	private val folders = CopyOnWriteArrayList<Folder>()

	/** Holds [bytes] and returns their hash. */
	fun put(bytes: ByteArray): String = sha256(bytes).also { held.putIfAbsent(it, bytes) }

	/** The object [hash] names, checked against it; [IOException] when no holder has it. */
	fun get(hash: String): ByteArray {
		require(hash.matches(HASH)) { "Invalid rig object name" }
		held[hash]?.let { return it }
		for (folder in folders) {
			val bytes = folder.read(hash) ?: continue
			if (sha256(bytes) != hash) throw IOException("Rig object checksum mismatch: $hash")
			return bytes
		}
		throw IOException("Rig object is missing: $hash")
	}

	fun has(hash: String): Boolean = hash.matches(HASH) && (held.containsKey(hash) || folders.any { it.has(hash) })

	/** Reads objects from [folder] too, before the folders registered earlier. */
	fun addFolder(folder: Folder) {
		if (folders.none { it.key == folder.key }) folders.add(0, folder)
	}

	/** The registered folders, newest first. */
	fun folders(): List<Folder> = folders.toList()

	/** The bytes held in memory for [hash], if any. */
	fun held(hash: String): ByteArray? = held[hash]

	/** Lets go of the bytes held for [hash] (a registered folder now holds them). */
	fun release(hash: String) { held.remove(hash) }

	/** Forgets held objects and folders: for tests that need a cold process. */
	fun clear() { held.clear(); folders.clear() }

	private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).let(::hex)
}
