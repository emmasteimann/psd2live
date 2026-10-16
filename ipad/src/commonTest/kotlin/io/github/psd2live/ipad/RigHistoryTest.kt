package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.project.Files
import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.project.ProjectSaveCapture
import io.github.psd2live.project.WorkspaceStore
import io.github.psd2live.project.resolve
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A project's history survives iPad saves (it used to start over each save), records each kept change, and branches. */
class RigHistoryTest {
	@Test fun historyIsKeptThroughSaveAndGoingBackBranches() = kotlinx.coroutines.runBlocking {
		val p = okio.Path.Companion.run { "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd".toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return@runBlocking
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val model = PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false))
		fun outcome(m: io.github.psd2live.core.RigPreviewModel) = RigOutcome("boo.psd", 0, 0, 0, emptyList(), "", emptyList(), 0.0, RigPreview(m), source, m.config, m)
		val first = outcome(model)
		val h = RigProjectHistory.fresh(first)
		// A kept change: a draw order.
		val e = RigEditing(model, source)
		RigDrawOrder.set(e, RigDrawOrder.list(e).last().layerId, 999f)
		h.commit(outcome(e.model), "Changed draw order")
		h.commit(outcome(e.model), "Same again") // nothing changed: no node
		assertEquals(listOf("Workspace opened", "Changed draw order"), h.tree.nodes().map { it.summary })
		// Saved and reopened, the history is all there.
		val dir = Files.createTempDirectory("history-test-")
		val file = dir.resolve("boo.psd2live")
		ProjectRepository().save(ProjectSaveCapture(h.projectId, h.tree.state(), h.presentation, io.github.psd2live.project.pathOf("/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"), WorkspaceStore(dir.resolve("store"))), file)
		val opened = ProjectRepository().open(file)
		try {
			assertEquals(h.projectId, opened.projectId, "the project keeps its id")
			assertEquals(listOf("Workspace opened", "Changed draw order"), opened.history.nodes().map { it.summary })
			// Going back to the start, then a different change: a branch, nothing lost.
			val back = RigProjectHistory(opened.projectId, opened.history, opened.presentation)
			val root = back.tree.nodes().first { it.parentId == null }
			back.tree.checkout(root.id)
			val e2 = RigEditing(model, source)
			RigDrawOrder.set(e2, RigDrawOrder.list(e2).first().layerId, 0f)
			back.commit(outcome(e2.model), "Other draw order")
			assertEquals(3, back.tree.nodes().size)
			assertTrue(back.tree.childrenOf(root.id).size == 2, "two branches from the start")
		} finally { opened.close(); RigFiles.deleteTree(dir.toString()) }
	}
}
