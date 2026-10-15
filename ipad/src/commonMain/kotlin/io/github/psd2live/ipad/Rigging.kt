package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
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
)

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
		outcome.warnings, RigFiles.nowSeconds() - started)
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
