package io.github.psd2live.application

import kotlinx.serialization.json.JsonObject

/** A request is a pure candidate edit; it has no paths, jobs, UI projection or history effects. */
data class WorkspaceDocumentOperation(val operation: String, val request: JsonObject)
