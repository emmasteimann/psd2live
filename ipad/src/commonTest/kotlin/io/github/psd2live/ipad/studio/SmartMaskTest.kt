package io.github.psd2live.ipad.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmartMaskTest {
	private fun project(w: Int, h: Int) = StudioProject.create("S", encodePng(w, h, IntArray(w * h) { -1 }), w, h)

	@Test fun pointsAndMasksMapThroughTheRegion() {
		val oldE = StudioSmartMask.encodeFn; val oldP = StudioSmartMask.predictFn
		var sentPoints = FloatArray(0); var encodes = 0
		StudioSmartMask.encodeFn = { encodes++ }
		// The stand-in: candidate 0 is the middle quarter of its 256 grid (the 2nd is empty, the 3rd everything).
		StudioSmartMask.predictFn = { p, _ ->
			sentPoints = p
			val l = FloatArray(3 * 256 * 256) { i -> val c = i / 65536; val x = i % 256; val y = (i / 256) % 256
				when (c) { 0 -> if (x in 64..191 && y in 64..191) 5f else -5f; 1 -> -5f; else -> 5f } }
			l to floatArrayOf(0.9f, 0.1f, 0.5f)
		}
		try {
			val p = project(400, 300)
			val c = StudioSmartMask.suggest(p, listOf(Triple(150.0, 150.0, 1)), intArrayOf(100, 100, 200, 100))
			assertEquals(1, encodes)
			// The point, inside the region (100..300 × 100..200), in the model's 1024 picture.
			assertEquals(256f, sentPoints[0]); assertEquals(512f, sentPoints[1])
			val best = c.first(); assertEquals(0.9f, best.score)
			// The middle quarter of the region: x 150..250, y 125..175.
			assertTrue((best.mask[150 * 400 + 200] ushr 24) != 0); assertEquals(0, best.mask[110 * 400 + 110]); assertEquals(0, best.mask[150 * 400 + 50])
			assertTrue(best.pixels in 4500..5500, "${best.pixels}")
			// Same picture and region: not read again.
			StudioSmartMask.suggest(p, listOf(Triple(160.0, 150.0, 1)), intArrayOf(100, 100, 200, 100)); assertEquals(1, encodes)
			// Every mask empty: said plainly.
			StudioSmartMask.predictFn = { _, _ -> FloatArray(3 * 65536) { -1f } to floatArrayOf(0.5f, 0.5f, 0.5f) }
			assertFailsWith<EmptyMasks> { StudioSmartMask.suggest(p, listOf(Triple(150.0, 150.0, 1)), null) }
		} finally { StudioSmartMask.encodeFn = oldE; StudioSmartMask.predictFn = oldP }
	}
}
