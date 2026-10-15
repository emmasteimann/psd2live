package org.umamo.format.binary

// Legacy PSD layer names (Shift-JIS, GB18030, Big5) keep their UTF-8 reading on the iPad for now; Foundation's
// string encodings can decode them later.
internal actual fun decodeLegacyCharsets(bytes: ByteArray): String? = null
