package io.github.psd2live.project

import io.github.psd2live.core.WeakReference
import io.github.psd2live.core.identityHash
import io.github.psd2live.core.synchronized

/** No checkpoint chains on the iPad: revisions hash the whole canonical text (the same string). */
internal actual fun journalCheckpointRevision(document: WorkspaceDocument): String? = null

/** Values by key identity, held while the key lives: weak keys, dropped as they are found collected. */
actual class IdentityWeakCache<K : Any, V : Any> actual constructor() {
	private class Entry<V>(val key: WeakReference<Any>, val value: V)
	private val buckets = HashMap<Int, MutableList<Entry<V>>>()
	private var operations = 0

	private fun find(key: Any): V? = buckets[identityHash(key)]?.firstOrNull { it.key.get() === key }?.value

	private fun purge() {
		if (++operations % 256 != 0) return
		val it = buckets.values.iterator()
		while (it.hasNext()) { val list = it.next(); list.removeAll { e -> e.key.get() == null }; if (list.isEmpty()) it.remove() }
	}

	actual fun put(key: K, value: V) {
		synchronized(this) {
			purge()
			val list = buckets.getOrPut(identityHash(key)) { mutableListOf() }
			list.removeAll { it.key.get() === key }
			list.add(Entry(WeakReference(key), value))
		}
	}

	actual fun getOrPut(key: K, compute: () -> V): V {
		synchronized(this) { purge(); find(key)?.let { return it } }
		val value = compute()
		synchronized(this) {
			find(key)?.let { return it }
			buckets.getOrPut(identityHash(key)) { mutableListOf() }.add(Entry(WeakReference(key), value))
		}
		return value
	}
}
