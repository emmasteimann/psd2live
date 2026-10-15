package io.github.psd2live.application

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.umamo.runtime.model.ParameterId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Ordered authored gestures; evaluated playback frames never enter this queue. */
class WorkspacePoseQueue {
    class Pending(val id: Long, val generation: Long, val workspaceId: String,
                  @Volatile var values: Map<ParameterId, Float>)

    private val lock = Any()
    private val pending = ArrayList<Pending>()
    private var nextId = 0L
    private var queued = 0
    private val commits = Mutex()
    private val successors = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 64
    }

    fun open(generation: Long, workspaceId: String, values: Map<ParameterId, Float>,
             registerEmpty: Boolean = false): Pending = synchronized(lock) {
        Pending(++nextId, generation, workspaceId, values).also {
            if (registerEmpty || values.isNotEmpty()) pending += it
        }
    }

    fun values(generation: Long, workspaceId: String, after: Long? = null): Map<ParameterId, Float> = synchronized(lock) {
        pending.asSequence().filter { it.generation == generation && it.workspaceId == workspaceId &&
            (after == null || it.id > after) }.fold(emptyMap()) { values, change -> values + change.values }
    }

    fun consume(generation: Long, workspaceId: String, commit: Long?): Map<ParameterId, Float> = synchronized(lock) {
        if (commit != null) remove(commit)
        values(generation, workspaceId, commit)
    }

    fun contains(id: Long): Boolean = synchronized(lock) { pending.any { it.id == id } }
    fun remove(id: Long): Boolean = synchronized(lock) { pending.removeAll { it.id == id } }
    fun discard(generation: Long, workspaceId: String) = synchronized(lock) {
        pending.removeAll { it.generation == generation && it.workspaceId == workspaceId }
    }

    fun committed(before: String, after: String?) = synchronized(lock) {
        if (after != null && before != after) successors[before] = after
    }

    /** Follow only this queue's commits; external changes still fail the runtime CAS. */
    fun lineage(expected: String): String = synchronized(lock) {
        var state = expected
        val seen = HashSet<String>()
        while (seen.add(state)) state = successors[state] ?: break
        state
    }

    fun enqueued() = synchronized(lock) { queued++ }
    fun finished() = synchronized(lock) { check(queued > 0); queued-- }
    val busy: Boolean get() = synchronized(lock) { queued > 0 }

    suspend fun <T> serialized(commit: suspend () -> T): T = commits.withLock { commit() }
}

/** Associates a published pose with the gesture whose pending projection it settles. */
class PendingPoseCommit(val id: Long, val workspaceId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PendingPoseCommit>
}
