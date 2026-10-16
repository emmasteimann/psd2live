package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeelTest {
	private val w = 30; private val h = 20
	private fun png(f: (Int, Int) -> Int) = pngDataUrl(encodePng(w, h, IntArray(w * h) { f(it % w, it / w) }))

	/** One part whose art is two blobs (x 2..8 and x 20..26), with a mask over the right one. */
	private fun project(): StudioProject {
		val art = png { x, y -> if (y in 5..14 && (x in 2..8 || x in 20..26)) 0xFF3366CC.toInt() else 0 }
		val mask = png { x, _ -> if (x >= 15) MASK_ARGB else 0 }
		val src = png { _, _ -> -1 }
		return StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"T","source":"$src","width":$w,"height":$h,
			"parts":[{"id":"e","name":"Earrings","group":"Accessories","visible":true,"mask":"$mask","repairs":[],"cutout":"$art","extractionMask":"$art"}],"history":[]}""")
	}

	@Test fun peelLiftsTheMarkedPieceAndUndoPutsItBack() {
		val p = project()
		val (next, id) = StudioPeel.applySplit(p, "e", StudioPeel.cut(p, "e"), "Right earring")
		assertEquals(listOf("Earrings", "Right earring"), next.parts.map { it.name })
		val lifted = StudioImages.layer(next.part(id)!!.cutout, w, h); val rest = StudioImages.layer(next.part("e")!!.cutout, w, h)
		assertEquals(20, lifted.x); assertEquals(2, rest.x); assertEquals(7, rest.width)
		assertNull(next.part("e")!!.mask)
		assertEquals(1, (next.json["peels"] as kotlinx.serialization.json.JsonArray).size)
		assertTrue(next.part("e")!!.json["peelUndo"] is JsonObject)
		val back = StudioPeel.undo(next, "e")
		assertEquals(1, back.parts.size)
		assertEquals(p.part("e")!!.cutout, back.part("e")!!.cutout)
	}

	@Test fun peelNeedsAMaskOverTheArt() {
		val p = StudioEdits.setMask(project(), "e", png { x, y -> if (x in 12..14 && y < 3) MASK_ARGB else 0 })
		assertFailsWith<IllegalStateException> { StudioPeel.cut(p, "e") }
	}

	@Test fun splitApartFindsBothEarringsAndJoinBackRestores() {
		val p = project()
		val art = StudioPeel.partArt(p, "e")!!
		val f = StudioPeel.findIslands(art, w, h, gap = 0)
		assertEquals(2, f.pieces.size)
		assertEquals(setOf("Earrings (right)", "Earrings (left)"), StudioPeel.splitNames("Earrings", f.pieces).toSet())
		assertEquals("Earrings (right)", StudioPeel.splitNames("Earrings", f.pieces)[f.pieces.indexOf(f.pieces.minBy { it.cx })])
		val images = StudioPeel.pieceImages(f)
		assertEquals(art.count { (it ushr 24) != 0 }, images.sumOf { im -> im.count { (it ushr 24) != 0 } })
		val (split, ids) = StudioPeel.applySplitApart(p, "e", images.mapIndexed { i, im -> pngDataUrl(encodePng(w, h, im)) to "E$i" })
		assertEquals(listOf("E0", "E1"), split.parts.map { it.name }); assertEquals("e", ids[0])
		val joined = StudioPeel.joinBack(split, p.part("e")!!, ids)
		assertEquals(listOf("Earrings"), joined.parts.map { it.name })
		// A big gap joins them into one island.
		assertEquals(1, StudioPeel.findIslands(art, w, h, gap = 40).pieces.size)
	}
}
