package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceDocument

actual typealias WorkspaceAssetResources = WorkspaceAssetWorkflow

actual object WorkspacePlatformEdits {
	actual val assetOps: Set<String> get() = WorkspaceAssetLayerEdits.supported
	actual fun assetLayer(document: WorkspaceDocument, model: RigPreviewModel, operation: WorkspaceDocumentOperation,
		resources: WorkspaceAssetResources?, checkpoint: () -> Unit): WorkspaceDocument =
		WorkspaceAssetLayerEdits.apply(document, model, operation, resources, checkpoint)
}
