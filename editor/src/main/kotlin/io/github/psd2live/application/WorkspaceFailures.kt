package io.github.psd2live.application
import io.github.psd2live.project.AccessDeniedException
import io.github.psd2live.core.Collections
import io.github.psd2live.core.IdentityHashMap

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.IOException

class WorkspaceOutputContractFailure(val operation: String, val validation: WorkspaceValidationException) :
    IllegalStateException("Operation $operation returned an invalid result: ${validation.message}", validation)

/** The same machine-readable failure is retained by jobs and returned by transport adapters. */
@ConsistentCopyVisibility
data class WorkspaceFailure private constructor(
    val code: String,
    val message: String,
    val details: JsonObject,
) {
    fun toJson(): JsonObject = JsonObject(details + mapOf("code" to JsonPrimitive(code), "message" to JsonPrimitive(message)))

    companion object {
        fun from(failure: Throwable): WorkspaceFailure {
            require(failure !is CancellationException) { "Cancellation is a terminal job state, not a failure" }
            // Worker pools and coroutine adapters can wrap Errors. Classify by type throughout the
            // cause chain, never by a JVM's implementation-specific message such as "Java heap space".
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = failure
            var resourceFailure: Throwable? = null
            while (cause != null && seen.add(cause)) {
                if (cause is OutOfMemoryError) { resourceFailure = cause; break }
                if (cause is StackOverflowError) resourceFailure = cause
                cause = cause.cause
            }
            if (resourceFailure != null) return WorkspaceFailure(
                if (resourceFailure is OutOfMemoryError) "out_of_memory" else "stack_overflow",
                if (resourceFailure is OutOfMemoryError)
                    "Not enough memory to complete this operation. Reduce the artwork size or layer count, close other projects, then retry."
                else "The operation exceeded the stack limit. Reduce the model hierarchy depth, then retry.",
                buildJsonObject { put("exception_type", resourceFailure.javaClass.name) },
            )
            val code = when (failure) {
                is io.github.psd2live.core.GeometrySafetyRejectedException -> "geometry_unsafe"
                is WorkspaceBatchEditException -> "invalid_edit"
                is WorkspaceTileCollision -> "tile_collides"
                is WorkspaceValidationException -> "invalid_request"
                is WorkspaceOutputContractFailure -> "output_contract"
                is WorkspaceConflict -> "state_conflict"
                is WorkspaceProjectConflict -> "project_conflict"
                is WorkspaceRequestReuse -> "request_id_reused"
                is WorkspaceUnsavedChanges -> "unsaved_changes"
                is WorkspaceBusy -> "workspace_busy"
                is WorkspaceCheckTimeout -> "mesh_check_timeout"
                is AccessDeniedException, is SecurityException -> "permission_denied"
                is IOException -> "io_error"
                is IllegalArgumentException -> "invalid_argument"
                is IllegalStateException -> "invalid_state"
                else -> "operation_failed"
            }
            val details = buildJsonObject {
                when (failure) {
                    is io.github.psd2live.core.GeometrySafetyRejectedException -> put("diagnostics", failure.safetyReport.toJson())
                    is WorkspaceValidationException -> put("field", failure.fieldPath)
                    is WorkspaceOutputContractFailure -> {
                        put("field", failure.validation.fieldPath); put("operation", failure.operation)
                    }
                    is WorkspaceBatchEditException -> {
                        put("edit_index", failure.index); put("edit_operation", failure.editOperation)
                    }
                    is WorkspaceTileCollision -> put("layer_ids", JsonArray(failure.layerIds.map(::JsonPrimitive)))
                    is WorkspaceProjectConflict -> {
                        put("expected_project", failure.expectedProject?.let(::JsonPrimitive) ?: JsonNull)
                        put("actual_project", failure.actualProject?.let(::JsonPrimitive) ?: JsonNull)
                    }
                    is WorkspaceConflict -> {
                        put("expected_state", failure.expectedState); put("actual_state", failure.actualState)
                    }
                }
            }
            return WorkspaceFailure(code, failure.message ?: "Operation failed", details)
        }
    }
}
