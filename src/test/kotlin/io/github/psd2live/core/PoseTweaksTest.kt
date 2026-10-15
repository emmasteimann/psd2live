package io.github.psd2live.core

import io.github.psd2live.format.compile.FrameSpec
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iPad's hand tweaks on real art (LAYERLOOM_PSD=<a character psd>; skipped without it): a stroke at a pose moves
 * the rig at that pose only, leaves the model it started from untouched, and is kept by a full re-rig.
 */
class PoseTweaksTest {
	private fun frame(m: RigPreviewModel, pose: Map<String, Float>): IntArray {
		val ir = RigIrCompiler.compile(m)
		return IrFrameRenderer.open(ir, false).render(pose, 0f, FrameSpec.canvas(ir, 800), null).argb
	}
	private fun diff(a: IntArray, b: IntArray) = a.indices.count { a[it] != b[it] }

	@Test fun aStrokeAtAPoseStaysAtThatPose() {
		val psd = System.getenv("LAYERLOOM_PSD") ?: return
		val source = PsdReader.read(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(psd)))
		val pipeline = PSD2LivePipeline()
		val model = pipeline.buildPreview(source, PipelineConfig(exportCmo3 = false))
		val pose = mapOf("ParamAngleX" to 30f)
		val restBefore = frame(model, emptyMap()); val poseBefore = frame(model, pose)
		val puppet = model.rig.puppet
		// The middle of the art, where the head and body overlap most.
		val x = puppet.canvasWidth / 2f; val y = puppet.canvasHeight * 0.3f
		val meshes = PoseTweaks.meshesUnder(puppet, pose, x, y, 60f)
		assertTrue(meshes.isNotEmpty(), "something is under the brush")
		val session = PoseTweaks.begin(puppet, pose, meshes, x, y, 60f)
		for (i in 1..12) PoseTweaks.step(session, x + i * 4f, y)
		val config = requireNotNull(PoseTweaks.journaled(model, session))
		val tweaked = PoseTweaks.rebuilt(pipeline, model, config)
		assertEquals(0, diff(poseBefore, frame(model, pose)), "the model the stroke started from is unchanged")
		assertTrue(diff(poseBefore, frame(tweaked, pose)) > 0, "the pose moved")
		assertEquals(0, diff(restBefore, frame(tweaked, emptyMap())), "rest is untouched")
		val rerig = pipeline.buildPreview(source, config)
		assertEquals(0, diff(frame(tweaked, pose), frame(rerig, pose)), "a full re-rig keeps the tweak")
	}

	@Test fun aMirroredStrokeLandsOnTheOtherSideAtTheMirroredPose() {
		val psd = System.getenv("LAYERLOOM_PSD") ?: return
		val source = PsdReader.read(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(psd)))
		val pipeline = PSD2LivePipeline()
		val model = pipeline.buildPreview(source, PipelineConfig(exportCmo3 = false))
		val right = mapOf("ParamAngleX" to 30f); val left = mapOf("ParamAngleX" to -30f)
		val restBefore = frame(model, emptyMap()); val leftBefore = frame(model, left)
		val puppet = model.rig.puppet
		val x = puppet.canvasWidth * 0.4f; val y = puppet.canvasHeight * 0.25f
		val session = PoseTweaks.begin(puppet, right, PoseTweaks.meshesUnder(puppet, right, x, y, 50f), x, y, 50f)
		for (i in 1..10) PoseTweaks.step(session, x - i * 4f, y)
		val first = PoseTweaks.rebuilt(pipeline, model, requireNotNull(PoseTweaks.journaled(model, session)))
		assertEquals(0, diff(leftBefore, frame(first, left)), "the stroke alone leaves looking left as it was")
		val mirror = requireNotNull(PoseTweaks.mirrored(first.rig.puppet, session)) { "something is under the mirrored press" }
		assertEquals(-30f, mirror.request.pose["ParamAngleX"], "the mirror works at the mirrored pose")
		val config = requireNotNull(PoseTweaks.journaled(first, mirror))
		val both = PoseTweaks.rebuilt(pipeline, first, config)
		assertTrue(diff(leftBefore, frame(both, left)) > 0, "looking left moved too")
		assertEquals(0, diff(restBefore, frame(both, emptyMap())), "rest is untouched")
		assertEquals(0, diff(frame(both, left), frame(pipeline.buildPreview(source, config), left)), "a full re-rig keeps both")
	}
}
