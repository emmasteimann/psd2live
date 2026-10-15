package org.umamo.format.binary

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ByteReaderBoundsTest {
	@Test
	fun readPastEndIsAParseError() {
		val reader = ByteReader(byteArrayOf(1, 2, 3, 4))
		assertFailsWith<IllegalArgumentException> { reader.readBytes(5) }
		assertFailsWith<IllegalArgumentException> { reader.u8(4) }
	}
}
