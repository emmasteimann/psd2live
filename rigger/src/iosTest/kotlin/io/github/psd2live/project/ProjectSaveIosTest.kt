package io.github.psd2live.project

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.history.WorkspaceHistoryTree
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A project saved on the iPad: quill.psd (LAYERLOOM_PSD) as a .psd2live through the desktop's ProjectRepository, opened
 * back. With LAYERLOOM_DUMP it is also left there for the desktop to open. Skipped without LAYERLOOM_PSD.
 */
class ProjectSaveIosTest {
	@OptIn(ExperimentalForeignApi::class)
	@Test
	fun aProjectSavesAndOpensOnTheIpad() = runBlocking {
		val psd = platform.posix.getenv("LAYERLOOM_PSD")?.toKString()?.takeIf { it.isNotBlank() } ?: return@runBlocking println("LAYERLOOM_PSD not set: skipped")
		val source = PsdReader.read(FileSystem.SYSTEM.read(psd.toPath()) { readByteArray() })
		val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty,
			WorkspaceSettingsCodec.encode(PipelineConfig()))
		val revision = WorkspaceRevisions.of(document)
		val tree = WorkspaceHistoryTree(document, revision, revision)
		val work = Files.createTempDirectory("save-test-")
		val file = work.resolve("quill.psd2live")
		ProjectRepository(writeHeadCache = false).save(ProjectSaveCapture("project-ipad-test", tree.state(), JsonObject(emptyMap()),
			pathOf(psd), WorkspaceStore(work.resolve("store"))), file)
		platform.posix.getenv("LAYERLOOM_DUMP")?.toKString()?.let { dump -> Files.copy(file, pathOf(dump, "ipad-quill.psd2live"), StandardCopyOption.REPLACE_EXISTING) }
		ProjectRepository().open(file).use { opened ->
			assertEquals(revision, WorkspaceRevisions.of(opened.history.head().snapshot))
			println("saved ${Files.size(file)} bytes; reopened revision $revision")
		}
	}
}
