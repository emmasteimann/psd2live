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
	fun isEmpty(): Boolean
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

/** [body] for each index below [count], results in index order: in parallel when [parallel] and the platform allows. */
expect fun <T> parallelMap(count: Int, parallel: Boolean, body: (Int) -> T): List<T>

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

/** A transparent [width] × [height] raster (BufferedImage.TYPE_INT_ARGB on the desktop). */
expect fun newRigImage(width: Int, height: Int): RigImage

/** The own pixels of an image made by [newRigImage] or [rigImageOf]: 0xAARRGGBB row by row, written in place. */
expect fun RigImage.writablePixels(): IntArray

/** [rgba] (straight alpha, [width] × [height], row by row) as an image (PreviewRenderer.rasterImage on the desktop). */
expect fun rigImageOf(width: Int, height: Int, rgba: ByteArray): RigImage

/** The flattened artwork for previews (PreviewRenderer.composite on the desktop). Shared: not to be modified. */
expect fun compositePreview(source: org.umamo.format.art.SourceArt): RigImage

/** A texture file a desktop job wrote (texture upscaling): java.nio.file.Path on the desktop. */
expect interface TextureFile

/** Pixels read from a texture file: ARGB row by row, and whether the file had an alpha channel. */
class TexturePixels(val width: Int, val height: Int, val argb: IntArray, val hasAlpha: Boolean)

/** Reads [file] (a PNG). */
expect fun readTexturePixels(file: TextureFile): TexturePixels

/**
 * What Java2D's source-over draw gives a colour channel of value v at alpha a over a transparent pixel, at index
 * a * 256 + v ([RigGenerationSource.SourceOver]): read off Java2D on the desktop, [java2dSourceOverTable] on the iPad.
 */
expect fun platformSourceOverTable(): ByteArray?

/** The padding drawn with Java2D, for a desktop whose Java2D gave no [platformSourceOverTable]. */
expect fun legacyPaddedRaster(current: org.umamo.format.art.SourceLayer, bounds: org.umamo.format.art.LayerBounds): org.umamo.format.art.LayerRaster

/**
 * Java2D's source-over table computed as Java2D computes it (AlphaMath.c: mul8table premultiplies, div8table divides
 * back, both fixed-point steps). Equal to the table read off Java2D (pinned by a desktop test).
 */
fun java2dSourceOverTable(): ByteArray {
	fun mul8(i: Int, j: Int): Int = if (j == 0) 0 else ((((i.toLong() shl 16) + (i shl 8) + i) * j + (1L shl 23)) and 0xffffffffL ushr 24).toInt()
	fun div8(i: Int, j: Int): Int {
		if (j >= i) return 255
		val inc = ((0xffL shl 24) + i / 2) / i
		return ((inc * j + (1L shl 23)) and 0xffffffffL ushr 24).toInt()
	}
	val table = ByteArray(256 * 256)
	for (a in 1 until 256) for (v in 0 until 256) table[(a shl 8) or v] = div8(a, mul8(a, v)).toByte()
	return table
}

/**
 * [value] with [decimals] places as Java's String.format("%.Nf", Locale.ROOT): the shortest decimal that reads back as
 * the double (Double.toString's digits), rounded half-up, trailing zeros kept.
 */
fun formatFixedRoot(value: Double, decimals: Int): String {
	if (value.isNaN()) return "NaN"
	if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
	val s = kotlin.math.abs(value).toString()
	val e = s.indexOfFirst { it == 'E' || it == 'e' }
	val mantissa = if (e < 0) s else s.substring(0, e)
	val exponent = if (e < 0) 0 else s.substring(e + 1).toInt()
	val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
	val digits = mantissa.replace(".", "")
	val decimalAt = point + exponent
	val intPart = if (decimalAt <= 0) "0" else digits.take(decimalAt).padEnd(decimalAt, '0')
	val fracAll = if (decimalAt >= 0) digits.drop(decimalAt) else "0".repeat(-decimalAt) + digits
	var kept = (intPart + fracAll.padEnd(decimals, '0').take(decimals)).toCharArray()
	val rest = fracAll.drop(decimals)
	if (rest.isNotEmpty() && rest[0] >= '5') {
		var k = kept.size - 1
		while (k >= 0 && kept[k] == '9') { kept[k] = '0'; k-- }
		kept = if (k < 0) charArrayOf('1') + kept else kept.also { it[k] = it[k] + 1 }
	}
	val all = kept.concatToString()
	val whole = all.dropLast(decimals).trimStart('0').ifEmpty { "0" }
	val text = if (decimals == 0) whole else whole + "." + all.takeLast(decimals)
	return if (value < 0 || (value == 0.0 && 1.0 / value < 0)) "-$text" else text
}

/** Whether preview bundles are read back ([PSD2LivePipeline.validatesPreviewBundles]): on the desktop a system property, else
 * whether assertions are on (the test JVMs); off on the iPad. */
expect fun previewBundleValidationDefault(): Boolean

/** System.identityHashCode. */
expect fun identityHash(value: Any?): Int
