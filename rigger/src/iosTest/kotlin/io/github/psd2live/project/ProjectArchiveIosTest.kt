package io.github.psd2live.project

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The iPad's archive round-trips a project folder, STORED and DEFLATEd entries alike, and checks its manifest. */
class ProjectArchiveIosTest {
	@OptIn(ExperimentalForeignApi::class)
	@Test
	fun writeThenExtractGivesTheSameFiles() {
		val root = Files.createTempDirectory("archive-test-")
		val files = mapOf(
			"blobs/a.png" to Random(1).nextBytes(70_000),
			"document/nodes/x.json" to "{\"hello\": \"world\", \"n\": [1, 2, 3]}".repeat(200).encodeToByteArray(),
			"source/quill.psd" to ByteArray(300_000) { (it % 7).toByte() },
			"workspace.json" to "{}".encodeToByteArray(),
		)
		for ((name, bytes) in files) { val p = root.resolve(name); Files.createDirectories(p.parent!!); Files.write(p, bytes) }
		val target = root.parent!!.resolve("archive-test-${Random.nextInt(1_000_000)}.psd2live")
		var last = 0.0
		ProjectArchive.write(root, target, "project-test", progress = { last = it })
		assertEquals(1.0, last, 1e-9)
		platform.posix.getenv("LAYERLOOM_DUMP")?.toKString()?.let { dump -> Files.copy(target, pathOf(dump, "ios-archive.psd2live"), StandardCopyOption.REPLACE_EXISTING) }
		val extracted = ProjectArchive.extract(target)
		try {
			for ((name, bytes) in files) assertContentEquals(bytes, Files.readAllBytes(extracted.resolve(name)), name)
			val manifest = ProjectArchive.readJson(extracted.resolve("manifest.json"))
			assertEquals("project-test", manifest.getValue("projectId").toString().trim('"'))
		} finally {
			ProjectArchive.deleteTemporaryDirectory(extracted)
			Files.deleteIfExists(target)
		}
	}
}
