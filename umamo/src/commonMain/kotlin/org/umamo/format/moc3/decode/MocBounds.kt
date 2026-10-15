package org.umamo.format.moc3.decode

/**
 * CountInfo and a section's bytes are independent on disk. A truncated or hostile file can name more
 * objects, vertices, or keyforms than the section stores. These reads fail with a parse error instead
 * of an index exception.
 */
internal fun mocTruncated(detail: String): Nothing =
	throw IllegalArgumentException("MOC3 section is truncated: $detail")

internal fun IntArray.mocAt(index: Int): Int =
	if (index in indices) this[index] else mocTruncated("int index $index of $size")

internal fun FloatArray.mocAt(index: Int): Float =
	if (index in indices) this[index] else mocTruncated("float index $index of $size")

internal fun FloatArray.mocSlice(start: Int, length: Int): FloatArray {
	if (start < 0 || length < 0 || start.toLong() + length > size.toLong()) {
		mocTruncated("float slice $start+$length of $size")
	}
	return copyOfRange(start, start + length)
}

internal fun ShortArray.mocSlice(start: Int, length: Int): ShortArray {
	if (start < 0 || length < 0 || start.toLong() + length > size.toLong()) {
		mocTruncated("short slice $start+$length of $size")
	}
	return copyOfRange(start, start + length)
}

internal fun IntArray.mocSlice(start: Int, length: Int): IntArray {
	if (start < 0 || length < 0 || start.toLong() + length > size.toLong()) {
		mocTruncated("int slice $start+$length of $size")
	}
	return copyOfRange(start, start + length)
}
