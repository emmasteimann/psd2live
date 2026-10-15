package io.github.psd2live.ipad.studio

import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioPsdTest {
	@Test fun writtenPsdReadsBackWithTheSharedReader() {
		val w = 12; val h = 9
		val bottom = Layer.trimmed(w, h, IntArray(w * h) { 0xFF102030.toInt() })
		val top = Layer.trimmed(w, h, IntArray(w * h) { i -> if (i % w in 3..6 && i / w in 2..4) 0x80FF8040.toInt() else 0 })
		val bytes = StudioPsd.write(w, h, listOf(StudioPsd.PsdLayer("Remaining artwork", bottom, false), StudioPsd.PsdLayer("前髪 bangs", top, true)), IntArray(w * h) { -1 })
		val art = PsdReader.read(bytes)
		assertEquals(w, art.widthPx); assertEquals(h, art.heightPx)
		assertEquals(listOf("Remaining artwork", "前髪 bangs"), art.layers.map { it.name })
		val t = art.layers[1]
		assertEquals(3, t.bounds.left); assertEquals(2, t.bounds.top); assertEquals(4, t.bounds.width); assertEquals(3, t.bounds.height)
		assertTrue(!t.visible); assertTrue(art.layers[0].visible)
		assertEquals(0x80, t.raster.rgba[3].toInt() and 0xFF); assertEquals(0xFF, t.raster.rgba[0].toInt() and 0xFF); assertEquals(0x40, t.raster.rgba[2].toInt() and 0xFF)
	}

	@Test fun importMakesOnePartPerLayerAndExportGivesThemBack() {
		val w = 10; val h = 10
		val a = Layer.trimmed(w, h, IntArray(w * h) { i -> if (i % w < 5) 0xFFAA0000.toInt() else 0 })
		val b = Layer.trimmed(w, h, IntArray(w * h) { i -> if (i / w < 3) 0xFF00AA00.toInt() else 0 })
		val psd = StudioPsd.write(w, h, listOf(StudioPsd.PsdLayer("Body", a, false), StudioPsd.PsdLayer("Hair front", b, false)), IntArray(w * h) { -1 })
		val project = StudioPsd.import(psd, "quill.psd")
		assertEquals("quill", project.name)
		assertEquals(listOf("Body", "Hair front"), project.parts.map { it.name })
		assertEquals(listOf("Body", "Hair"), project.parts.map { it.group })
		// Every pixel is in a layer, so nothing is left of the drawing.
		assertTrue(StudioRender.remaining(project).isEmpty)
		val back = PsdReader.read(StudioPsd.export(project))
		assertEquals(listOf("Remaining artwork", "Body", "Hair front"), back.layers.map { it.name })
		assertEquals(5, back.layers[1].bounds.width); assertEquals(3, back.layers[2].bounds.height)
	}

	@Test fun packBitsRoundTrips() {
		val data = ByteArray(300) { (if (it < 140) 7 else it % 5).toByte() }
		val packed = StudioPsd.packBits(data)
		val out = ArrayList<Byte>(); var i = 0
		while (i < packed.size) {
			val n = packed[i++].toInt()
			if (n >= 0) { repeat(n + 1) { out += packed[i++] } } else if (n != -128) { val v = packed[i++]; repeat(1 - n) { out += v } }
		}
		assertEquals(data.toList(), out)
	}
}
