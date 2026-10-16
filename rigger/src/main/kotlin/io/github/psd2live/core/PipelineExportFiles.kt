package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.art.SourceArt
import org.umamo.format.psd.PsdReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.model.gen.CWarpDeformerBezierExtension
import org.umamo.format.cmo3.type.CArrayList
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// The desktop's side of the pipeline: PSD files in, export folders out, and the .cmo3 export (umamo's .cmo3 writer is
// the desktop's). The shared pipeline works on bytes (PSD2LivePipeline, exportFiles).

data class ExportedFile(val path: Path, val bytes: Long)

data class PipelineResult(
	val analysis: PipelineAnalysis,
	val exportedFiles: List<ExportedFile>,
	val warnings: List<String>,
	val previewModel: RigPreviewModel,
)

fun PSD2LivePipeline.inspect(psd: Path, config: PipelineConfig = PipelineConfig()): PipelineAnalysis {
	require(Files.isRegularFile(psd)) { tr("error.psdMissing", psd) }
	val bytes = Files.readAllBytes(psd)
	require(PsdReader.matches(bytes)) { tr("error.invalidPsd", psd) }
	return RigGenerationSource.analyze(PsdReader.read(bytes), config)
}

fun PSD2LivePipeline.buildPreview(psd: Path, config: PipelineConfig = PipelineConfig()): RigPreviewModel =
	buildPreview(inspect(psd, config), config)

fun PSD2LivePipeline.run(
	psd: Path,
	outputDirectory: Path,
	config: PipelineConfig = PipelineConfig(),
	progress: ProgressListener = ProgressListener { _, _ -> },
): PipelineResult {
	progress.update(tr("progress.readPsd"), 0.04)
	require(Files.isRegularFile(psd)) { tr("error.psdMissing", psd) }
	val bytes = Files.readAllBytes(psd)
	require(PsdReader.matches(bytes)) { tr("error.invalidPsd", psd) }
	val model = layerloomPreview(PsdReader.read(bytes), config, building(progress))
	return export(model, psd.fileName.toString(), outputDirectory, progress)
}

/** Export the current authoritative source, including Agent-created layers, without rereading the PSD. */
fun PSD2LivePipeline.run(
	source: SourceArt,
	sourceName: String,
	outputDirectory: Path,
	config: PipelineConfig = PipelineConfig(),
	progress: ProgressListener = ProgressListener { _, _ -> },
): PipelineResult {
	progress.update(tr("progress.readPsd"), 0.04)
	return export(layerloomPreview(source, config, building(progress)), sourceName, outputDirectory, progress)
}

/**
 * Writes [model] - the rig the editor shows, as built - as the formats its config enables, each read back and
 * checked, into [outputDirectory] ([PSD2LivePipeline.exportFiles] makes and checks them).
 */
fun PSD2LivePipeline.export(model: RigPreviewModel, sourceName: String, outputDirectory: Path,
                            progress: ProgressListener = ProgressListener { _, _ -> }): PipelineResult {
	val outputRoot = outputDirectory.toAbsolutePath().normalize()
	Files.createDirectories(outputRoot)
	val outcome = exportFiles(model, sourceName, progress) { name, bytes -> writeContained(outputRoot, name, bytes) }
	return PipelineResult(model.analysis, outcome.files, outcome.warnings, model)
}

private fun writeContained(root: Path, relativeName: String, bytes: ByteArray): ExportedFile {
	val target = root.resolve(relativeName.replace('/', java.io.File.separatorChar)).normalize()
	require(target.startsWith(root)) { tr("error.outputEscapesRoot", relativeName) }
	Files.createDirectories(target.parent)
	Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
	return ExportedFile(target, bytes.size.toLong())
}

internal actual fun PSD2LivePipeline.exportCmo3(baseName: String, model: RigPreviewModel, warnings: MutableList<String>, write: (String, ByteArray) -> Unit) {
	val (analysis, atlas, rig, config) = model
val ir = RigIrCompiler.compile(analysis, atlas, rig, config, tileArt = true)
val cmo3 = io.github.psd2live.targets.cubism.Cmo3Target { BezierWarp.configureEditor(it, config.rigEdits) }
val cmo3Options = io.github.psd2live.format.compile.ExportOptions(baseName, settings = mapOf("clips" to config.exportCan3.toString()))
val converted = cmo3.convert(ir, cmo3Options)
val bytes = Cmo3.write(converted.model)
write("$baseName.cmo3", bytes)
warnings += converted.report.notices.map { noticeText("CMO3", it) }
// One line for every layer held above canvas resolution, not one per layer.
cmo3.textureLossSummary(ir, cmo3Options)?.let { warnings += "CMO3: $it" }
cmo3.can3(ir, cmo3Options, converted)?.let { write("$baseName.can3", it) }
warnings += cmo3.can3Losses(ir, cmo3Options).map { "CAN3: ${it.objectId}: ${it.note}" }
val source = Cmo3.read(bytes).root as? CModelSource ?: error(tr("error.cmo3Root"))
val reimported = Cmo3Import.fromModelSource(source)
warnings += validateRigShape("CMO3", converted.puppet, reimported)
val cmo3Label = tr("validation.cmo3Readback")
val cmoNeutral = RigIntegrityValidator.validateNeutralPose(cmo3Label, reimported, rig.sourceBoundsByDrawableId)
warnings += cmoNeutral.warnings
warnings += RigIntegrityValidator.validateHeadAnglePoses(cmo3Label, reimported, cmoNeutral.boundsByDrawableId)
warnings += RigIntegrityValidator.validateDirectionalWarpDimensions(cmo3Label, reimported)
}

actual fun previewBundleValidationDefault(): Boolean =
	System.getProperty("psd2live.validatePreviewBundles")?.toBooleanStrictOrNull() ?: PSD2LivePipeline::class.java.desiredAssertionStatus()

actual fun identityHash(value: Any?): Int = System.identityHashCode(value)

/** The job file beside [psd] (its name with [LayerloomJob.SUFFIX]), if there is one. */
fun LayerloomJob.Companion.besidePsd(psd: Path): Path? = psd.resolveSibling(psd.fileName.toString().substringBeforeLast('.') + LayerloomJob.SUFFIX)
	.takeIf { Files.isRegularFile(it) }

fun LayerloomJob.Companion.read(path: Path): LayerloomJob = parse(Json.parseToJsonElement(Files.readString(path)) as JsonObject)

actual object ImportedCmo3 {
	actual fun analysis(source: org.umamo.format.art.SourceArt, config: PipelineConfig): PipelineAnalysis = Cmo3ModelImport.analysis(source, config)
	actual fun baseRig(source: org.umamo.format.art.SourceArt, config: PipelineConfig): Pair<PackedAtlas, BuiltRig> = Cmo3ModelImport.baseRig(source, config)
	actual fun paintingPreview(pipeline: PSD2LivePipeline, source: org.umamo.format.art.SourceArt, config: PipelineConfig): RigPreviewModel =
		Cmo3ModelImport.paintingPreview(pipeline, source, config)
	actual fun puppet(encoded: String): org.umamo.runtime.model.PuppetModel = Cmo3ModelImport.decode(encoded).puppet
	actual fun textureCanvas(preview: RigPreviewModel, drawable: org.umamo.runtime.model.Drawable): FloatArray = Cmo3ModelImport.textureCanvas(preview, drawable)
	actual fun bezierDivisions(encoded: String): Map<String, Pair<Int, Int>> {
		val model = Cmo3.read(base64Decode(encoded)).root as CModelSource
		val sources = Cmo3Import.elementsOf((model.deformerSourceSet as? org.umamo.format.cmo3.model.gen.CDeformerSourceSet)?._sources)
			.filterIsInstance<org.umamo.format.cmo3.model.gen.CWarpDeformerSource>()
		return sources.mapNotNull { source ->
			val key = Cmo3Import.idStrOf(source.id) ?: return@mapNotNull null
			val extension = Cmo3Import.elementsOf(source._extensions).filterIsInstance<org.umamo.format.cmo3.model.gen.CWarpDeformerBezierExtension>().lastOrNull()
				?: return@mapNotNull null
			if (extension.bezierRow !in 1..16 || extension.bezierCol !in 1..16) null else key to (extension.bezierRow to extension.bezierCol)
		}.toMap()
	}
}

/** The editable Bezier divisions written into a .cmo3 model's warps (Cubism keeps them apart from the baked lattice). */
fun BezierWarp.configureEditor(model: CModelSource, overlay: RigEditOverlay = RigEditOverlay.Empty) {
    val sources = (model.deformerSourceSet as? CDeformerSourceSet)?._sources as? Iterable<*> ?: return
    for (warp in sources.filterIsInstance<CWarpDeformerSource>()) {
        val id = Cmo3Import.idStrOf(warp.id) ?: continue
        val (rows, columns) = RigBezierJournal.divisions(overlay, id)
        val extensions = CArrayList<Any?>()
        (warp._extensions as? Iterable<*>)?.filterNot { it is CWarpDeformerBezierExtension }?.forEach { extensions.add(it) }
        extensions.add(CWarpDeformerBezierExtension().apply {
            guid = org.umamo.format.cmo3.model.identity.Guid("CExtensionGuid").apply { uuid = java.util.UUID.randomUUID().toString() }
            _owner = warp
            editLevel = 2
            bezierCol = columns
            bezierRow = rows
        })
        warp._extensions = extensions
    }
}
