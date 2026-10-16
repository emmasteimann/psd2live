package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceDocument

/** What an asset placement draws with: the desktop's asset workflow (Java2D); the iPad has none yet. */
expect class WorkspaceAssetResources

/**
 * Document edits whose engine is still the desktop's own, so the shared document layer can name them: placing an
 * imported image as a layer (it renders with Java2D). On the desktop these are exactly the existing edits; the iPad
 * doesn't offer them yet.
 */
expect object WorkspacePlatformEdits {
	val assetOps: Set<String>
	fun assetLayer(document: WorkspaceDocument, model: RigPreviewModel, operation: WorkspaceDocumentOperation,
		resources: WorkspaceAssetResources?, checkpoint: () -> Unit): WorkspaceDocument
}
