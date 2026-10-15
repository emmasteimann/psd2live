package io.github.psd2live.project

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.history.WorkspaceHistoryTree
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.FileSystem
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The workspace store on the iPad: a real document's history (LAYERLOOM_PSD) persisted to disk and loaded back - the
 * layers' pixels (content-addressed PNG blobs), names and settings as they went in. Skipped without LAYERLOOM_PSD.
 */
class WorkspaceStoreIosTest {
	@OptIn(ExperimentalForeignApi::class)
	@Test
	fun aDocumentsHistorySavesAndLoads() {
		val psd = platform.posix.getenv("LAYERLOOM_PSD")?.toKString()?.takeIf { it.isNotBlank() } ?: return println("LAYERLOOM_PSD not set: skipped")
		val source = PsdReader.read(FileSystem.SYSTEM.read(psd.toPath()) { readByteArray() })
		val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty,
			WorkspaceSettingsCodec.encode(PipelineConfig()))
		val revision = WorkspaceRevisions.of(document)
		val tree = WorkspaceHistoryTree(document, revision, revision)
		val root = Files.createTempDirectory("store-test-")
		val store = WorkspaceStore(root)
		store.persistHistory("project-ios", tree.state())
		val loaded = requireNotNull(WorkspaceStore(root).loadHistory("project-ios")) { "nothing loaded" }
		val back = loaded.head().snapshot
		assertEquals(revision, WorkspaceRevisions.of(back), "the loaded document is the saved one")
		assertEquals(document.source.layers.map { it.name }, back.source.layers.map { it.name })
		for ((a, b) in document.source.layers.zip(back.source.layers)) assertContentEquals(a.raster.rgba, b.raster.rgba, a.name)
		println("store: ${Files.walk(root).filter { Files.isRegularFile(it) }.toList().size} files for ${source.layers.size} layers")
	}
}
