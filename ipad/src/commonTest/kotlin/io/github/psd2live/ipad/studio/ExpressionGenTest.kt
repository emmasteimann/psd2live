package io.github.psd2live.ipad.studio

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpressionGenTest {
	private val w = 120; private val h = 120
	private fun png(f: (Int, Int) -> Int) = pngDataUrl(encodePng(w, h, IntArray(w * h) { f(it % w, it / w) }))
	private val SKIN = 0xFFF0D0C0.toInt(); private val WHITE = 0xFFFAFAFA.toInt(); private val LIP = 0xFFC04050.toInt(); private val RED = 0xFFFF0000.toInt()
	private fun inEyeL(x: Int, y: Int) = x in 30..44 && y in 40..48
	private fun inEyeR(x: Int, y: Int) = x in 75..89 && y in 40..48
	private fun inMouth(x: Int, y: Int) = x in 55..64 && y in 80..82

	/** A face with two eye whites and a mouth; the eyes named the slot way ("Left Sclera") and the Auto-split way ("Eyewhite R"). */
	private fun project(): StudioProject {
		val face = png { x, y -> if (x in 15..105 && y in 15..105) SKIN else 0 }
		val l = png { x, y -> if (inEyeL(x, y)) WHITE else 0 }
		val r = png { x, y -> if (inEyeR(x, y)) WHITE else 0 }
		val m = png { x, y -> if (inMouth(x, y)) LIP else 0 }
		fun part(id: String, name: String, art: String) = """{"id":"$id","name":"$name","group":"Face","visible":true,"mask":null,"repairs":[],"cutout":"$art","extractionMask":"$art"}"""
		return StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"T","source":"$face","width":$w,"height":$h,"parts":[
			${part("face", "Face", face)},${part("el", "Left Sclera", l)},${part("er", "Eyewhite R", r)},${part("mouth", "Mouth", m)}],"history":[]}""")
	}

	/** The stand-in AI paints red wherever it may edit (the mask's transparent pixels) and returns the rest as sent. */
	private suspend fun <T> withStandIn(block: suspend () -> T): T {
		val old = StudioAI.transport
		StudioAI.transport = { image, mask, _, _ ->
			val img = decodeImage(image)!!; val mk = decodeImage(mask!!)!!
			Pixels(img.width, img.height, IntArray(img.argb.size) { i -> if ((mk.argb[i] ushr 24) < 128) RED else img.argb[i] })
		}
		try { return block() } finally { StudioAI.transport = old }
	}

	@Test fun findsEyesAndMouthByNameAndRig() {
		val f = StudioExpressionGen.findFace(project())
		assertEquals(listOf("el"), f.left.map { it.id }); assertEquals(listOf("er"), f.right.map { it.id }); assertEquals("mouth", f.mouth?.id)
	}

	@Test fun blinkMakesOneHiddenPartPerEyeThatTheRigCallsEyeClose() = runBlocking {
		val p = project()
		val r = withStandIn { StudioExpressionGen.blink(p) }
		assertEquals(listOf("eye-close-l", "eye-close-r"), r.items.map { it.role })
		val left = StudioImages.layer(r.items[0].art, w, h)
		assertEquals(RED, left.at(37, 44)) // the edit, over the left eye
		assertEquals(0, StudioImages.layer(r.items[0].art, w, h).full()[44 * w + 82]) // nothing from the other eye
		val applied = StudioExpressionGen.apply(p, r.items)
		val ids = applied.parts.map { it.json.str("expressionRole") ?: it.id }
		assertEquals(listOf("face", "el", "eye-close-l", "er", "eye-close-r", "mouth"), ids) // each right above its eye
		val closeL = applied.parts[2]
		assertTrue(!closeL.visible)
		assertEquals("eye close-l", StudioRig.rigName(applied, closeL))
		// Generating again replaces, never duplicates.
		assertEquals(6, StudioExpressionGen.apply(applied, r.items).parts.size)
	}

	@Test fun mouthOpenBecomesTheRigMouthAndTheDrawnOneItsClosedState() = runBlocking {
		val p = project()
		val r = withStandIn { StudioExpressionGen.mouth(p, StudioExpressionGen.mouths.first { it.id == "open" }) }
		val it = r.items.single()
		assertEquals("ParamMouthOpenY", it.paramId)
		assertEquals(RED, StudioImages.layer(it.art, w, h).at(60, 81))
		assertEquals(0, StudioImages.layer(it.art, w, h).full()[44 * w + 37]) // the eyes untouched
		val applied = StudioExpressionGen.apply(p, r.items)
		assertEquals("mouth", StudioRig.rigName(applied, applied.parts.last()))
		assertEquals("mouth close", StudioRig.rigName(applied, applied.part("mouth")!!))
		// The preview shows the new mouth in place of the drawn one.
		assertEquals(RED, StudioExpressionGen.preview(p, r.items)[81 * w + 60])
	}
}
