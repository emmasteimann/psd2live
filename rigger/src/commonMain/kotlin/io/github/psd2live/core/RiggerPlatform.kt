package io.github.psd2live.core

// What the shared rigger needs from the platform (Layerloom iPad plan, phase 2). On the JVM the types are Java's own
// (typealiases), so the desktop app keeps handing BufferedImage and java.util.BitSet around unchanged; on the iPad
// they are small Kotlin classes (RiggerPlatformIos.kt).

/** A raster the rigger holds (atlas pages, previews): AWT's BufferedImage on the desktop, an ARGB buffer on the iPad. */
expect class RigImage

expect val RigImage.pixelWidth: Int
expect val RigImage.pixelHeight: Int

/** The pixels as 0xAARRGGBB (straight alpha), row by row. May be the image's own buffer: not to be modified. */
expect fun RigImage.argbPixels(): IntArray

/** The page's canonical PNG, what exports write (ImageIO's bytes on the desktop, as they have always been). */
expect fun canonicalPng(image: RigImage): ByteArray

/** java.util.BitSet's subset the rigger uses; it is java.util.BitSet on the JVM. */
expect class BitSet() {
	constructor(nbits: Int)
	operator fun get(bitIndex: Int): Boolean
	fun set(bitIndex: Int)
	fun set(fromIndex: Int, toIndex: Int)
	fun nextSetBit(fromIndex: Int): Int
	fun nextClearBit(fromIndex: Int): Int
	fun cardinality(): Int
	fun length(): Int
	fun andNot(set: BitSet)
	/** Little-endian bytes up to the highest set bit, as java.util.BitSet's (stored in projects). */
	fun toByteArray(): ByteArray
}

expect fun BitSet.copy(): BitSet

/** The bit set of [bytes] in [BitSet.toByteArray]'s form. */
expect fun bitSetOf(bytes: ByteArray): BitSet

/** [raw] deflated without a zlib header at [level], ending on a full flush (zlib's Z_FULL_FLUSH). */
expect fun deflateRawFullFlush(raw: ByteArray, level: Int): ByteArray

/** Runs [body] for each of [indices]: in parallel where the platform allows, each on its own. */
expect fun parallelFor(indices: IntArray, body: (Int) -> Unit)

/** Throws when the platform asked the current job to stop (a JVM thread interrupt); nothing on the iPad. */
expect fun checkInterrupted()

/** A warning line for whoever runs the rigger (System.err on the desktop, the console on the iPad). */
expect fun warn(message: String)

/** A monotonic clock in nanoseconds (System.nanoTime on the desktop), for timings only. */
expect fun nanoTime(): Long

/** java.lang.InterruptedException: a job was asked to stop. */
expect open class InterruptedException() : Exception

/**
 * A check bound to the calling thread, to be run from work it hands to others: it throws [InterruptedException] once
 * that thread is interrupted (the desktop cancels a build so). Never throws on the iPad.
 */
expect fun callerInterruptCheck(): () -> Unit
