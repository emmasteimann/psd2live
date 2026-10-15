package io.github.psd2live.ipad

import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.project.resolve
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.RasterImage
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.ProgressListener
import org.umamo.format.psd.PsdReader

/** What rigging one PSD made: the rig's size, where its runtime files went, and the checks' warnings. */
class RigOutcome(
	val name: String,
	val drawables: Int,
	val deformers: Int,
	val parameters: Int,
	val files: List<String>,
	val folder: String,
	val warnings: List<String>,
	val seconds: Double,
	val preview: RigPreview,
	/** What the rig was built from, for saving it as a project. */
	internal val source: org.umamo.format.art.SourceArt,
	internal val config: PipelineConfig,
	/** The rig as the editor holds it, for painting. */
	internal val model: RigPreviewModel,
)

/** One parameter's slider: its id, display name and range. */
class PreviewParameter(val id: String, val name: String, val min: Float, val max: Float, val default: Float)

/**
 * The rig drawn at any pose, as the desktop's raster exports draw it (the shared software rasterizer). One frame at a
 * time; render off the main thread.
 */
class RigPreview(model: RigPreviewModel) {
	private val ir = RigIrCompiler.compile(model)
	private val session = IrFrameRenderer.open(ir, false)
	private val lock = Any()
	val parameters: List<PreviewParameter> = ir.parameters.map { PreviewParameter(it.id, it.name, it.min, it.max, it.default) }

	/** The whole canvas at [values] (missing parameters at their defaults), [size] px on its longer side. */
	fun render(values: Map<String, Float>, size: Int): RasterImage =
		io.github.psd2live.core.synchronized(lock) { session.render(values, 0f, FrameSpec.canvas(ir, size), null) }
}

/**
 * Rigs [bytes] (a PSD named [name]) with the desktop's pipeline and writes the moc3 runtime files - the same files the
 * Mac app exports, byte for byte - into the app's Rigs folder ([RigFiles]). The .cmo3 export is the desktop's.
 * Blocking: run it off the main thread. [progress] gets each stage and the fraction done.
 */
fun rigPsd(name: String, bytes: ByteArray, progress: (String, Double) -> Unit): RigOutcome {
	require(PsdReader.matches(bytes)) { "$name is not a Photoshop file" }
	val started = RigFiles.nowSeconds()
	progress("Reading the layers", 0.02)
	return buildRig(name, PsdReader.read(bytes), PipelineConfig(exportCmo3 = false), started, progress)
}

/** Builds [source]'s rig with [config], writes its runtime files into Rigs/NAME and returns what was made. */
internal fun buildRig(name: String, source: org.umamo.format.art.SourceArt, config: PipelineConfig, started: Double,
	progress: (String, Double) -> Unit): RigOutcome {
	val pipeline = PSD2LivePipeline()
	val model = pipeline.buildPreview(source, config, ProgressListener { stage, fraction -> progress(stage, 0.04 + fraction * 0.8) })
	return writeRig(name, source, config, model, started, progress)
}

/** [model]'s runtime files written into Rigs/NAME, and the outcome that describes them. */
internal fun writeRig(name: String, source: org.umamo.format.art.SourceArt, config: PipelineConfig, model: RigPreviewModel,
	started: Double, progress: (String, Double) -> Unit): RigOutcome {
	progress("Writing the runtime files", 0.86)
	val folder = RigFiles.folderFor(name)
	val outcome = PSD2LivePipeline().exportFiles(model, name) { relative, data -> RigFiles.write(folder, relative, data); relative }
	progress("Done", 1.0)
	val puppet = model.rig.puppet
	return RigOutcome(name, puppet.drawables.size, puppet.deformers.size, puppet.parameters.size, outcome.files.sorted(), folder,
		outcome.warnings, RigFiles.nowSeconds() - started, RigPreview(model), source, config, model)
}

/**
 * [outcome] saved as a PSD2Live project (`Rigs/NAME.psd2live`) through the desktop's own project code: the Mac editor
 * opens it with the same rig. Returns the file's path.
 */
suspend fun saveRigProject(outcome: RigOutcome): String {
	val document = io.github.psd2live.project.WorkspaceDocument(outcome.source, emptyMap(), emptySet(), emptyMap(), emptyMap(),
		outcome.config.rigEdits, io.github.psd2live.project.WorkspaceSettingsCodec.encode(outcome.config),
		generationSource = outcome.config.generationSource)
	val revision = io.github.psd2live.project.WorkspaceRevisions.of(document)
	val history = io.github.psd2live.history.WorkspaceHistoryTree(document, revision, revision)
	val work = io.github.psd2live.project.Files.createTempDirectory("rig-save-")
	val target = io.github.psd2live.project.pathOf(RigFiles.projectPath(outcome.name))
	try {
		@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
		val projectId = "project-${kotlin.uuid.Uuid.random()}"
		io.github.psd2live.project.ProjectRepository().save(io.github.psd2live.project.ProjectSaveCapture(projectId, history.state(),
			kotlinx.serialization.json.JsonObject(emptyMap()), io.github.psd2live.project.pathOf(RigFiles.psdPath(outcome.name)),
			io.github.psd2live.project.WorkspaceStore(work.resolve("store"))), target)
	} finally { RigFiles.deleteTree(work.toString()) }
	return target.toString()
}

/** The app's files: PSDs it can rig, and the Rigs folder it writes into (the Files app shows both). */
expect object RigFiles {
	/** The PSDs in the app's Documents folder (put there from the Files app or the share sheet), by name. */
	fun psds(): List<String>
	fun read(name: String): ByteArray
	/** A fresh folder for [psdName]'s rig: Documents/Rigs/NAME (an older rig of the same name is replaced). */
	fun folderFor(psdName: String): String
	fun write(folder: String, relative: String, data: ByteArray)
	fun psdPath(name: String): String
	/** Documents/Rigs/NAME.psd2live. */
	fun projectPath(psdName: String): String
	fun deleteTree(path: String)
	fun nowSeconds(): Double
}

/** A rendered frame as an image Compose can draw. */
expect fun RasterImage.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap
