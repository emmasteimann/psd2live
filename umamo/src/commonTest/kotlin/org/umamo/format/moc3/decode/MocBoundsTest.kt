package org.umamo.format.moc3.decode

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MocBoundsTest {
	@Test
	fun shortGeometryTableIsAParseError() {
		assertFailsWith<IllegalArgumentException> { FloatArray(2).mocSlice(0, 4) }
		assertFailsWith<IllegalArgumentException> { IntArray(1).mocAt(3) }
	}
}
