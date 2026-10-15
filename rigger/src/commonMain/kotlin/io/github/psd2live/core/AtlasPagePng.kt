package io.github.psd2live.core

import okio.Buffer

/**
 * PNG encodings of an atlas page.
 *
 * [canonicalPng] is what exports write (ImageIO's, on the desktop). The editor's runtime bundle instead uses a strip encoding:
 * the page's rows are filtered and deflated in strips of [STRIP_ROWS], each strip on its own (a fresh
 * compressor, ending on a full flush), and each strip becomes one IDAT chunk. The chunks of a zlib stream
 * may be split anywhere, and independent deflate segments that each end byte-aligned on a non-final block
 * concatenate into one valid stream; the stream's Adler-32 is combined from the strips'. So a page that
 * differs from an earlier page only in a few rows re-encodes only those strips, and a whole page encodes
 * its strips in parallel. The pixels decode exactly as the canonical PNG's do - both are lossless RGBA8.
 *
 * Shared code (it runs on the iPad too): only deflating and parallelism are the platform's ([RiggerPlatform]).
 */
object AtlasPagePng {
	const val STRIP_ROWS = 16
	private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

	/** One encoded strip: its IDAT chunk (length, type, data, CRC), the Adler-32 and length of its filtered rows. */
	class Strip(val chunk: ByteArray, val adler: Int, val rawLength: Long)

	class Strips(val width: Int, val height: Int, val strips: List<Strip>)

	/** Strips of [image]; those of [reuse] (a page of the same size) are kept wherever no row in [dirtyRows] falls. */
	fun strips(image: RigImage, reuse: Strips?, dirtyRows: BitSet?): Strips {
		val width = image.pixelWidth; val height = image.pixelHeight
		val count = (height + STRIP_ROWS - 1) / STRIP_ROWS
		val pixels = image.argbPixels()
		val result = arrayOfNulls<Strip>(count)
		val encode = (0 until count).filter { index ->
			val start = index * STRIP_ROWS; val end = minOf(height, start + STRIP_ROWS)
			val clean = reuse != null && reuse.width == width && reuse.height == height && dirtyRows != null &&
				dirtyRows.nextSetBit(start).let { it < 0 || it >= end }
			if (clean) result[index] = reuse!!.strips[index]
			!clean
		}.toIntArray()
		checkInterrupted()
		parallelFor(encode) { index ->
			val start = index * STRIP_ROWS
			result[index] = strip(pixels, width, start, minOf(height, start + STRIP_ROWS))
		}
		return Strips(width, height, result.map { requireNotNull(it) })
	}

	/** The PNG file of [strips]. */
	fun assemble(width: Int, height: Int, strips: Strips): ByteArray {
		require(strips.width == width && strips.height == height)
		val header = Buffer().writeInt(width).writeInt(height).write(byteArrayOf(8, 6, 0, 0, 0)).readByteArray()
		var adler = 1
		for (strip in strips.strips) adler = combineAdler(adler, strip.adler, strip.rawLength)
		// The zlib header leads; an empty final stored block and the checksum close the stream.
		val zlibHeader = chunk("IDAT", byteArrayOf(0x78, 0x01))
		val trailer = chunk("IDAT", Buffer().write(byteArrayOf(0x01, 0x00, 0x00, 0xff.toByte(), 0xff.toByte())).writeInt(adler).readByteArray())
		val ihdr = chunk("IHDR", header)
		val iend = chunk("IEND", ByteArray(0))
		val out = Buffer()
		out.write(SIGNATURE).write(ihdr).write(zlibHeader)
		for (strip in strips.strips) out.write(strip.chunk)
		out.write(trailer).write(iend)
		return out.readByteArray()
	}

	/** Rows [start] until [end], each with the Sub filter, deflated on their own. */
	private fun strip(pixels: IntArray, width: Int, start: Int, end: Int): Strip {
		val stride = width * 4 + 1
		val raw = ByteArray(stride * (end - start))
		for (y in start until end) {
			var offset = (y - start) * stride
			raw[offset++] = 1
			var previous = 0
			val row = y * width
			for (x in 0 until width) {
				val pixel = pixels[row + x]
				val r = pixel ushr 16 and 0xff; val g = pixel ushr 8 and 0xff; val b = pixel and 0xff; val a = pixel ushr 24
				raw[offset] = (r - (previous ushr 16 and 0xff)).toByte()
				raw[offset + 1] = (g - (previous ushr 8 and 0xff)).toByte()
				raw[offset + 2] = (b - (previous and 0xff)).toByte()
				raw[offset + 3] = (a - (previous ushr 24)).toByte()
				offset += 4
				previous = pixel
			}
		}
		return Strip(chunk("IDAT", deflateRawFullFlush(raw, 1)), adler32(raw), raw.size.toLong())
	}

	private fun chunk(type: String, data: ByteArray): ByteArray {
		val typeBytes = type.encodeToByteArray()
		val crc = crc32(data, crc32(typeBytes)).toInt()
		return Buffer().writeInt(data.size).write(typeBytes).write(data).writeInt(crc).readByteArray()
	}

	private val CRC_TABLE = IntArray(256) { n ->
		var c = n
		repeat(8) { c = if (c and 1 != 0) (0xedb88320.toInt() xor (c ushr 1)) else c ushr 1 }
		c
	}

	/** CRC-32 (PNG's, as java.util.zip.CRC32) of [data], continuing from [start] (a CRC of earlier bytes). */
	fun crc32(data: ByteArray, start: Long = 0): Long {
		var c = start.toInt().inv()
		for (byte in data) c = CRC_TABLE[(c xor byte.toInt()) and 0xff] xor (c ushr 8)
		return c.inv().toLong() and 0xffffffffL
	}

	/** Adler-32 (zlib's, as java.util.zip.Adler32) of [data]. */
	fun adler32(data: ByteArray): Int {
		var a = 1L; var b = 0L
		var i = 0
		while (i < data.size) {
			// 5552 bytes is the most that cannot overflow 32 unsigned bits before the modulo (zlib's NMAX).
			val end = minOf(data.size, i + 5552)
			while (i < end) { a += data[i++].toInt() and 0xff; b += a }
			a %= 65521; b %= 65521
		}
		return ((b shl 16) or a).toInt()
	}

	/** zlib's adler32_combine: the checksum of two byte runs from each one's and the second's length. */
	fun combineAdler(first: Int, second: Int, secondLength: Long): Int {
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
