package io.github.psd2live.project
import io.github.psd2live.core.synchronized

/** Complete snapshots supersede pending snapshots of the same destination; unrelated writes keep their order. */
class CoalescingPersistence(private val schedule: (() -> Unit) -> Unit) {
    private val pending = mutableMapOf<Any, () -> Unit>()

    fun submit(destination: Any, write: () -> Unit) = synchronized(pending) {
        val previous = pending.put(destination, write)
        if (previous == null) {
            // Enqueue before returning so a caller's subsequent flush cannot pass an unqueued write.
            schedule {
                // A write arriving during execution gets a new entry, after any flush already queued.
                val latest = synchronized(pending) { pending.remove(destination) }
                latest?.invoke()
            }
        }
    }
}
