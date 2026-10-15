package org.umamo.format.binary

internal actual fun decodeLegacyCharsets(bytes: ByteArray): String? {
	for (charsetName in listOf("Windows-31J", "GB18030", "Big5")) {
		try {
			val decoder = java.nio.charset.Charset.forName(charsetName).newDecoder()
			decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
			decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
			val decoded = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
			if (!decoded.contains('�')) return decoded
		} catch (_: Exception) {
			// Try the next charset.
		}
	}
	return try {
		java.nio.charset.Charset.defaultCharset().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
	} catch (_: Exception) {
		null
	}
}
