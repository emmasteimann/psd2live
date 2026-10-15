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

	@Test fun reMeshingALayerKeepsItsTweaks() {
		val psd = System.getenv("LAYERLOOM_PSD") ?: return
		val source = PsdReader.read(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(psd)))
		val pipeline = PSD2LivePipeline()
		val model = pipeline.buildPreview(source, PipelineConfig(exportCmo3 = false))
		val pose = mapOf("ParamAngleX" to 30f)
		val puppet = model.rig.puppet
		val x = puppet.canvasWidth / 2f; val y = puppet.canvasHeight * 0.3f
		val meshes = PoseTweaks.meshesUnder(puppet, pose, x, y, 40f)
		val session = PoseTweaks.begin(puppet, pose, meshes, x, y, 40f)
		for (i in 1..10) PoseTweaks.step(session, x + i * 4f, y)
		val tweaked = PoseTweaks.rebuilt(pipeline, model, requireNotNull(PoseTweaks.journaled(model, session)))
		// The front-most tweaked mesh's layer, re-meshed finer.
		val layer = requireNotNull(tweaked.rig.layerIdByDrawableId[meshes.first()])
		fun finer(config: PipelineConfig) = config.copy(meshOverrides = config.meshOverrides + (layer to MeshSettings(interiorDensity = 15f, maxEdgeDistance = 3f)))
		val remeshed = pipeline.rebuildPreview(tweaked, finer(tweaked.config))
		val plain = pipeline.rebuildPreview(model, finer(model.config))
		val vertices = { m: RigPreviewModel -> m.rig.puppet.drawables.single { it.id.raw == meshes.first() }.mesh!!.vertexCount }
		assertTrue(vertices(remeshed) > vertices(tweaked), "the layer has more vertices")
		assertTrue(diff(frame(plain, pose), frame(remeshed, pose)) > 0, "the tweak is still there at its pose")
		assertEquals(0, diff(frame(plain, emptyMap()), frame(remeshed, emptyMap())), "and still not at rest")
		assertEquals(0, diff(frame(remeshed, pose), frame(pipeline.buildPreview(source, remeshed.config), pose)), "a full re-rig keeps it")
	}

	@Test fun aWarpPushMovesTheWholeRegionAtThatPose() {
		val psd = System.getenv("LAYERLOOM_PSD") ?: return
		val source = PsdReader.read(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(psd)))
		val pipeline = PSD2LivePipeline()
		val model = pipeline.buildPreview(source, PipelineConfig(exportCmo3 = false))
		val pose = mapOf("ParamAngleX" to 30f)
		val restBefore = frame(model, emptyMap()); val poseBefore = frame(model, pose)
		val puppet = model.rig.puppet
		val x = puppet.canvasWidth / 2f; val y = puppet.canvasHeight * 0.22f
		val warp = requireNotNull(PoseTweaks.warpUnder(puppet, pose, x, y)) { "a warp holds the head" }
		val session = PoseTweaks.beginWarp(puppet, pose, warp.id, x, y, 120f)
		for (i in 1..10) PoseTweaks.step(session, x + i * 5f, y)
		val config = requireNotNull(PoseTweaks.journaled(model, session))
		val tweaked = PoseTweaks.rebuilt(pipeline, model, config)
		assertTrue(diff(poseBefore, frame(tweaked, pose)) > 0, "the pose moved (${warp.name})")
		assertEquals(0, diff(restBefore, frame(tweaked, emptyMap())), "rest is untouched")
		assertEquals(0, diff(frame(tweaked, pose), frame(pipeline.buildPreview(source, config), pose)), "a full re-rig keeps it")
	}

	@Test fun selectedPointsMoveExactlyWithThePencilAndNothingElseMoves() {
		val psd = System.getenv("LAYERLOOM_PSD") ?: return
		val source = PsdReader.read(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(psd)))
		val pipeline = PSD2LivePipeline()
		val model = pipeline.buildPreview(source, PipelineConfig(exportCmo3 = false))
		val pose = mapOf("ParamAngleX" to 30f)
		val before = PoseTweaks.vertices(model.rig.puppet, pose).toMap()
		val (mesh, points) = before.entries.first { it.value.size >= 40 }
		val chosen = setOf(0, 3, 7, 11)
		val x = points[0]; val y = points[1]
		val session = PoseTweaks.beginPoints(model.rig.puppet, pose, mapOf(mesh to chosen), x, y)
		PoseTweaks.step(session, x + 6f, y - 4f)
		PoseTweaks.step(session, x + 12f, y - 8f)
		val config = requireNotNull(PoseTweaks.journaled(model, session))
		val after = PoseTweaks.vertices(PoseTweaks.rebuilt(pipeline, model, config).rig.puppet, pose).toMap()
		val moved = after.getValue(mesh)
		for (i in 0 until points.size / 2) {
			val dx = moved[i * 2] - points[i * 2]; val dy = moved[i * 2 + 1] - points[i * 2 + 1]
			if (i in chosen) {
				assertTrue(kotlin.math.abs(dx - 12f) < 0.05f && kotlin.math.abs(dy + 8f) < 0.05f, "vertex $i moved by the drag ($dx, $dy)")
			} else assertTrue(kotlin.math.abs(dx) < 1e-3f && kotlin.math.abs(dy) < 1e-3f, "unselected vertex $i stayed ($dx, $dy)")
		}
		for ((other, p) in before) if (other != mesh) assertTrue(p.contentEquals(after.getValue(other)) || (p.indices.all { kotlin.math.abs(p[it] - after.getValue(other)[it]) < 1e-3f }), "$other stayed")
		val rest = PoseTweaks.vertices(model.rig.puppet, emptyMap()).toMap().getValue(mesh)
		val restAfter = PoseTweaks.vertices(PoseTweaks.rebuilt(pipeline, model, config).rig.puppet, emptyMap()).toMap().getValue(mesh)
		assertTrue(rest.indices.all { kotlin.math.abs(rest[it] - restAfter[it]) < 1e-3f }, "rest is untouched")
	}
}
