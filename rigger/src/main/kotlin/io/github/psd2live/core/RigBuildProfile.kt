package io.github.psd2live.core

import kotlin.concurrent.Volatile


/**
 * Wall time spent in each stage of a rig build, summed over every build while [recording] is on. Off - the
 * default - a stage costs one volatile read. Only the measurement tools turn it on.
 */
object RigBuildProfile {
	@Volatile var recording: Boolean = false
	private val nanos = Collections.synchronizedMap(HashMap<String, AtomicLong>())
	private val calls = Collections.synchronizedMap(HashMap<String, AtomicLong>())

	inline fun <T> stage(name: String, block: () -> T): T {
		if (!recording) return block()
		val start = nanoTime()
		try { return block() } finally { add(name, nanoTime() - start) }
	}

	fun add(name: String, elapsed: Long) {
		counter(nanos, name).addAndGet(elapsed)
		counter(calls, name).incrementAndGet()
	}

	fun count(name: String) = counter(calls, name).incrementAndGet()

	private fun counter(map: MutableMap<String, AtomicLong>, name: String): AtomicLong = synchronized(map) { map.getOrPut(name) { AtomicLong() } }

	fun reset() { nanos.clear(); calls.clear() }

	/** Milliseconds per stage and call counts, in first-recorded order of their names. */
	fun snapshot(): Map<String, Pair<Double, Long>> =
		nanos.keys.union(calls.keys).sorted().associateWith { (nanos[it]?.get() ?: 0L) / 1e6 to (calls[it]?.get() ?: 0L) }
}
