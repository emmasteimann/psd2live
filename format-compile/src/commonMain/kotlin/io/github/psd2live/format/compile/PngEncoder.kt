package io.github.psd2live.format.compile

import okio.Buffer
import kotlin.math.abs

/**
 * An 8-bit RGBA PNG encoder for large images that compresses bands of rows in parallel.
 *
 * Each band of [BAND_ROWS] rows is filtered (per row, the filter whose output has the smallest sum of
 * absolute signed bytes, as common encoders choose) and deflated by its own compressor, ending on a sync
 * flush; the bands' deflate segments concatenate into one zlib stream whose Adler-32 is combined from the
 * bands'. The bands do not depend on the number of threads, so the bytes are the same on every machine with
 * the same zlib.
 *
 * Shared code (it runs on the iPad too): only the deflating and the band parallelism are the platform's
 * ([deflateRawSyncFlush], [forEachBand]); the checksums are computed here.
 */
public object PngEncoder {
	private const val BAND_ROWS = 64
	private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

	private class Band(val deflated: ByteArray, val adler: Int, val length: Long)

	/** [argb] (0xAARRGGBB, straight alpha, row by row) as a PNG file. */
	public fun encode(width: Int, height: Int, argb: IntArray): ByteArray {
		require(width > 0 && height > 0 && argb.size.toLong() == width.toLong() * height) { "Raster size mismatch" }
		val count = (height + BAND_ROWS - 1) / BAND_ROWS
		val bands = arrayOfNulls<Band>(count)
		forEachBand(count) { index ->
			val start = index * BAND_ROWS
			bands[index] = band(argb, width, start, minOf(height, start + BAND_ROWS))
		}
		var adler = 1
		for (band in bands) adler = combineAdler(adler, band!!.adler, band.length)
		val idat = Buffer()
		// zlib header (deflate, 32K window, no preset dictionary), the bands, an empty final stored block, the checksum.
		idat.writeByte(0x78); idat.writeByte(0x01)
		for (band in bands) idat.write(band!!.deflated)
		idat.write(byteArrayOf(0x01, 0x00, 0x00, 0xff.toByte(), 0xff.toByte()))
		idat.writeInt(adler)
		val out = Buffer()
		out.write(SIGNATURE)
		chunk(out, "IHDR", Buffer().writeInt(width).writeInt(height).writeByte(8).writeByte(6).writeByte(0).writeByte(0).writeByte(0).readByteArray())
		chunk(out, "IDAT", idat.readByteArray())
		chunk(out, "IEND", ByteArray(0))
		return out.readByteArray()
	}

	/** Rows [start] until [end] filtered and deflated on their own. */
	private fun band(argb: IntArray, width: Int, start: Int, end: Int): Band {
		val stride = width * 4
		val raw = ByteArray((stride + 1) * (end - start))
		var previous = if (start == 0) ByteArray(stride) else row(argb, width, start - 1, ByteArray(stride))
		var current = ByteArray(stride)
		val candidates = Array(5) { ByteArray(stride) }
		for (y in start until end) {
			row(argb, width, y, current)
			var best = 0; var bestScore = Long.MAX_VALUE
			for (filter in 0 until 5) {
				val score = filter(filter, current, previous, candidates[filter])
				if (score < bestScore) { bestScore = score; best = filter }
			}
			val offset = (y - start) * (stride + 1)
			raw[offset] = best.toByte()
			candidates[best].copyInto(raw, offset + 1)
			val swap = previous; previous = current; current = swap
		}
		return Band(deflateRawSyncFlush(raw), adler32(raw), raw.size.toLong())
	}

	private fun row(argb: IntArray, width: Int, y: Int, into: ByteArray): ByteArray {
		var o = 0
		for (x in y * width until (y + 1) * width) {
			val p = argb[x]
			into[o] = (p shr 16).toByte(); into[o + 1] = (p shr 8).toByte(); into[o + 2] = p.toByte(); into[o + 3] = (p ushr 24).toByte()
			o += 4
		}
		return into
	}

	/** [current] filtered by PNG filter [type] against [previous] into [out]; returns the sum of absolute signed bytes. */
	private fun filter(type: Int, current: ByteArray, previous: ByteArray, out: ByteArray): Long {
		var sum = 0L
		for (i in current.indices) {
			val x = current[i].toInt() and 0xff
			val a = if (i >= 4) current[i - 4].toInt() and 0xff else 0
			val b = previous[i].toInt() and 0xff
			val c = if (i >= 4) previous[i - 4].toInt() and 0xff else 0
			val predicted = when (type) {
				0 -> 0
				1 -> a
				2 -> b
				3 -> (a + b) ushr 1
				else -> {
					val p = a + b - c
					val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
					if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
				}
			}
			val v = (x - predicted).toByte()
			out[i] = v
			sum += abs(v.toInt())
		}
		return sum
	}

	private fun chunk(out: Buffer, type: String, data: ByteArray) {
		val typeBytes = type.encodeToByteArray()
		out.writeInt(data.size).write(typeBytes).write(data).writeInt(crc32(typeBytes, data))
	}

	/** Adler-32 of [data] (zlib's checksum). */
	internal fun adler32(data: ByteArray): Int {
		var a = 1L; var b = 0L
		var i = 0
		while (i < data.size) {
			// 5552 bytes is the most that can be summed before the 32-bit sums must be reduced (zlib's NMAX).
			val end = minOf(data.size, i + 5552)
			while (i < end) { a += data[i].toInt() and 0xff; b += a; i++ }
			a %= 65521; b %= 65521
		}
		return ((b shl 16) or a).toInt()
	}

	private val CRC_TABLE = IntArray(256) { n ->
		var c = n
		repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
		c
	}

	/** CRC-32 of the parts in order (PNG's chunk checksum). */
	internal fun crc32(vararg parts: ByteArray): Int {
		var c = -1
		for (part in parts) for (byte in part) c = CRC_TABLE[(c xor byte.toInt()) and 0xff] xor (c ushr 8)
		return c.inv()
	}

	/** The Adler-32 of two byte runs from each one's checksum and the second's length. */
	internal fun combineAdler(first: Int, second: Int, secondLength: Long): Int {
		val base = 65521L
		val remainder = secondLength % base
		var sum1 = first.toLong() and 0xffff
		var sum2 = (remainder * sum1) % base
		sum1 += (second.toLong() and 0xffff) + base - 1
		sum2 += ((first.toLong() ushr 16) and 0xffff) + ((second.toLong() ushr 16) and 0xffff) + base - remainder
		if (sum1 >= base) sum1 -= base
		if (sum1 >= base) sum1 -= base
		if (sum2 >= base shl 1) sum2 -= base shl 1
		if (sum2 >= base) sum2 -= base
		return (sum1 or (sum2 shl 16)).toInt()
	}
}

/** [raw] deflated without a zlib header, ending on a sync flush (zlib's Z_SYNC_FLUSH), at the default level. */
internal expect fun deflateRawSyncFlush(raw: ByteArray): ByteArray

/** Runs [body] for every band index below [count]: in parallel where the platform allows, each band on its own. */
internal expect fun forEachBand(count: Int, body: (Int) -> Unit)
