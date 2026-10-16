package io.github.psd2live.ipad.studio

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AITest {
	private val w = 64; private val h = 64
	private fun png(f: (Int, Int) -> Int) = pngDataUrl(encodePng(w, h, IntArray(w * h) { f(it % w, it / w) }))
	private val TEAL = 0xFF208080.toInt(); private val GOLD = 0xFFE0B020.toInt()

	/** A hat (teal) with a gold charm on it (x 28..35, y 28..35), all in one part, and the charm masked. */
	private fun project(): StudioProject {
		val art = png { x, y -> if (x in 28..35 && y in 28..35) GOLD else if (y in 20..44) TEAL else 0 }
		val mask = png { x, y -> if (x in 28..35 && y in 28..35) MASK_ARGB else 0 }
		return StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"T","source":"$art","width":$w,"height":$h,"parts":[
			{"id":"hat","name":"Hat","group":"Accessories","visible":true,"mask":"$mask","repairs":[],"cutout":"$art","extractionMask":"$art"}],"history":[]}""")
	}

	@Test fun resampleKeepsColoursAndShape() {
		val img = IntArray(100) { if (it % 10 < 5) TEAL else GOLD }
		val small = StudioAI.resample(img, 10, 10, 0, 0, 10, 10, 2, 2)
		assertEquals(TEAL, small[0]); assertEquals(GOLD, small[1])
		val big = StudioAI.resample(img, 10, 10, 0, 0, 10, 10, 40, 40)
		assertEquals(TEAL, big[0]); assertEquals(GOLD, big[39])
		val sq = StudioAI.squareAround(intArrayOf(0, 0, 10, 10), 64, 64)
		assertTrue(sq[0] >= 0 && sq[1] >= 0 && sq[0] + sq[2] <= 64)
	}

	@Test fun repaintKeepsWhatTheAIPaintedUnderThePiece() = runBlocking {
		val old = StudioAI.transport
		var sentPrompt = ""
		// The stand-in AI: the hat continues where the charm was (teal everywhere the hat is), magenta elsewhere.
		StudioAI.transport = { image, _, prompt, _ ->
			sentPrompt = prompt
			val px = decodeImage(image)!!
			Pixels(px.width, px.height, IntArray(px.argb.size) { i -> val p = px.argb[i]; if (p == 0xFFFF00FF.toInt()) p else TEAL })
		}
		try {
			val r = StudioAI.peelSplit(project(), "hat", "Charm", "the hat's teal band")
			assertTrue(sentPrompt.contains("Remove the Charm") && sentPrompt.contains("teal band"))
			val rest = StudioImages.layer(r.cut.rest, w, h); val lifted = StudioImages.layer(r.cut.lifted, w, h)
			assertEquals(GOLD, lifted.at(31, 31))
			// Under the charm the hat is now teal and solid (repainted), not a hole.
			val p = rest.at(31, 31)
			assertEquals(TEAL and 0xFFFFFF, p and 0xFFFFFF); assertTrue((p ushr 24) > 200, "alpha ${p ushr 24}")
			assertEquals(TEAL, rest.at(10, 30)) // the rest of the hat untouched
		} finally { StudioAI.transport = old }
	}
}
