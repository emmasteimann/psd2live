package io.github.psd2live.format.compile

import kotlin.native.identityHashCode

/** The iPad hands the rasterizer its pages' pixels (the atlas it just built), so it decodes nothing itself. */
internal actual fun decodePngArgb(png: ByteArray): Triple<Int, Int, IntArray>? =
	throw UnsupportedOperationException("On the iPad the rasterizer takes the texture pages' pixels, not their PNGs")

internal actual fun <R> boundedParallel(count: Int, bytes: (Int) -> Long, task: (Int) -> R): List<R> = List(count) { task(it) }

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual fun identityHashOf(value: Any): Int = value.identityHashCode()
