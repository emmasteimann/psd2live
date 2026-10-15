package io.github.psd2live.application

class WorkspaceUnsavedChanges : IllegalStateException("Current workspace has unsaved changes; save first or explicitly discard them")
class WorkspaceBusy : IllegalStateException("Workspace is busy")
class WorkspaceCheckTimeout : IllegalStateException("Disconnected mesh check timed out")
