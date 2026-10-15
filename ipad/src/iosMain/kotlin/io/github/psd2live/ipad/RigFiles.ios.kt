package io.github.psd2live.ipad

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970

actual object RigFiles {
	private val documents: String
		get() = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

	actual fun psds(): List<String> {
		val root = documents.toPath()
		return FileSystem.SYSTEM.list(root).filter { it.name.lowercase().endsWith(".psd") }.map { it.name }.sorted()
	}

	actual fun read(name: String): ByteArray = FileSystem.SYSTEM.read("$documents/$name".toPath()) { readByteArray() }

	actual fun folderFor(psdName: String): String {
		val safe = psdName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]+"), "_").trim().ifEmpty { "rig" }
		val folder = "$documents/Rigs/$safe".toPath()
		FileSystem.SYSTEM.deleteRecursively(folder)
		FileSystem.SYSTEM.createDirectories(folder)
		return folder.toString()
	}

	actual fun write(folder: String, relative: String, data: ByteArray) {
		require(relative.split('/').none { it == ".." || it.isEmpty() }) { "Unsafe file name: $relative" }
		val target = "$folder/$relative".toPath()
		target.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
		FileSystem.SYSTEM.write(target) { write(data) }
	}

	actual fun psdPath(name: String): String = "$documents/$name"

	actual fun projectPath(psdName: String): String {
		val safe = psdName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]+"), "_").trim().ifEmpty { "rig" }
		FileSystem.SYSTEM.createDirectories("$documents/Rigs".toPath())
		return "$documents/Rigs/$safe.psd2live"
	}

	actual fun deleteTree(path: String) = FileSystem.SYSTEM.deleteRecursively(path.toPath())

	actual fun nowSeconds(): Double = NSDate().timeIntervalSince1970
}
