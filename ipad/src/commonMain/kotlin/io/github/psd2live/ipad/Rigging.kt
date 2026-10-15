package io.github.psd2live.ipad

import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.project.resolve
import io.github.psd2live.project.config
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

/** [this] with [warning] added at the top of its warnings. */
internal fun RigOutcome.withWarning(warning: String) = RigOutcome(name, drawables, deformers, parameters, files, folder,
	listOf(warning) + warnings, seconds, preview, source, config, model)

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

	/** The canvas region ([left], [top], [width], [height]) at [values], [outWidth] x [outHeight] pixels. */
	fun render(values: Map<String, Float>, left: Float, top: Float, width: Float, height: Float, outWidth: Int, outHeight: Int): RasterImage =
		io.github.psd2live.core.synchronized(lock) { session.render(values, 0f, FrameSpec(left, top, width, height, outWidth, outHeight), null) }

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

/**
 * [outcome] re-rigged with [config]. Layers whose mesh settings changed are re-meshed first through the desktop's own
 * path (`rebuildPreview`), which carries hand tweaks onto the new vertices; anything else that changed then rebuilds
 * from that. Blocking.
 */
internal fun applyRigConfig(outcome: RigOutcome, config: PipelineConfig, progress: (String, Double) -> Unit): RigOutcome {
	val started = RigFiles.nowSeconds()
	var base = outcome.model
	var next = config
	if (config.meshOverrides != outcome.config.meshOverrides) {
		progress("Re-meshing", 0.05)
		val remeshed = PSD2LivePipeline().rebuildPreview(base, base.config.copy(meshOverrides = config.meshOverrides))
		next = config.copy(rigEdits = remeshed.config.rigEdits, generationSource = remeshed.config.generationSource, meshSource = remeshed.config.meshSource)
		base = remeshed
	}
	// Physics, expressions and motions shape only what is exported from the rig, not the rig: no rebuild for them.
	val b = base.config
	val exportOnly = next.copy(rigEdits = next.rigEdits.copy(physicsEdits = b.rigEdits.physicsEdits,
		disabledPhysicsIds = b.rigEdits.disabledPhysicsIds, expressions = b.rigEdits.expressions,
		motionPresets = b.rigEdits.motionPresets, motionClips = b.rigEdits.motionClips),
		physicsFrontHair = b.physicsFrontHair, physicsBackHair = b.physicsBackHair, physicsEyeJelly = b.physicsEyeJelly,
		motionIdle = b.motionIdle, motionBlink = b.motionBlink, motionNod = b.motionNod, motionShake = b.motionShake,
		motionSkeleton = b.motionSkeleton) == b
	return when {
		next == base.config -> writeRig(outcome.name, outcome.source, next, base, started, progress)
		exportOnly -> writeRig(outcome.name, outcome.source, next, PSD2LivePipeline().updateRuntimeBundle(base, next), started, progress)
		else -> buildRig(outcome.name, outcome.source, next, started, progress)
	}
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

/**
 * The project at [path] opened through the desktop's own project code and rigged with everything it holds - its
 * settings, hand tweaks and painted pixels - as the outcome the Rig tab shows. Its runtime files go to Rigs/NAME.
 */
suspend fun openRigProject(path: String, progress: (String, Double) -> Unit): RigOutcome {
	val started = RigFiles.nowSeconds()
	progress("Opening the project", 0.02)
	val opened = io.github.psd2live.project.ProjectRepository().open(io.github.psd2live.project.pathOf(path))
	try {
		val document = opened.history.head().snapshot
		require(document.rigEdits.importedCmo3 == null) { "This project was imported from a .cmo3, which only the Mac app opens for now" }
		// Named after the project file; the archive keeps its PSD as "original.psd".
		val name = path.substringAfterLast('/').substringBeforeLast('.') + ".psd"
		// A project from the Mac brings its PSD along: put it beside the others, so saving it again has its source.
		val psd = io.github.psd2live.project.pathOf(RigFiles.psdPath(name))
		if (!io.github.psd2live.project.Files.exists(psd) && io.github.psd2live.project.Files.exists(opened.source))
			io.github.psd2live.project.Files.copy(opened.source, psd)
		val config = document.config(PipelineConfig()).copy(exportCmo3 = false)
		return buildRig(name, document.source, config, started) { stage, fraction -> progress(stage, 0.1 + fraction * 0.9) }
	} finally { opened.close() }
}

/** The app's files: PSDs it can rig, and the Rigs folder it writes into (the Files app shows both). */
expect object RigFiles {
	/** The PSDs in the app's Documents folder (put there from the Files app or the share sheet), by name. */
	fun psds(): List<String>
	/** Saved projects (`.psd2live`) in Documents and Documents/Rigs, as full paths. */
	fun projects(): List<String>
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

/** Files in and out of the app: the system document picker and share sheet. */
expect object Exchange {
	/** Lets the user pick PSDs and projects anywhere in Files; they are copied into the app's folder. Calls back with their names. */
	fun pick(onPicked: (List<String>) -> Unit)
	/** The share sheet for [paths] (files or folders): VTube Studio, AirDrop, Save to Files... */
	fun share(paths: List<String>)
}
