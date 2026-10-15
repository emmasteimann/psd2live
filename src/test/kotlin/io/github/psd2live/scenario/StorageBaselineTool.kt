package io.github.psd2live.scenario

import io.github.psd2live.core.PipelineConfig
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.Test

/**
 * A dev tool (LAYERLOOM_STORAGE_BASELINE=<out file>, LAYERLOOM_PSD=<psd>): imports the PSD, saves it twice, then writes what the store and the archive hold - every file's path and content hash - with random ids and
 * times masked. Run before and after a storage change: the two files must be equal.
 */
class StorageBaselineTool {
	@Test
	fun dump() {
		val out = System.getenv("LAYERLOOM_STORAGE_BASELINE") ?: return
		val psd = Path.of(System.getenv("LAYERLOOM_PSD") ?: return)
		val temp = Files.createTempDirectory("storage-baseline")
		val lines = sortedSetOf<String>()
		Studio.run(temp, checks = emptySet()) {
			importPsd(psd, PipelineConfig())
			// (Not reopen: Studio's reopen check fails on real art, before and after the storage work - a separate issue.)
			val file = save()
			val second = save("save again")
			for (archive in listOf(file, second)) ZipFile(archive.toFile()).use { zip ->
				for (entry in zip.entries()) if (!entry.isDirectory) lines += "${archive.fileName.toString().replace(Regex("\\d+"), "N")} ${maskNode(entry.name)} " +
					hash(maskVolatile(entry.name, zip.getInputStream(entry).readBytes()))
			}
			Files.walk(temp).use { paths -> paths.filter(Files::isRegularFile).forEach { f ->
				val rel = temp.relativize(f).toString()
				if (!rel.startsWith("store-")) return@forEach
				lines += "store ${maskNode(rel).replace(Regex("store-\\d+"), "store-N")} ${hash(maskVolatile(rel, Files.readAllBytes(f)))}"
			} }
		}
		java.io.File(out).writeText(lines.joinToString("\n") + "\n")
		temp.toFile().deleteRecursively()
	}

	private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
	private val instant = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")
	private fun mask(text: String) = text.replace(uuid, "UUID").replace(instant, "TIME")
	private fun mask(bytes: ByteArray): ByteArray {
		val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return bytes
		return mask(text).encodeToByteArray()
	}
	/** History node files are named by a hash of their random id; the manifest lists every file's digest. */
	private val hex64 = Regex("[0-9a-f]{64}")
	private fun maskNode(name: String) = mask(if ("history" in name) name.replace(hex64, "NODE") else name)
	private fun maskVolatile(name: String, bytes: ByteArray): ByteArray {
		val masked = mask(bytes)
		return if (name.endsWith("manifest.json") || "history" in name) masked.decodeToString().replace(hex64, "H").encodeToByteArray() else masked
	}

	private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
}
