package io.github.psd2live.ipad.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplodeTest {
	private val GREEN = 0xFF00FF00.toInt(); private val TEAL = 0xFF208080.toInt(); private val GOLD = 0xFFE0B020.toInt()

	/** A green sheet: a teal square top left, a gold bar top right, a teal blob below, a 3 px speck, and two squares joined by a thin bridge. */
	private fun sheet(): Pixels {
		val w = 300; val h = 260
		return Pixels(w, h, IntArray(w * h) { i ->
			val x = i % w; val y = i / w
			when {
				x in 20..69 && y in 20..69 -> TEAL
				x in 200..279 && y in 30..49 -> GOLD
				x in 30..89 && y in 160..219 -> TEAL
				x in 150..152 && y in 150..152 -> TEAL
				x in 180..204 && y in 180..209 -> GOLD
				x in 205..214 && y in 194..195 -> GOLD
				x in 215..239 && y in 180..209 -> TEAL
				else -> GREEN
			}
		})
	}

	@Test fun cutKeysTheGreenAndNumbersPiecesRowByRow() {
		val cut = ExplodeCut.cut(sheet())
		assertEquals(4, cut.pieces.size, "three shapes and the touching pair; the speck is dropped")
		val (a, b, c, d) = cut.pieces
		assertEquals(listOf(20, 20, 70, 70), a.box.toList(), "top row, left first")
		assertEquals(200, b.box[0])
		assertEquals(30, c.box[0], "bottom row")
		assertEquals(listOf(180, 180, 240, 210), d.box.toList(), "the touching pair cut as one")
		assertEquals(0, (a.argb[0] ushr 24) xor 0xFF, "no green left in the piece")
		assertEquals(TEAL and 0xFFFFFF, a.argb[25 * a.w + 25] and 0xFFFFFF)
	}

	@Test fun splitParts() {
		val cut = ExplodeCut.cut(sheet())
		val pair = cut.pieces.last()
		val after = ExplodeCut.split(cut, pair.n)
		assertEquals(5, after.pieces.size)
		val halves = after.pieces.takeLast(2)
		assertEquals(listOf(4, 5), halves.map { it.n })
		assertTrue(halves.all { it.w in 25..35 }, "split on the bridge: ${halves.map { it.w }}")
	}

	@Test fun combineAndPlace() {
		val cut = ExplodeCut.cut(sheet())
		val (art, w, h) = assertNotNull(ExplodeCut.combine(cut, listOf(0, 1)))
		assertEquals(260, w); assertEquals(50, h)
		val one = assertNotNull(ExplodeCut.combine(cut, listOf(0)))
		val placed = ExplodeCut.place(one.first, one.second, one.third, intArrayOf(100, 100, 125, 125), 200, 200)
		val b = assertNotNull(ExplodeCut.bounds(placed, 200, 200))
		assertTrue(b.zip(listOf(100, 100, 125, 125)).all { (got, want) -> kotlin.math.abs(got - want) <= 1 }, "placed into the box: ${b.toList()}")
		assertNull(ExplodeCut.bounds(IntArray(16), 4, 4))
		assertTrue(art.any { (it ushr 24) == 0xFF })
	}

	@Test fun promptListsVisibleThenUnderneath() {
		val p = ExplodeCut.prompt(listOf(ExplodeCut.Part("Hair", "the hair"), ExplodeCut.Part("Neck", "the neck", under = true), ExplodeCut.Part("Hat", "")))
		assertTrue("1. the hair\n2. Hat" in p)
		assertTrue("UNDERNEATH" in p && "3. the neck" in p)
		assertTrue("UNDERNEATH" !in ExplodeCut.prompt(listOf(ExplodeCut.Part("Hair", "the hair"))))
	}

	@Test fun partsAndSheetLiveInTheProject() {
		val src = pngDataUrl(encodePng(8, 8, IntArray(64) { TEAL }))
		var project = StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"T","source":"$src","width":8,"height":8,"parts":[
			{"id":"a","name":"Hair","group":"Hair","visible":true,"mask":null,"repairs":[],"cutout":"$src"}],"history":[]}""")
		assertEquals(7, ExplodeCut.parts(project).size, "one part: the starter set")
		project = ExplodeCut.withParts(project, listOf(ExplodeCut.Part("Hair", "the hair", under = false)))
		project = ExplodeCut.withSheet(project, encodePng(2, 2, IntArray(4) { GREEN }))
		assertEquals(listOf("Hair"), ExplodeCut.parts(project).map { it.name })
		assertNotNull(ExplodeCut.sheet(project))
		assertEquals(listOf(0, 0, 8, 8), ExplodeCut.boxFor(project, "hair")?.toList())
		val added = ExplodeCut.addParts(project, listOf("Hair" to IntArray(64) { GOLD }, "Hat" to IntArray(64)))
		assertEquals(listOf("Hair", "Hair", "Hat"), added.parts.map { it.name }, "the new Hair right above the old, Hat on top")
	}
}
