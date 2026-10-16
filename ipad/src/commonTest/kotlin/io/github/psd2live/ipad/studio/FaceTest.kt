package io.github.psd2live.ipad.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceTest {
	private val w = 60; private val h = 60
	private fun png(f: (Int, Int) -> Int) = pngDataUrl(encodePng(w, h, IntArray(w * h) { f(it % w, it / w) }))
	private val SKIN = 0xFFF6D7C3.toInt()

	/** A round face (centre 30,30, radius 20) as a part named "Face", and an empty "Mouth". */
	private fun project(): StudioProject {
		val face = png { x, y -> if ((x - 30) * (x - 30) + (y - 30) * (y - 30) <= 400) SKIN else 0 }
		val src = png { _, _ -> -1 }
		return StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"T","source":"$src","width":$w,"height":$h,"parts":[
			{"id":"f","name":"Face","group":"Face","visible":true,"mask":null,"repairs":[],"cutout":"$face","extractionMask":"$face"},
			{"id":"m","name":"Mouth","group":"Face","visible":true,"mask":null,"repairs":[]}],"history":[]}""")
	}

	@Test fun overlaysStayOnTheFaceAndSitAboveIt() {
		val p = project()
		val blush = StudioFace.makeBlush(p, 0xFFFF6F8E.toInt(), 0.6, 1.0, lines = true)
		assertTrue(blush.any { (it ushr 24) != 0 })
		for (i in blush.indices) if ((blush[i] ushr 24) != 0) { val x = i % w; val y = i / w; assertTrue((x - 30) * (x - 30) + (y - 30) * (y - 30) <= 400, "blush off the face at $x,$y") }
		val shadow = StudioFace.makeShadow(p, 0xFF463C82.toInt(), 0.85, 0.0, lines = true)
		assertTrue((shadow[12 * w + 30] ushr 24) > 100) // strong at the top of the face
		assertEquals(0, shadow[49 * w + 30] ushr 24) // gone by the chin
		val p2 = StudioFace.placeOverlay(p, StudioFace.blush, blush, emptyMap(), null, emptyList())
		val p3 = StudioFace.placeOverlay(p2, StudioFace.shadow, shadow, emptyMap(), "multiply", StudioFace.gloomParams(true, 0.6, true, false))
		assertEquals(listOf("Face", "Mouth", "Blush", "Face shadow"), p3.parts.map { it.name }) // above the topmost face piece (the mouth)
		// No extraction mask: an overlay mustn't punch a hole in the drawing.
		assertNull(p3.part(StudioFace.overlayPart(p3, StudioFace.shadow)!!.id)!!.extractionMask)
		// Remaking replaces rather than adding a second one.
		assertEquals(4, StudioFace.placeOverlay(p3, StudioFace.blush, blush, emptyMap(), null, emptyList()).parts.size)
		assertFailsWith<IllegalStateException> { StudioFace.makeBlush(StudioEdits.delete(p, "f"), 0, 0.5, 1.0, false) }
	}

	@Test fun eyePlanAssignsTheSlots() {
		val p = StudioFace.planEyeSlots(project())
		val left = StudioFace.eyeRolesOf(p, "left")!!
		assertEquals(7, left.size)
		assertEquals("Left Sclera", p.part(left.first { it.key == "sclera" }.partId)!!.name)
		val p2 = StudioFace.setEyeRole(p, "left", "iris", "m")
		assertEquals("m", StudioFace.eyeRolesOf(p2, "left")!!.first { it.key == "iris" }.partId)
		val p3 = StudioFace.setEyeRole(p2, "left", "pupil", null, required = true)
		assertTrue(StudioFace.eyeRolesOf(p3, "left")!!.first { it.key == "pupil" }.required)
	}

	@Test fun variantsAndKeyformPlans() {
		val p = project()
		val good = encodePng(w, h, IntArray(w * h) { if (it % w in 20..40 && it / w in 40..45) 0xFFAA3344.toInt() else 0 })
		val p2 = StudioFace.importVariant(p, "m", good, "Mouth open")
		val v = StudioFace.variants(p2.part("m")!!).single()
		assertEquals("Mouth open", v.name)
		assertFailsWith<IllegalStateException> { StudioFace.importVariant(p, "m", encodePng(10, 10, IntArray(100) { -1 }), "x") }
		assertFailsWith<IllegalStateException> { StudioFace.importVariant(p, "m", encodePng(w, h, IntArray(w * h) { -1 }), "x") }
		val p3 = StudioFace.setKeyformPlan(p2, "m", "ParamMouthOpenY", 0.0, mapOf(v.id to 1.0))
		assertEquals(emptyList(), StudioChecks.keyformIssues(p3.part("m")!!))
		val p4 = StudioFace.deleteVariant(p3, "m", v.id)
		assertTrue(StudioFace.variants(p4.part("m")!!).isEmpty())
		assertEquals(emptyMap(), StudioFace.keyformPlan(p4.part("m")!!)!!.third)
	}

	@Test fun underlyingReferenceAlignsAndCopiesUnderThePart() {
		val p0 = project()
		// A 30x30 picture, solid green: imported, it's fitted to the canvas (scale 2).
		val p1 = StudioFace.importReference(p0, encodePng(30, 30, IntArray(900) { 0xFF22AA44.toInt() }))
		val ref = StudioFace.reference(p1)!!
		assertEquals(2.0, ref.alignment!!.scale)
		val al = StudioFace.aligned(p1, ref, ref.alignment!!)
		assertEquals(0xFF22AA44.toInt(), al[30 * w + 30])
		val masked = StudioEdits.setMask(p1, "f", png { x, _ -> if (x < 10) MASK_ARGB else 0 })
		val (p2, id) = StudioFace.copyUnder(masked, "f", "Under face", ref, ref.alignment!!)
		assertEquals(listOf("Under face", "Face", "Mouth"), p2.parts.map { it.name })
		val layer = StudioImages.layer(p2.part(id)!!.cutout, w, h)
		assertEquals(0, layer.x); assertEquals(10, layer.width)
		assertNotNull(p2.part(id)!!.json["underlyingFrom"])
	}
}
