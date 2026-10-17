package io.github.psd2live.format.compile

import kotlin.native.identityHashCode

/** The iPad hands the rasterizer its pages' pixels (the atlas it just built), so it decodes nothing itself. */
internal actual fun decodePngArgb(png: ByteArray): Triple<Int, Int, IntArray>? =
	throw UnsupportedOperationException("On the iPad the rasterizer takes the texture pages' pixels, not their PNGs")

/**
 * GCD's dispatch_apply over the cores (it nests safely and returns when every task has), with the tasks' [bytes] held
 * to about a sixth of the iPad's memory at once; the first failure is rethrown after the rest finish.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual fun <R> boundedParallel(count: Int, bytes: (Int) -> Long, task: (Int) -> R): List<R> {
	if (count <= 1) return List(count) { task(it) }
	val results = arrayOfNulls<Any?>(count)
	val budget = (platform.Foundation.NSProcessInfo.processInfo.physicalMemory.toLong() / 6).coerceAtLeast(64L shl 20)
	val lock = platform.Foundation.NSCondition()
	var free = budget
	var failure: Throwable? = null
	platform.darwin.dispatch_apply(count.toULong(), platform.darwin.dispatch_get_global_queue(platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) { i ->
		val index = i.toInt()
		val need = bytes(index).coerceIn(1L, budget)
		lock.lock(); while (free < need) lock.wait(); free -= need; lock.unlock()
		try { results[index] = task(index) }
		catch (t: Throwable) { lock.lock(); if (failure == null) failure = t; lock.unlock() }
		finally { lock.lock(); free += need; lock.broadcast(); lock.unlock() }
	}
	failure?.let { throw it }
	@Suppress("UNCHECKED_CAST")
	return results.asList() as List<R>
}

internal actual val renderBands: Int = platform.Foundation.NSProcessInfo.processInfo.activeProcessorCount.toInt().coerceIn(1, 16)

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual fun identityHashOf(value: Any): Int = value.identityHashCode()
