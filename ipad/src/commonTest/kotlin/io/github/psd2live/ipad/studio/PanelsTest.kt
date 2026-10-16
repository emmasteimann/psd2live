package io.github.psd2live.ipad.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PanelsTest {
	private val w = 40; private val h = 30
	private fun png(f: (Int, Int) -> Int) = pngDataUrl(encodePng(w, h, IntArray(w * h) { f(it % w, it / w) }))
	private val RED = 0xFFCC2222.toInt(); private val BLUE = 0xFF2244CC.toInt()

	/** A drawing of a red square (x 10..19) over a blue band (x 5..34), as two parts: band behind, square in front. */
	private fun project(squareFirst: Boolean = false, extra: String = ""): StudioProject {
		val drawing = png { x, y -> if (x in 10..19 && y in 10..19) RED else if (x in 5..34 && y in 8..21) BLUE else -1 }
		val band = png { x, y -> if (x in 5..34 && y in 8..21) BLUE else 0 }
		val square = png { x, y -> if (x in 10..19 && y in 10..19) RED else 0 }
		val bandJson = """{"id":"band","name":"Band","group":"Body","visible":true,"mask":null,"repairs":[],"cutout":"$band","extractionMask":"$band"}"""
		val squareJson = """{"id":"sq","name":"Square","group":"Custom","visible":true,"mask":null,"repairs":[],"cutout":"$square","extractionMask":"$square"$extra}"""
		val parts = if (squareFirst) "$squareJson,$bandJson" else "$bandJson,$squareJson"
		return StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"T","source":"$drawing","width":$w,"height":$h,"parts":[$parts],"history":[]}""")
	}

	@Test fun motionCountsTheHolesItOpens() {
		val p = project()
		val m = StudioChecks.motion(p, "sq", 5, 0, highlight = true)
		// The square moved right 5 px: its left 5 columns (10 tall) are holes in the band area... but the band is
		// behind and covers them, so no holes.
		assertEquals(0, m.gaps)
		val m2 = StudioChecks.motion(p, "band", 0, 15, highlight = true)
		assertTrue(m2.gaps > 0); assertNotNull(m2.gapMask)
	}

	@Test fun cleanupTrimsToTheKeepMaskAndUndoes() {
		val p = StudioEdits.setMask(project(), "sq", png { x, _ -> if (x < 15) MASK_ARGB else 0 })
		val r = StudioRepair.cleanup(p, "sq", 0, StudioRepair.Leftover.DROP, null)
		val p2 = StudioRepair.applyCleanup(p, "sq", r)
		val art = StudioImages.layer(p2.part("sq")!!.cutout, w, h)
		assertEquals(10, art.x); assertEquals(5, art.width)
		assertEquals(50, r.removed)
		val back = StudioRepair.undoCleanup(p2, "sq")
		assertEquals(p.part("sq")!!.cutout, back.part("sq")!!.cutout)
	}

	@Test fun cleanupCanMoveOrReturnTheTrimmedPixels() {
		val p = StudioEdits.setMask(project(), "sq", png { x, _ -> if (x < 15) MASK_ARGB else 0 })
		// Transfer: the right half of the square goes into the band.
		val t = StudioRepair.cleanup(p, "sq", 0, StudioRepair.Leftover.TRANSFER, "band")
		val pt = StudioRepair.applyCleanup(p, "sq", t)
		// The trimmed half leaves the square, and goes under the band's own art (as the web does).
		assertEquals(50, t.removed)
		assertEquals(0, StudioImages.layer(pt.part("sq")!!.cutout, w, h).at(17, 15))
		assertEquals("band", t.targetChange?.first)
		val undone = StudioRepair.undoCleanup(pt, "sq")
		assertEquals(p.part("band")!!.cutout, undone.part("band")!!.cutout)
		// Return: the drawing's pixels outside the keep-mask go to the band as hidden art.
		val r = StudioRepair.cleanup(p, "sq", 0, StudioRepair.Leftover.RETURN, "band")
		val pr = StudioRepair.applyCleanup(p, "sq", r)
		assertEquals(1, pr.part("sq")!!.repairs.size)
		assertEquals("band", pr.part("sq")!!.repairs[0].str("targetPartId"))
		// The band now shows the red where the square was trimmed (the patch sits under the band's own art? no: over
		// nothing - the band's own pixels there are blue, so check the patch itself).
		assertEquals(RED, StudioImages.layer(pr.part("sq")!!.repairs[0].str("patch"), w, h).at(17, 15))
	}

	@Test fun cleanupRefusesAMaskThatTrimsNothingOrEverything() {
		val all = StudioEdits.setMask(project(), "sq", png { _, _ -> MASK_ARGB })
		assertFailsWith<IllegalStateException> { StudioRepair.cleanup(all, "sq", 0, StudioRepair.Leftover.DROP, null) }
		val none = StudioEdits.setMask(project(), "sq", png { x, _ -> if (x > 30) MASK_ARGB else 0 })
		assertFailsWith<IllegalStateException> { StudioRepair.cleanup(none, "sq", 0, StudioRepair.Leftover.DROP, null) }
	}

	@Test fun cloneCopiesTheDrawingIntoAPartBehind() {
		val p = StudioEdits.setMask(project(), "sq", png { x, y -> if (x in 12..14 && y in 12..14) MASK_ARGB else 0 })
		// From the band's blue at (30, 15) to (13, 13): the masked square gets blue.
		val (next, target) = StudioRepair.clone(p, "sq", Pt(30.0, 15.0), Pt(13.0, 13.0), "band", 1.0)
		assertEquals("Band", target)
		val patch = StudioImages.layer(next.part("sq")!!.repairs.last().str("patch"), w, h)
		assertEquals(BLUE, patch.at(13, 13)); assertEquals(0, patch.at(20, 20))
		assertEquals(0, StudioRepair.undoClone(next, "sq").part("sq")!!.repairs.size)
		// Into a part in front: refused.
		assertFailsWith<IllegalStateException> { StudioRepair.clone(StudioEdits.setMask(project(), "band", png { x, _ -> if (x == 6) MASK_ARGB else 0 }), "band", Pt(30.0, 15.0), Pt(6.0, 15.0), "sq", 1.0) }
	}

	@Test fun preflightFindsBlockers() {
		val p = project()
		val issues = StudioChecks.preflight(p)
		assertTrue(issues.none { it.blocker }, issues.filter { it.blocker }.joinToString { it.message })
		val dup = StudioEdits.rename(p, "sq", "Band")
		assertTrue(StudioChecks.preflight(dup).any { it.code == "layer-name" && it.blocker })
		val checked = StudioChecks.setHandoff(p, "edges", true)
		assertTrue(StudioChecks.preflight(checked).none { it.code == "edge-review" })
	}

	@Test fun matchFindsTheWrongLayerInFrontAndFixesIt() {
		// The square is listed behind the band, so the band covers it - Match should say "Square in front of Band".
		val p = project(squareFirst = true)
		val r = StudioMatch.compare(p)
		val q = r.proposals.firstOrNull { it.a == "sq" && it.b == "band" }
		assertNotNull(q, r.proposals.joinToString { "${it.kind} ${it.aName}>${it.bName}" })
		assertEquals(StudioMatch.Kind.MOVE, q.kind)
		val fixed = StudioMatch.apply(p, q).project
		assertEquals(listOf("band", "sq"), fixed.parts.map { it.id })
		assertTrue(StudioMatch.compare(fixed).proposals.isEmpty())
	}
}
