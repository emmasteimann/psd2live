package io.github.psd2live.core

import org.umamo.format.art.SourceArt
import org.umamo.runtime.model.PuppetModel

/**
 * Projects imported from a Cubism `.cmo3` (config.rigEdits.importedCmo3), as the shared rigger sees them. Reading a
 * `.cmo3` is the desktop's (Cmo3ModelImport over umamo's `.cmo3` reader); the iPad opens generated projects only, for
 * now, and says so when handed an imported one.
 */
expect object ImportedCmo3 {
	fun analysis(source: SourceArt, config: PipelineConfig): PipelineAnalysis
	fun baseRig(source: SourceArt, config: PipelineConfig): Pair<PackedAtlas, BuiltRig>
	fun paintingPreview(pipeline: PSD2LivePipeline, source: SourceArt, config: PipelineConfig): RigPreviewModel
	/** The imported model's puppet, from its stored encoding. */
	fun puppet(encoded: String): PuppetModel
	/** The editable Bezier divisions (rows to columns) of the imported model's warps, by warp id. */
	fun bezierDivisions(encoded: String): Map<String, Pair<Int, Int>>
}

/** The texture source id an imported model's painted art has (Cmo3ModelImport.PAINT_SOURCE_ID). */
const val CMO3_PAINT_SOURCE_ID = "cmo3-painted-art"
