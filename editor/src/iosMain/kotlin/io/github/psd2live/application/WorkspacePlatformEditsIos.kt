package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceDocument

actual class WorkspaceAssetResources private constructor()

actual object WorkspacePlatformEdits {
	actual val assetOps: Set<String> = emptySet()
	actual fun assetLayer(document: WorkspaceDocument, model: RigPreviewModel, operation: WorkspaceDocumentOperation,
		resources: WorkspaceAssetResources?, checkpoint: () -> Unit): WorkspaceDocument =
		throw UnsupportedOperationException("Placing an image as a layer is on the desktop for now")
}
