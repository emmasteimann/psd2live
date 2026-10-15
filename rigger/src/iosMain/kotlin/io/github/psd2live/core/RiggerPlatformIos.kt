package io.github.psd2live.core

import io.github.psd2live.format.compile.PngEncoder
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import okio.Buffer
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FULL_FLUSH
import platform.zlib.Z_OK
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

/** The iPad's raster: [argb] is 0xAARRGGBB, straight alpha, row by row (BufferedImage.TYPE_INT_ARGB's layout). */
actual class RigImage(val width: Int, val height: Int, val argb: IntArray = IntArray(width * height)) {
	init { require(width > 0 && height > 0 && argb.size == width * height) { "Raster size mismatch" } }
}

actual val RigImage.pixelWidth: Int get() = width
actual val RigImage.pixelHeight: Int get() = height

actual fun RigImage.argbPixels(): IntArray = argb

actual fun canonicalPng(image: RigImage): ByteArray = PngEncoder.encode(image.width, image.height, image.argb)

actual class BitSet actual constructor() {
	private var words = LongArray(1)

	actual constructor(nbits: Int) : this() {
		require(nbits >= 0)
		words = LongArray(maxOf(1, (nbits + 63) ushr 6))
	}

	private fun grow(wordIndex: Int) {
		if (wordIndex >= words.size) words = words.copyOf(maxOf(words.size * 2, wordIndex + 1))
	}

	actual operator fun get(bitIndex: Int): Boolean {
		require(bitIndex >= 0)
		val w = bitIndex ushr 6
		return w < words.size && words[w] and (1L shl bitIndex) != 0L
	}

	actual fun set(bitIndex: Int) {
		require(bitIndex >= 0)
		val w = bitIndex ushr 6
		grow(w)
		words[w] = words[w] or (1L shl bitIndex)
	}

	actual fun set(fromIndex: Int, toIndex: Int) {
		require(fromIndex in 0..toIndex)
		for (i in fromIndex until toIndex) set(i)
	}

	actual fun nextSetBit(fromIndex: Int): Int {
		require(fromIndex >= 0)
		var w = fromIndex ushr 6
		if (w >= words.size) return -1
		var word = words[w] and (-1L shl fromIndex)
		while (true) {
			if (word != 0L) return (w shl 6) + word.countTrailingZeroBits()
			if (++w == words.size) return -1
			word = words[w]
		}
	}

	actual fun nextClearBit(fromIndex: Int): Int {
		require(fromIndex >= 0)
		var w = fromIndex ushr 6
		if (w >= words.size) return fromIndex
		var word = words[w].inv() and (-1L shl fromIndex)
		while (true) {
			if (word != 0L) return (w shl 6) + word.countTrailingZeroBits()
			if (++w == words.size) return w shl 6
			word = words[w].inv()
		}
	}

	actual fun cardinality(): Int = words.sumOf { it.countOneBits() }

	actual fun length(): Int {
		for (w in words.indices.reversed()) if (words[w] != 0L) return (w shl 6) + 64 - words[w].countLeadingZeroBits()
		return 0
	}

	actual fun andNot(set: BitSet) {
		for (w in 0 until minOf(words.size, set.words.size)) words[w] = words[w] and set.words[w].inv()
	}

	actual fun toByteArray(): ByteArray {
		val bytes = ByteArray((length() + 7) ushr 3)
		for (i in bytes.indices) bytes[i] = (words[i ushr 3] ushr ((i and 7) * 8)).toByte()
		return bytes
	}

	internal fun copyWords(): BitSet = BitSet().also { it.words = words.copyOf() }

	override fun equals(other: Any?): Boolean {
		if (other !is BitSet) return false
		for (w in 0 until maxOf(words.size, other.words.size))
			if (words.getOrElse(w) { 0L } != other.words.getOrElse(w) { 0L }) return false
		return true
	}

	override fun hashCode(): Int {
		// java.util.BitSet's hash, over the words up to the last set one.
		var h = 1234L
		val used = (length() + 63) ushr 6
		for (i in used - 1 downTo 0) h = h xor (words[i] * (i + 1))
		return ((h shr 32) xor h).toInt()
	}
}

actual fun BitSet.copy(): BitSet = copyWords()

actual fun bitSetOf(bytes: ByteArray): BitSet {
	val set = BitSet(bytes.size * 8)
	for (i in bytes.indices) for (b in 0 until 8) if (bytes[i].toInt() shr b and 1 == 1) set.set(i * 8 + b)
	return set
}

// The iPad's own zlib (the JVM's Deflater wraps zlib too): raw deflate (window bits -15), memory level 8.
@OptIn(ExperimentalForeignApi::class)
actual fun deflateRawFullFlush(raw: ByteArray, level: Int): ByteArray = memScoped {
	val stream = alloc<z_stream>()
	check(deflateInit2_(stream.ptr, level, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) == Z_OK) {
		"zlib could not start"
	}
	try {
		val out = Buffer()
		val chunk = ByteArray(64 * 1024)
		val input = if (raw.isEmpty()) ByteArray(1) else raw
		input.usePinned { pinnedIn ->
			stream.next_in = pinnedIn.addressOf(0).reinterpret()
			stream.avail_in = raw.size.toUInt()
			do {
				chunk.usePinned { pinnedOut ->
					stream.next_out = pinnedOut.addressOf(0).reinterpret()
					stream.avail_out = chunk.size.toUInt()
					deflate(stream.ptr, Z_FULL_FLUSH)
				}
				out.write(chunk, 0, chunk.size - stream.avail_out.toInt())
			} while (stream.avail_out.toInt() == 0)
		}
		out.readByteArray()
	} finally {
		deflateEnd(stream.ptr)
	}
}

actual fun parallelFor(indices: IntArray, body: (Int) -> Unit) {
	for (index in indices) body(index)
}

actual fun checkInterrupted() {}

/**
 * One recursive lock for every monitor: the rigger's synchronized blocks guard caches, and a single re-entrant lock
 * can never deadlock with itself however they nest (striped or per-object locks could, in an order the JVM never saw).
 */
@PublishedApi internal val monitor = platform.Foundation.NSRecursiveLock()

actual inline fun <R> synchronized(lock: Any, block: () -> R): R {
	monitor.lock()
	try { return block() } finally { monitor.unlock() }
}
