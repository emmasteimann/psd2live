package io.github.psd2live.core

import java.nio.file.Path

// What an export wrote to disk: the desktop's side of the pipeline (the shared model is in Model.kt).

data class ExportedFile(val path: Path, val bytes: Long)

data class PipelineResult(
	val analysis: PipelineAnalysis,
	val exportedFiles: List<ExportedFile>,
	val warnings: List<String>,
	val previewModel: RigPreviewModel,
)
