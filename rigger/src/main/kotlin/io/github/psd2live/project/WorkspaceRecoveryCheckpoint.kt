package io.github.psd2live.project

import kotlinx.serialization.json.JsonObject

/** Complete durable state beside one historical HEAD; presentation contains authored workspace data too. */
data class WorkspaceRecoveryCheckpoint(
    val loadingIdentity: String,
    val state: String,
    val historyHead: String,
    val presentation: JsonObject,
    val auxiliary: JsonObject,
    val tasks: List<WorkspaceTaskSnapshot>,
)
