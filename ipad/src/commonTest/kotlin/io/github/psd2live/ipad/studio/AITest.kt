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

class RedrawTest {
	@Test fun redrawKeepsTheOutlineAndTakesTheNewPaint(): Unit = kotlinx.coroutines.runBlocking {
		val w = 80; val h = 60
		// A red blob with grey specks of dust inside; the "editor" answers the same blob, clean blue, slightly shifted.
		val piece = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 20..59 && y in 15..44) (if ((x + y) % 7 == 0) 0xFF808080.toInt() else 0xFFCC2222.toInt()) else 0 }
		val old = StudioRedraw.transport
		StudioRedraw.transport = { png, _ ->
			val sheet = decodeImage(png)!!
			// Repaint everything that isn't green screen as clean blue, nudged 3 px right.
			val out = IntArray(sheet.argb.size) { 0xFF00FF00.toInt() }
			for (y in 0 until sheet.height) for (x in 3 until sheet.width) { val p = sheet.argb[y * sheet.width + x - 3]; if (p != 0xFF00FF00.toInt()) out[y * sheet.width + x] = 0xFF2244CC.toInt() }
			Pixels(sheet.width, sheet.height, out)
		}
		try {
			val (art, iou) = StudioRedraw.redraw(piece, w, h)
			assertTrue(iou > 0.9, "overlap $iou")
			for (i in art.indices) assertEquals(piece[i] ushr 24, art[i] ushr 24) // exactly the piece's own outline
			val c = art[30 * w + 40]; assertTrue((c and 0xFF) > 150 && ((c ushr 16) and 0xFF) < 100, "blue paint, got ${c.toString(16)}")
		} finally { StudioRedraw.transport = old }
	}

	@Test fun aRedrawThatChangesTheShapeIsRefused(): Unit = kotlinx.coroutines.runBlocking {
		val w = 80; val h = 60
		val piece = IntArray(w * h) { i -> if (i % w in 20..59 && i / w in 15..44) -1 else 0 }
		val old = StudioRedraw.transport
		StudioRedraw.transport = { png, _ -> val s = decodeImage(png)!!; Pixels(s.width, s.height, IntArray(s.argb.size) { i -> if (i % s.width < s.width / 4) -1 else 0xFF00FF00.toInt() }) }
		try { kotlin.test.assertFailsWith<IllegalStateException> { StudioRedraw.redraw(piece, w, h) } } finally { StudioRedraw.transport = old }
	}
}

class RepairAITest {
	private val w = 40; private val h = 40
	private fun png(f: (Int, Int) -> Int) = pngDataUrl(encodePng(w, h, IntArray(w * h) { f(it % w, it / w) }))
	private val PINK = 0xFFFF88AA.toInt()

	private fun project(): StudioProject {
		val src = png { _, _ -> -1 }; val art = png { x, _ -> if (x < 20) 0xFF334455.toInt() else 0 }
		val mask = png { x, y -> if (x in 10..14 && y in 10..14) MASK_ARGB else 0 }
		return StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"T","source":"$src","width":$w,"height":$h,"parts":[
			{"id":"back","name":"Back hair","group":"Hair","visible":true,"mask":null,"repairs":[]},
			{"id":"a","name":"Arm","group":"Body","visible":true,"mask":"$mask","repairs":[],"cutout":"$art","extractionMask":"$art"}],"history":[]}""")
	}

	@Test fun repairIsClippedToTheMaskAndBecomesAPatch(): Unit = kotlinx.coroutines.runBlocking {
		val old = StudioRepairAI.transport
		var sentBody = ByteArray(0)
		// The stand-in AI paints the whole image pink, at a different size (the studio must line it up).
		StudioRepairAI.transport = { path, body, _ ->
			sentBody = body
			assertEquals("/repair", path)
			200 to """{"candidate":"${pngDataUrl(encodePng(80, 80, IntArray(6400) { PINK }))}","model":"test"}"""
		}
		try {
			val p = project()
			val c = StudioRepairAI.generate(p, "a", "the hair behind the arm", "repair")
			assertTrue(c.scaled)
			val patch = StudioImages.layer(c.patch, w, h)
			assertEquals(PINK, patch.at(12, 12)); assertEquals(0, patch.at(30, 30)) // only inside the mask
			assertTrue(sentBody.decodeToString().contains("name=\"operation\"\r\n\r\nrepair"))
			val p2 = StudioRepairAI.approve(p, c, "back", "")
			assertEquals("back", p2.part("a")!!.repairs.single().str("targetPartId"))
			// The patch now shows under the arm as the back hair's art.
			assertTrue(StudioRender.layers(p2).any { it.partId == "back" })
			// An expression becomes a variant, the part's own art kept outside the mask.
			val e = StudioRepairAI.generate(p, "a", "smile", "expression")
			val v = StudioImages.layer(e.variantArt, w, h)
			assertEquals(PINK, v.at(12, 12)); assertEquals(0xFF334455.toInt(), v.at(2, 2))
			assertEquals(1, StudioFace.variants(StudioRepairAI.approve(p, e, null, "Smile").part("a")!!).size)
		} finally { StudioRepairAI.transport = old }
	}
}
