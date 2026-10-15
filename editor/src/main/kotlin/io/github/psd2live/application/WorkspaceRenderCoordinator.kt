package io.github.psd2live.application

import io.github.psd2live.core.PreviewFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns view frame streams and deferred model activation, independently of document/UI state. */
class WorkspaceRenderCoordinator {
    private val lock = Any()
    private val frames = mutableMapOf<String, MutableStateFlow<PreviewFrame?>>()
    private val users = mutableMapOf<String, Int>()
    private var needsReload = false

    fun frameFor(key: String): StateFlow<PreviewFrame?> = synchronized(lock) {
        frames.getOrPut(key) { MutableStateFlow(null) }.asStateFlow()
    }

    fun retain(key: String): StateFlow<PreviewFrame?> = synchronized(lock) {
        users[key] = (users[key] ?: 0) + 1
        frameFor(key)
    }

    /** True only when the last mounted user releases a view. */
    fun releaseRetained(key: String): Boolean = synchronized(lock) {
        val count = users[key] ?: return@synchronized false
        if (count > 1) { users[key] = count - 1; false }
        else { users.remove(key); true }
    }

    fun remove(key: String) = synchronized(lock) {
        users.remove(key)
        frames.remove(key)?.value = null
    }

    fun contains(key: String): Boolean = synchronized(lock) { key in frames }
    fun publish(frame: PreviewFrame): Boolean = synchronized(lock) {
        val stream = frames[frame.viewId] ?: return@synchronized false
        stream.value = frame
        true
    }

    fun invalidateModel() = synchronized(lock) { needsReload = true }

    /** Headless captures don't activate a renderer; the first mounted live view claims the load. */
    fun claimModelLoad(live: Boolean): Boolean = synchronized(lock) {
        if (!live || !needsReload || users.isEmpty()) false
        else { needsReload = false; true }
    }

    fun clearFrames() = synchronized(lock) { frames.values.forEach { it.value = null } }
    fun close() = synchronized(lock) {
        clearFrames()
        frames.clear()
        users.clear()
        needsReload = false
    }
}
