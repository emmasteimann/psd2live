package io.github.psd2live.application
import io.github.psd2live.project.Path

import io.github.psd2live.project.WorkspaceMutationResult

/** Explicit archive lifecycle port; clients never select paths through a GUI dialog. */
interface WorkspaceProjectLifecycle {
    suspend fun saveProjectAt(path: Path? = null): WorkspaceMutationResult
    suspend fun openProjectAt(path: Path, discardUnsaved: Boolean = false): WorkspaceMutationResult
}
