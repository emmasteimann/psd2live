package io.github.psd2live.format.compile

// What the shared preview rasterizer (render/SoftwareRasterizer) needs from the platform.

/** [png] decoded to (width, height, ARGB straight alpha); null when it is not a PNG. ImageIO on the desktop. */
internal expect fun decodePngArgb(png: ByteArray): Triple<Int, Int, IntArray>?

/**
 * [task] over 0 until [count], results in order, in parallel: on the desktop holding at most about a third of the heap
 * in the [bytes] the tasks say they use at once, on the iPad about a sixth of its memory.
 */
internal expect fun <R> boundedParallel(count: Int, bytes: (Int) -> Long, task: (Int) -> R): List<R>

/**
 * How many horizontal bands one large frame is drawn in, side by side on the cores: 1 on the desktop (its frames
 * already render in parallel with each other), the core count on the iPad, whose editing canvases draw one frame at
 * a time. Each band computes its pixels exactly as the whole frame does.
 */
internal expect val renderBands: Int

/** System.identityHashCode. */
internal expect fun identityHashOf(value: Any): Int
