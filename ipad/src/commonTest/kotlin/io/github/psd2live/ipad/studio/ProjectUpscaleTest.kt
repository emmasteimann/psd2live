package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectUpscaleTest {
	private val w = 40; private val h = 30
	private fun png(f: (Int, Int) -> Int) = pngDataUrl(encodePng(w, h, IntArray(w * h) { f(it % w, it / w) }))
	private val TEAL = 0xFF208080.toInt(); private val GOLD = 0xFFE0B020.toInt()

	private fun project(): StudioProject {
		val art = png { x, y -> if (x in 10..29 && y in 5..24) TEAL else 0 }
		val mask = png { x, y -> if (x in 12..20 && y in 8..16) MASK_ARGB else 0 }
		val patch = png { x, y -> if (x in 15..18 && y in 10..12) GOLD else 0 }
		val small = pngDataUrl(encodePng(4, 4, IntArray(16) { TEAL })) // not full-canvas: left alone
		return StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"Quill","source":"$art","width":$w,"height":$h,
			"armJoints":{"left":{"elbow":[10,20]}},"parts":[{"id":"robe","name":"Robe","group":"Clothing","visible":true,"mask":"$mask","cutout":"$art","extractionMask":"$art",
			"repairs":[{"id":"r1","patch":"$patch","mask":"$mask"}],"thumb":"$small"}],"history":[{"at":"x","event":"made"}]}""")
	}

	@Test fun everyFullCanvasImageIsDoubledAndPositionsScale() {
		val old = StudioProjectUpscale.art
		var artCalls = 0
		// Stand-in model: nearest-neighbour 2×.
		StudioProjectUpscale.art = { a, aw, ah, s -> artCalls++; val k = s.toInt(); IntArray(aw * k * ah * k) { i -> a[(i / (aw * k)) / k * aw + (i % (aw * k)) / k] } }
		try {
			val p = project()
			val r = StudioProjectUpscale.upscaleProject(p)
			val q = r.project
			assertEquals(80, q.width); assertEquals(60, q.height)
			assertNotEquals(p.id, q.id); assertEquals("Quill (2×)", q.name)
			assertEquals(1, r.skipped)
			assertEquals(2, artCalls) // the art (source, cutout, extractionMask are one image) and the patch; masks don't go through the model
			val robe = q.part("robe")!!
			assertEquals(TEAL, StudioImages.layer(robe.cutout, 80, 60).at(40, 30))
			assertEquals(GOLD, StudioImages.layer(robe.repairs[0].str("patch"), 80, 60).at(33, 22))
			val m = decodeImage(dataUrlBytes(robe.mask!!))!!; assertEquals(80, m.width)
			assertTrue((m.argb[24 * 80 + 32] ushr 24) > 200) // the mask resampled into place
			assertEquals(4, decodeImage(dataUrlBytes(robe.json.str("thumb")!!))!!.width) // small image untouched
			assertEquals(listOf(20, 40), ((q.json["armJoints"] as JsonObject)["left"] as JsonObject)["elbow"].let { (it as JsonArray).map { v -> v.jsonPrimitive.content.toInt() } })
			assertEquals(2, (q.json["history"] as JsonArray).size)
		} finally { StudioProjectUpscale.art = old }
	}

	@Test fun cleanUpKeepsTheOldArtForStartingOver() {
		val old = StudioProjectUpscale.art
		StudioProjectUpscale.art = { a, _, _, _ -> IntArray(a.size) { i -> if ((a[i] ushr 24) > 0) GOLD else 0 } }
		try {
			val p = project()
			val q = StudioProjectUpscale.cleanUpParts(p)
			val robe = q.part("robe")!!
			assertEquals(GOLD, StudioImages.layer(robe.cutout, w, h).at(20, 20))
			assertEquals(p.part("robe")!!.cutout, robe.json.str("fitOriginal"))
			assertEquals(p.part("robe")!!.mask, robe.mask) // masks left alone
		} finally { StudioProjectUpscale.art = old }
	}

	/** The real Core ML model, on a small project (skipped when the simulator app hasn't been built). */
	@Test fun theRealModelDoublesAPart() {
		val path = "/Users/goosible/Downloads/neuro-goose/psd2live-main/ipad/iosApp/build/dd/Build/Products/Debug-iphonesimulator/Layerloom.app/AnimeUpscaler.mlmodelc"
		if (!okio.FileSystem.SYSTEM.exists(okio.Path.Companion.run { path.toPath() })) return
		AnimeUpscaler.useModelAt(path)
		val q = StudioProjectUpscale.upscaleProject(project()).project
		val c = StudioImages.layer(q.part("robe")!!.cutout, 80, 60)
		val px = c.at(40, 30)
		assertTrue((px ushr 24) > 200, "opaque inside, got ${px.toUInt().toString(16)}")
		assertTrue(((px shr 8) and 0xFF) in 100..160 && (px and 0xFF) in 100..160, "still teal, got ${px.toUInt().toString(16)}")
		assertEquals(0, c.full()[2 * 80 + 2] ushr 24) // empty stays empty
	}
}
