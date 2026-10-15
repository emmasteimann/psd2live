package io.github.psd2live.application

/** Normal close explicitly relinquishes the current session; shutdown preserves recoverable edits. */
enum class WorkspaceCloseReason { SAVED, DISCARDED, ABNORMAL }
