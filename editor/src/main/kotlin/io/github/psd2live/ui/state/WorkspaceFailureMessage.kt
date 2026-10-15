package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceFailure
import io.github.psd2live.i18n.tr

/** User-facing wording shares the typed classification used by background jobs and MCP. */
fun workspaceFailureMessage(failure: Throwable): String = when (WorkspaceFailure.from(failure).code) {
    "out_of_memory" -> tr("error.outOfMemory")
    "stack_overflow" -> tr("error.stackOverflow")
    "mesh_check_timeout" -> tr("error.meshSplitCheckTimeout")
    else -> failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
}
