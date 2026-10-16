package io.github.psd2live.ipad.studio

import io.github.psd2live.core.LayerloomJob
import kotlinx.serialization.json.JsonObject
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RigHandoffTest {
	private val w = 60; private val h = 60
	private fun png(c: Int, x0: Int, y0: Int, x1: Int, y1: Int) = pngDataUrl(encodePng(w, h, IntArray(w * h) { if (it % w in x0..x1 && it / w in y0..y1) c else 0 }))
	private fun part(id: String, name: String, art: String, extra: String = "") =
		"""{"id":"$id","name":"$name","group":"Face","visible":true,"mask":null,"repairs":[],"cutout":"$art","extractionMask":"$art"$extra}"""

	private fun project() = StudioProject.parse("""{"format":"layerloom-project","version":1,"id":"p","name":"Boo","source":"${png(-1, 0, 0, 59, 59)}","width":$w,"height":$h,
		"showRemaining":false,"hairTurnDepth":0.6,"parts":[
		${part("11111111-1111-1111-1111-111111111111", "Back Hair", png(0xFF8080FF.toInt(), 5, 5, 55, 40), ""","swing":{"preset":"hair","side":0.5,"edge":"top","pivot":0.2}""")},
		${part("22222222-2222-2222-2222-222222222222", "Face", png(0xFFF0D0C0.toInt(), 15, 10, 45, 40))},
		${part("33333333-3333-3333-3333-333333333333", "Mouth", png(0xFFC04050.toInt(), 27, 30, 33, 32))},
		${part("44444444-4444-4444-4444-444444444444", "Mouth Open", png(0xFF600010.toInt(), 26, 29, 34, 34), ""","visible":false,"expressionRole":"mouth-open","expressionOf":["33333333-3333-3333-3333-333333333333"]""")},
		${part("55555555-5555-5555-5555-555555555555", "Mouth Smile", png(0xFF600010.toInt(), 26, 29, 34, 34), ""","visible":false,"expressionRole":"mouth-smile","expressionOf":["33333333-3333-3333-3333-333333333333"]""")},
		${part("66666666-6666-6666-6666-666666666666", "Ribbon", png(0xFFE03030.toInt(), 20, 2, 30, 8), ""","hairAnchor":{"target":"11111111-1111-1111-1111-111111111111","from":0.1,"to":0.4}""")}
		],"history":[]}""")

	@Test fun thePsdHasRigNamesStableIdsAndTheMouthsInOrder() {
		val hand = StudioRigHandoff.build(project())
		val psd = PsdReader.read(hand.psd)
		val names = psd.layers.map { it.name }
		assertEquals(listOf("back hair", "face", "mouth", "mouth close", "neckwear"), names, "got $names") // the rigger reads a ribbon as neckwear
		assertTrue(psd.layers.all { it.id.raw.startsWith("lyid:") }, "every layer keeps its part's id: ${psd.layers.map { it.id.raw }}")
		assertEquals(listOf("Mouth Smile"), hand.skipped) // PSD2Live has no smile slot yet
	}

	@Test fun theJobCarriesSwingGlueAndHairDepthForPsd2LivesOwnReader() {
		val hand = StudioRigHandoff.build(project())
		val job = LayerloomJob.parse(hand.job)
		val swing = job.swings["back hair"] as JsonObject
		assertEquals("top", (swing["fulcrum"] as kotlinx.serialization.json.JsonPrimitive).content)
		assertTrue(hand.job.toString().contains("\"glues\""), "the ribbon is glued to the back hair: ${hand.job}")
		assertEquals("back hair", ((hand.job["glues"] as JsonObject).values.first() as JsonObject)["target"].toString().trim('"'))
		assertTrue(!job.isEmpty)
		assertEquals(0.6, (hand.job["hairTurnDepth"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), 1e-9)
	}

	@Test fun layerIdsMatchTheMacsRigPy() {
		// rig.py: int(uuid hex, 16) % 2147483646 + 1
		val used = mutableSetOf<Int>()
		val id = StudioRigHandoff.layerId("00000000-0000-0000-0000-000000000005", used)
		assertEquals(6, id)
		assertEquals(7, StudioRigHandoff.layerId("00000000-0000-0000-0000-000000000005", used)) // a clash moves on
	}
}

/** Boo's real PSD through Studio → Send to Rig → rigger, against rigging the PSD directly (skipped without the file). */
class RigHandoffRealArtTest {
	@Test fun booRigsTheSameThroughTheStudio() {
		val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"
		if (!okio.FileSystem.SYSTEM.exists(okio.Path.Companion.run { path.toPath() })) return
		val bytes = okio.FileSystem.SYSTEM.read(okio.Path.Companion.run { path.toPath() }) { readByteArray() }
		val pipeline = io.github.psd2live.core.PSD2LivePipeline()
		val base = io.github.psd2live.core.PipelineConfig(exportCmo3 = false)
		val direct = pipeline.buildPreview(PsdReader.read(bytes), base)
		val hand = StudioRigHandoff.build(StudioPsd.import(bytes, "boo_v3.psd"))
		val source = PsdReader.read(hand.psd)
		val job = LayerloomJob.parse(hand.job)
		val viaStudio = pipeline.buildPreview(source, job.configure(base))
		val a = direct.rig.puppet; val b = viaStudio.rig.puppet
		println("HANDOFF direct: ${a.drawables.size} drawables, ${a.parameters.size} params; via Studio: ${b.drawables.size} drawables, ${b.parameters.size} params; skipped ${hand.skipped}")
		assertEquals(a.drawables.size, b.drawables.size, "the same pieces")
		assertEquals(a.parameters.map { it.id }.toSet(), b.parameters.map { it.id }.toSet(), "the same parameters (blink, mouth, look...)")
	}
}
