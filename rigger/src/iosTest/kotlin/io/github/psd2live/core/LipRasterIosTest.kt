package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Core Graphics draws the lip triangles as Java2D does: y down, the colour straight, outside left clear. */
class LipRasterIosTest {
	@Test
	fun trianglesFillWithTheirColourYDown() {
		// One triangle in the top-left half of a 20 x 20 image: (2,2) (17,2) (2,17).
		val argb = rasterizeLipTriangles(20, 20, floatArrayOf(2f, 2f, 17f, 2f, 2f, 17f), intArrayOf(0, 1, 2), 0xC03040)
		val inside = argb[5 * 20 + 5]
		assertEquals(0xFFC03040.toInt(), inside, "inside the triangle: opaque, its colour")
		assertEquals(0, argb[18 * 20 + 18], "the bottom-right corner is outside: clear")
		assertEquals(0, argb[0], "beyond the 1.5 px stroke: clear")
		// The stroke's round edge reaches just past the outline: partly covered, colour unpremultiplied back.
		val edge = argb[1 * 20 + 9]
		assertTrue((edge ushr 24) in 1..254, "antialiased edge: ${(edge ushr 24)}")
		// Premultiplied at low alpha and divided back, a channel may lose a level or two (Java2D's does too).
		for ((shift, want) in listOf(16 to 0xC0, 8 to 0x30, 0 to 0x40)) {
			assertTrue(kotlin.math.abs((edge ushr shift and 0xff) - want) <= 3, "edge colour stays the stroke's: ${edge.toString(16)}")
		}
	}
}
