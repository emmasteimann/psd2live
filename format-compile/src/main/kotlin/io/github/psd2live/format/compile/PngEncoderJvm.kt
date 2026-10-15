package io.github.psd2live.format.compile

import java.io.ByteArrayOutputStream
import java.util.stream.IntStream
import java.util.zip.Deflater

internal actual fun deflateRawSyncFlush(raw: ByteArray): ByteArray {
	val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
	try {
		deflater.setInput(raw)
		val output = ByteArrayOutputStream(raw.size / 4 + 64)
		val buffer = ByteArray(64 * 1024)
		while (true) {
			val written = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
			output.write(buffer, 0, written)
			// The flush is complete once it no longer fills the buffer.
			if (written < buffer.size) break
		}
		return output.toByteArray()
	} finally {
		deflater.end()
	}
}

internal actual fun forEachBand(count: Int, body: (Int) -> Unit) {
	IntStream.range(0, count).parallel().forEach(body)
}
