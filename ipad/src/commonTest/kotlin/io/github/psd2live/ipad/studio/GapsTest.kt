package io.github.psd2live.ipad.studio

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GapsTest {
	private val w = 100; private val h = 100
	private fun png(f: (Int, Int) -> Int) = pngDataUrl(encodePng(w, h, IntArray(w * h) { f(it % w, it / w) }))
	private val TEAL = 0xFF208080.toInt(); private val GOLD = 0xFFE0B020.toInt()
	private fun hole(x: Int, y: Int) = x in 45..54 && y in 45..54

	/** A robe with a hole never drawn (behind the lantern), and the lantern in front covering it. */
	private fun project(): StudioProject {
		val robe = png { x, y -> if (x in 20..79 && y in 20..90 && !hole(x, y)) TEAL else 0 }
		val lantern = png { x, y -> if (x in 42..57 && y in 42..60) GOLD else 0 }
		val gap = png { x, y -> if (hole(x, y)) MASK_ARGB else 0 }
		return StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"T","source":"$robe","width":$w,"height":$h,"parts":[
			{"id":"robe","name":"Robe","group":"Clothing","visible":true,"mask":null,"repairs":[],"cutout":"$robe","extractionMask":"$robe","seeThroughTag":"topwear","gapMask":"$gap"},
			{"id":"lantern","name":"Lantern","group":"Accessories","visible":true,"mask":null,"repairs":[],"cutout":"$lantern","extractionMask":"$lantern"}],"history":[]}""")
	}

	@Test fun theBlobGrowsTheGapButStaysNearIt() {
		val gap = IntArray(w * h) { if (hole(it % w, it / w)) 255 else 0 }
		val b = StudioGaps.blob(gap, w, h)
		for (i in gap.indices) if (gap[i] == 255) assertEquals(1, b[i].toInt()) // always at least the gap
		assertEquals(1, b[50 * w + 57].toInt()) // grown a little
		assertEquals(0, b[5 * w + 5].toInt()) // not everywhere
	}

	@Test fun paintsTheHoleBehindTheLanternWithoutShowingTheModelTheLantern() = runBlocking {
		val p = project()
		var prompt = ""; var sawGold = false
		val old = StudioAI.transport
		// The stand-in AI: robe fabric wherever it may edit.
		StudioAI.transport = { image, mask, pr, _ ->
			prompt = pr
			val img = decodeImage(image)!!; val mk = decodeImage(mask!!)!!
			sawGold = img.argb.any { it == GOLD }
			Pixels(img.width, img.height, IntArray(img.argb.size) { i -> if ((mk.argb[i] ushr 24) < 128) TEAL else img.argb[i] })
		}
		try {
			assertEquals(listOf("robe"), StudioGaps.todo(p).map { it.id })
			val item = StudioGaps.paint(p, "robe")
			assertTrue(!sawGold, "the lantern covering the gap is hidden from the model")
			assertTrue(prompt.contains("fabric continuing behind") && prompt.contains("no object"))
			val applied = StudioGaps.apply(p, listOf(item))
			val robe = StudioImages.layer(applied.part("robe")!!.cutout, w, h)
			assertEquals(TEAL, robe.at(50, 50)) // the hole is filled
			assertEquals(TEAL, robe.at(25, 25)) // the robe's own art kept
			assertEquals(0, robe.full()[5 * w + 5]) // nothing painted outside the character
			assertNull(applied.part("robe")!!.json.str("gapMask"))
			assertTrue(StudioGaps.todo(applied).isEmpty())
			assertTrue(applied.part("robe")!!.json["peelUndo"] is kotlinx.serialization.json.JsonObject) // Undo last peel puts it back
		} finally { StudioAI.transport = old }
	}
}
