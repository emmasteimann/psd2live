package io.github.psd2live.format.compile

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

internal actual fun decodePngArgb(png: ByteArray): Triple<Int, Int, IntArray>? {
	val image = ImageIO.read(ByteArrayInputStream(png)) ?: return null
	return Triple(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
}

internal actual fun <R> boundedParallel(count: Int, bytes: (Int) -> Long, task: (Int) -> R): List<R> {
	val results = arrayOfNulls<Any?>(count)
	if (count <= 1) { for (i in 0 until count) results[i] = task(i) } else {
		val unit = 1L shl 16
		val permits = (Runtime.getRuntime().maxMemory() / 3 / unit).coerceIn(1024L, Int.MAX_VALUE.toLong()).toInt()
		val budget = java.util.concurrent.Semaphore(permits)
		java.util.stream.IntStream.range(0, count).parallel().forEach { i ->
			val need = ((bytes(i) + unit - 1) / unit).coerceIn(1L, permits.toLong()).toInt()
			budget.acquireUninterruptibly(need)
			try { results[i] = task(i) } finally { budget.release(need) }
		}
	}
	@Suppress("UNCHECKED_CAST")
	return results.asList() as List<R>
}

internal actual fun identityHashOf(value: Any): Int = System.identityHashCode(value)
