package io.github.psd2live.application
import io.github.psd2live.core.export
import io.github.psd2live.core.besidePsd
import io.github.psd2live.core.read

import io.github.psd2live.core.LayerloomJob
import io.github.psd2live.core.LayerloomRigOptions
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import org.umamo.format.psd.PsdReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layerloom patch (ONE-APP-PLAN S1): a Layerloom rig job built the way the editor's Import PSD builds it, then
 * saved and reopened, keeps its build options and exports a rig to compare against the CLI's. Needs a real
 * job: LAYERLOOM_PARITY_DIR holding `quill.psd` and `quill.layerloom.json`; skipped without it. The export goes
 * to `editor-out/` there for pose-render comparison (Layerloom's scripts/rig-render.py).
 */
class LayerloomEditorParityTest {
	@Test
	fun editorImportBuildsTheJobAndASavedProjectKeepsIt() = runBlocking {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return@runBlocking
		val psd = Path.of(dir, "quill.psd")
		val job = LayerloomJob.read(requireNotNull(LayerloomJob.besidePsd(psd)) { "no quill.layerloom.json beside the PSD" })
		val source = PsdReader.read(Files.readAllBytes(psd))
		val overrides = LayerloomJob.toggleOverrides(source, job.toggles)
		// As WorkspaceSourceImporter.install prepares a new source project's settings.
		val config = job.configure(PipelineConfig()).copy(layerOverrides = overrides, hairSimulationFront = false, hairSimulationBack = false)
		val document = WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), RigEditOverlay.Empty,
			WorkspaceSettingsCodec.encode(config))
		val builder = WorkspacePreviewBuilder()
		val built = LayerloomJob.setUp(builder.build(document), config) { next -> builder.build(document.copy(rigEdits = next.rigEdits)) }
		if (job.skeleton != null) assertTrue(built.config.rigEdits.skeleton?.enabled == true, "the skeleton is a stored rig edit")
		if (job.swings.isNotEmpty()) assertTrue(built.config.rigEdits != RigEditOverlay.Empty, "swings are stored rig edits")

		// Saved and reopened: the build options come back from the settings text, and the one-time setup from the edits.
		val saved = document.copy(rigEdits = built.config.rigEdits)
		val reopenedSettings = WorkspaceSettingsCodec.decode(saved.settings)
		assertEquals(job.rig, LayerloomRigOptions.of(reopenedSettings))
		val reopened = builder.build(saved)
		PSD2LivePipeline().export(reopened, "quill.psd", Path.of(dir, "editor-out"))
	}
}
