package io.github.psd2live.ui.state
import kotlin.concurrent.Volatile

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

/** UI-owned check: dismissal immediately releases ownership; a late result cannot reopen a dialog. */
class MeshSplitCheck(private val scope: CoroutineScope, private val timeoutMillis: Long = 60_000L) {
    private class Run { @Volatile var job: Job? = null }
    private val active = AtomicReference<Run?>()
    val running get() = active.get() != null

    fun <T> start(action: suspend () -> T, completed: (T) -> Unit, failed: (Throwable) -> Unit,
                  finished: () -> Unit = {}) {
        val owner = Run()
        check(active.compareAndSet(null, owner)) { "Mesh split check is already running" }
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try { withTimeout(timeoutMillis) { action() } }
                catch (timeout: TimeoutCancellationException) {
                    // Parent cancellation still means dismissal. Only this check's deadline is a failure.
                    currentCoroutineContext().ensureActive()
                    throw io.github.psd2live.application.WorkspaceCheckTimeout()
                }
                ensureActive()
                if (active.get() === owner) completed(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (active.get() === owner) failed(failure)
            } finally {
                if (active.compareAndSet(owner, null)) finished()
            }
        }
        owner.job = launched
        launched.invokeOnCompletion {
            // A lazy job can be cancelled before its body (and therefore its finally) runs.
            active.compareAndSet(owner, null)
        }
        if (active.get() !== owner) launched.cancel()
        launched.start()
    }

    fun cancel() {
        active.getAndSet(null)?.job?.cancel()
    }
}
