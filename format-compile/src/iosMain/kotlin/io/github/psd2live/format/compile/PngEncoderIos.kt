package io.github.psd2live.format.compile

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
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_OK
import platform.zlib.Z_SYNC_FLUSH
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

// The iPad's own zlib (as the JVM's Deflater wraps zlib): raw deflate (window bits -15), memory level 8.
@OptIn(ExperimentalForeignApi::class)
internal actual fun deflateRawSyncFlush(raw: ByteArray): ByteArray = memScoped {
	val stream = alloc<z_stream>()
	check(deflateInit2_(stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) == Z_OK) {
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
					deflate(stream.ptr, Z_SYNC_FLUSH)
				}
				out.write(chunk, 0, chunk.size - stream.avail_out.toInt())
			} while (stream.avail_out.toInt() == 0)
		}
		out.readByteArray()
	} finally {
		deflateEnd(stream.ptr)
	}
}

internal actual fun forEachBand(count: Int, body: (Int) -> Unit) {
	for (index in 0 until count) body(index)
}
