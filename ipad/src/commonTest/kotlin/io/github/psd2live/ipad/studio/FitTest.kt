package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitTest {
	private fun near(a: Double, b: Double, eps: Double = 1e-6) = assertTrue(abs(a - b) < eps, "$a vs $b")

	@Test fun homographyMapsTheCorners() {
		val a = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))
		val b = listOf(Pt(1.0, 2.0), Pt(12.0, 0.0), Pt(9.0, 14.0), Pt(-1.0, 9.0))
		val m = FitGeometry.homography(a, b)!!
		for (i in 0 until 4) { val p = FitGeometry.applyHomography(m, a[i]); near(p.x, b[i].x, 1e-6); near(p.y, b[i].y, 1e-6) }
		assertTrue(!FitGeometry.convexQuad(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(0.0, 10.0), Pt(10.0, 10.0))))
	}

	@Test fun resamplingTheCageKeepsTheWarp() {
		val box = Box(0.0, 0.0, 30.0, 30.0)
		val warped = FitGeometry.transformPoints(FitGeometry.gridPoints(box, 3, 3), dx = 5.0, scale = 1.5, centre = Pt(15.0, 15.0))
		val six = FitGeometry.resampleCage(box, 3, warped, 6)
		val expect = FitGeometry.transformPoints(FitGeometry.gridPoints(box, 6, 6), dx = 5.0, scale = 1.5, centre = Pt(15.0, 15.0))
		for (i in six.indices) { near(six[i].x, expect[i].x, 1e-9); near(six[i].y, expect[i].y, 1e-9) }
	}

	@Test fun warpMovesThePixels() {
		val w = 20; val h = 20
		val img = IntArray(w * h) { i -> if (i % w in 2..5 && i / w in 2..5) 0xFFFF0000.toInt() else 0 }
		val box = FitGeometry.alphaBox(img, w, h)!!
		assertEquals(Box(2.0, 2.0, 6.0, 6.0), box)
		val src = FitGeometry.gridPoints(box, 2, 2)
		val dst = src.map { Pt(it.x + 10, it.y + 7) }
		val out = FitRaster.warp(img, w, h, src, dst, 2, 0.0, 0.0, w.toDouble(), h.toDouble(), w, h)
		val moved = FitGeometry.alphaBox(out, w, h, 128)!!
		assertEquals(Box(12.0, 9.0, 16.0, 13.0), moved)
		assertEquals(0xFFFF0000.toInt(), out[10 * w + 13])
	}

	@Test fun snapFindsTheLine() {
		// A drawn square outline; the piece is the same square, 6 px off. Snap puts it back.
		val w = 80; val h = 80
		val drawing = IntArray(w * h) { -1 }
		for (i in 20..59) { drawing[20 * w + i] = 0xFF000000.toInt(); drawing[59 * w + i] = 0xFF000000.toInt(); drawing[i * w + 20] = 0xFF000000.toInt(); drawing[i * w + 59] = 0xFF000000.toInt() }
		val dist = FitGeometry.distanceMap(FitGeometry.edgeMask(drawing, w, h), w, h)
		val piece = IntArray(w * h) { i -> if (i % w in 26..65 && i / w in 20..59) -1 else 0 }
		val pts = FitGeometry.outlinePoints(piece, w, h)
		val r = FitGeometry.snapToLines(pts, dist, w, h, Pt(45.0, 40.0))
		assertTrue(r.after < r.before / 3, "${r.before} -> ${r.after}")
		assertTrue(abs(r.change.dx + 6) < 2, "dx ${r.change.dx}")
	}

	@Test fun brushAndWandAndSpecks() {
		val a = ByteArray(100)
		FitRaster.stroke(a, 10, 10, Pt(5.0, 5.0), Pt(5.0, 5.0), 4.0, 0.0, false)
		assertEquals(255, a[5 * 10 + 5].toInt() and 0xFF); assertEquals(0, a[0].toInt())
		FitRaster.stroke(a, 10, 10, Pt(5.0, 5.0), Pt(5.0, 5.0), 4.0, 0.0, true)
		assertEquals(0, a[5 * 10 + 5].toInt() and 0xFF)

		val img = IntArray(100) { i -> if (i % 10 < 5) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
		val sel = FitGeometry.wandSelect(img, 10, 10, 1, 1, 28)
		assertEquals(50, sel.count { it.toInt() != 0 })

		// One big blob and a 2-pixel speck far away.
		val p = IntArray(40 * 40)
		for (y in 5..25) for (x in 5..25) p[y * 40 + x] = -1
		p[35 * 40 + 35] = -1; p[35 * 40 + 36] = -1
		val s = FitGeometry.findSpecks(p, 40, 40, 40, true)
		assertEquals(1, s.count); assertEquals(2, s.px)
	}

	@Test fun bakingSavesTheFitAndKeepsTheTrimmedArt() {
		val w = 24; val h = 24
		val art = pngDataUrl(encodePng(w, h, IntArray(w * h) { i -> if (i % w in 4..11 && i / w in 4..11) 0xFF20A040.toInt() else 0 }))
		val source = pngDataUrl(encodePng(w, h, IntArray(w * h) { -1 }))
		val project = StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"T","source":"$source","width":$w,"height":$h,
			"parts":[{"id":"a","name":"Hair front","group":"Hair","visible":true,"mask":null,"repairs":[],"cutout":"$art","extractionMask":"$art"}],"history":[]}""")
		val f = FitSession.load(project, "a")
		f.dst = f.dst.map { Pt(it.x + 6, it.y) }
		f.trimStroke(Pt(5.0, 5.0), Pt(5.0, 5.0), 3.0, 0.0, false)
		val (changes, event) = f.bake()
		assertTrue(event.startsWith("Fitted Hair front by hand"))
		val cut = StudioImages.layer(changes["cutout"]!!.jsonPrimitive.content, w, h)
		assertTrue(cut.x >= 9, "moved right: ${cut.x}")
		assertNotNull(changes["trimBase"]); assertTrue(changes["trimBase"] !is JsonNull)
		assertEquals(art, changes["fitOriginal"]!!.jsonPrimitive.content)
		assertTrue(changes["hairAnchor"] is JsonNull)
		// Glue round-trips the web's fields.
		val g = Glue(0.2, 0.4, true, true, "x1").json()
		assertEquals(Glue(0.2, 0.4, true, true, "x1"), Glue.of(g))
		assertNull(Glue.of(JsonObject(emptyMap()))?.target)
	}

	@Test fun rigNamesFollowTheWeb() {
		assertEquals("handwear-l", StudioRig.fromName("Sleeve L"))
		assertEquals("front hair", StudioRig.fromName("front hair"))
		assertEquals("eyewhite-r 2", StudioRig.fromName("eyewhite r 2"))
		assertEquals("topwear", StudioRig.fromName("Dress"))
	}
}
