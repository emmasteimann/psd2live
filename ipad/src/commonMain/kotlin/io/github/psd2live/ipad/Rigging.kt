package io.github.psd2live.ipad

import io.github.psd2live.core.IrFrameRenderer
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
	val config = PipelineConfig(exportCmo3 = false)
	val pipeline = PSD2LivePipeline()
	progress("Reading the layers", 0.02)
	val source = PsdReader.read(bytes)
	val model = pipeline.buildPreview(source, config, ProgressListener { stage, fraction -> progress(stage, 0.04 + fraction * 0.8) })
	progress("Writing the runtime files", 0.86)
	val folder = RigFiles.folderFor(name)
	val outcome = pipeline.exportFiles(model, name) { relative, data -> RigFiles.write(folder, relative, data); relative }
	progress("Done", 1.0)
	val puppet = model.rig.puppet
	return RigOutcome(name, puppet.drawables.size, puppet.deformers.size, puppet.parameters.size, outcome.files.sorted(), folder,
		outcome.warnings, RigFiles.nowSeconds() - started, RigPreview(model))
}

/** The app's files: PSDs it can rig, and the Rigs folder it writes into (the Files app shows both). */
expect object RigFiles {
	/** The PSDs in the app's Documents folder (put there from the Files app or the share sheet), by name. */
	fun psds(): List<String>
	fun read(name: String): ByteArray
	/** A fresh folder for [psdName]'s rig: Documents/Rigs/NAME (an older rig of the same name is replaced). */
	fun folderFor(psdName: String): String
	fun write(folder: String, relative: String, data: ByteArray)
	fun nowSeconds(): Double
}

/** A rendered frame as an image Compose can draw. */
expect fun RasterImage.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap
