package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioProjectTest {
	private fun png(w: Int, h: Int, fill: (Int, Int) -> Int) = pngDataUrl(encodePng(w, h, IntArray(w * h) { fill(it % w, it / w) }))

	private fun sample(): StudioProject {
		val source = png(8, 6) { _, _ -> 0xFF336699.toInt() }
		val text = """{"format":"layerloom-project","version":1,"id":"p1","name":"Test","source":"$source","width":8,"height":6,
			"eyePlan":{"version":1,"sides":{}},"futureField":[1,2,3],
			"parts":[{"id":"a","name":"Hair","group":"Hair","visible":true,"mask":null,"repairs":[],"swing":{"pivot":[1,2]}}],"history":[]}"""
		return StudioProject.parse(text)
	}

	@Test fun unknownFieldsSurviveAnEditAndASave() {
		val edited = StudioEdits.rename(sample(), "a", "Front hair")
		val reread = StudioProject.parse(edited.fileText())
		assertEquals("Front hair", reread.part("a")!!.name)
		assertEquals(Json.parseToJsonElement("[1,2,3]"), reread.json["futureField"])
		assertEquals(Json.parseToJsonElement("""{"pivot":[1,2]}"""), reread.part("a")!!.json["swing"])
		assertEquals(Json.parseToJsonElement("""{"version":1,"sides":{}}"""), reread.json["eyePlan"])
		assertEquals(1, reread.historyCount)
		assertEquals(JsonPrimitive("layerloom-project"), Json.parseToJsonElement(edited.fileText()).jsonObject["format"])
	}

	@Test fun openChecksTheFileLikeTheWebDoes() {
		assertFailsWith<IllegalArgumentException> { StudioProject.parse("""{"format":"other","version":1}""") }
		assertFailsWith<IllegalArgumentException> { StudioProject.parse("not json") }
	}

	@Test fun extractTakesTheMaskedPixelsAndLeavesAHole() {
		val p0 = sample()
		val mask = png(8, 6) { x, _ -> if (x < 4) MASK_ARGB else 0 }
		val p1 = StudioEdits.setMask(p0, "a", mask)
		val p2 = StudioEdits.extract(p1, "a")(p1)
		val art = StudioImages.layer(p2.part("a")!!.cutout, 8, 6)
		assertEquals(0, art.x); assertEquals(4, art.width); assertEquals(6, art.height)
		val rest = StudioRender.remaining(p2)
		assertEquals(4, rest.x); assertEquals(4, rest.width)
		// Together they make the whole drawing again.
		val all = StudioRender.composite(p2)
		assertTrue(all.all { it == 0xFF336699.toInt() })
	}

	@Test fun blendMatchesSourceOver() {
		assertEquals(0xFF336699.toInt(), StudioRender.blend(0xFFFFFFFF.toInt(), 0xFF336699.toInt()))
		val half = StudioRender.blend(0xFF000000.toInt(), 0x80FFFFFF.toInt())
		assertEquals(0xFF, half ushr 24); assertTrue(((half ushr 16) and 0xFF) in 127..129)
		assertEquals(0x40FF0000, StudioRender.blend(0, 0x40FF0000))
	}

	@Test fun polygonFillsInsideOnly() {
		val m = MaskEdit("a", 10, 10, null)
		m.polygon(listOf(2f to 2f, 8f to 2f, 8f to 8f, 2f to 8f), erase = false)
		assertEquals(MASK_ARGB, m.pixels[5 * 10 + 5]); assertEquals(0, m.pixels[0]); assertEquals(0, m.pixels[9 * 10 + 9])
		assertEquals(36, m.pixels.count { it != 0 })
		m.undo(); assertTrue(m.isEmpty)
	}

	@Test fun edgeBrushStopsAtTheLine() {
		// Left half pale, right half dark: the fill from the pale side stays pale.
		val art = Layer.trimmed(20, 10, IntArray(200) { if (it % 20 < 10) 0xFFEEEEEE.toInt() else 0xFF202020.toInt() })
		val m = MaskEdit("a", 20, 10, null)
		m.edge(art, 5f, 5f, 30f, 35, erase = false); m.endStroke("Edge")
		assertTrue((0 until 200).all { (m.pixels[it] != 0) == (it % 20 < 10) })
	}

	@Test fun featherSoftensTheEdge() {
		val a = IntArray(40) { if (it % 20 < 10) 255 else 0 }
		StudioRender.blurAlpha(a, 20, 2, 4)
		assertTrue(a[9] in 100..200); assertTrue(a[0] > 240); assertTrue(a[19] < 15)
	}

	@Test fun emptyMaskSavesAsNull() { assertNull(MaskEdit("a", 4, 4, null).dataUrl()) }
}
