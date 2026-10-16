package io.github.psd2live.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** Synchronous solver loops observe the originating coroutine, even before the next suspension. */
fun WorkspaceSimulationWork.cancellable(context: CoroutineContext,
    progress: (String, Float) -> Unit = { id, value -> context[WorkspaceJobContext]?.progress(0.1f + 0.75f * value, "Baking simulation $id") },
): WorkspaceSimulationWork {
    val observer = this
    return object : WorkspaceSimulationWork {
        private var progressHigh = 0f
        override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = observer.run(id) { report, cancelled ->
            fun check() { context.ensureActive(); if (cancelled()) throw CancellationException("Simulation bake cancelled") }
            check()
            val result = action({ value ->
                check(); progressHigh = maxOf(progressHigh, value); progress(id, progressHigh); report(value)
            }, { check(); false })
            check()
            result
        }
    }
}

