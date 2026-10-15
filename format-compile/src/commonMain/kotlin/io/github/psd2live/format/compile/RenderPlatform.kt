package io.github.psd2live.format.compile

// What the shared preview rasterizer (render/SoftwareRasterizer) needs from the platform.

/** [png] decoded to (width, height, ARGB straight alpha); null when it is not a PNG. ImageIO on the desktop. */
internal expect fun decodePngArgb(png: ByteArray): Triple<Int, Int, IntArray>?

/**
 * [task] over 0 until [count], results in order: in parallel on the desktop, holding at most about a third of the heap
 * in the [bytes] the tasks say they use at once; one after another on the iPad.
 */
internal expect fun <R> boundedParallel(count: Int, bytes: (Int) -> Long, task: (Int) -> R): List<R>

/** System.identityHashCode. */
internal expect fun identityHashOf(value: Any): Int
