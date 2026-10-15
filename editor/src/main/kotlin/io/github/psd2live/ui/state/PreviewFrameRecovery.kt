package io.github.psd2live.ui.state

/** A broken preview backs off without blocking edits or repeatedly publishing the same warning. */
class PreviewFrameRecovery {
    private var model: Any? = null
    private var context: String? = null
    private var failures = 0
    private var retryAt = 0L
    private var reported: Pair<Class<*>, String?>? = null

    @Synchronized fun shouldAttempt(model: Any?, context: String, now: Long): Boolean {
        if (this.model !== model || this.context != context) {
            this.model = model
            this.context = context
            succeeded()
        }
        return now >= retryAt
    }

    @Synchronized fun succeeded() {
        failures = 0
        retryAt = 0L
        reported = null
    }

    /** Returns whether this episode needs a new warning. Changes to the input allow an immediate retry. */
    @Synchronized fun failed(failure: Throwable, now: Long): Boolean {
        val signature = failure.javaClass to failure.message
        val report = reported != signature
        reported = signature
        val delay = (250_000_000L shl failures.coerceAtMost(5)).coerceAtMost(5_000_000_000L)
        failures = (failures + 1).coerceAtMost(6)
        retryAt = now + delay
        return report
    }
}
