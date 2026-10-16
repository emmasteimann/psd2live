package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deform paths on Boo's real rig (skipped without the file): a path along the tail bends it at a pose, not at rest. */
class RigPathsTest {
	@Test fun aPathBendsThePartAtThePoseOnly() {
		val p = okio.Path.Companion.run { "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd".toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val model = PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false))
		val outcome = RigOutcome("boo.psd", 0, 0, 0, emptyList(), "", emptyList(), 0.0, RigPreview(model), source, model.config, model)
		val t = RigTweaking(outcome)
		val e = RigEditing(model, source)
		val tail = RigParts.list(e).first { it.name.startsWith("tail") }
		val b = RigParts.bounds(e, tail)!!
		val mesh = tail.meshes.first()
		val cx = (b[0] + b[2]) / 2
		val id = t.addPath(mesh, listOf(cx to b[1] + (b[3] - b[1]) * 0.2f, cx to (b[1] + b[3]) / 2, cx to b[1] + (b[3] - b[1]) * 0.8f))
		val pose = mapOf("ParamBodyAngleX" to 10f)
		val shown = t.paths(pose).first { it.id == id }
		assertEquals(6, shown.points.size)
		val restBefore = t.frame(emptyMap(), 0f, 0f, t.canvasWidth, t.canvasHeight, 300, 400).argb.copyOf()
		val poseBefore = t.frame(pose, 0f, 0f, t.canvasWidth, t.canvasHeight, 300, 400).argb.copyOf()
		t.movePathPoint(id, 1, shown.points[2] + 40f, shown.points[3], pose)
		val after = t.paths(pose).first { it.id == id }
		assertTrue(after.points[2] > shown.points[2] + 20f, "the middle point moved right at the pose: ${shown.points.toList()} -> ${after.points.toList()}")
		val restAfter = t.frame(emptyMap(), 0f, 0f, t.canvasWidth, t.canvasHeight, 300, 400).argb
		val poseAfter = t.frame(pose, 0f, 0f, t.canvasWidth, t.canvasHeight, 300, 400).argb
		assertTrue(restBefore.indices.count { restBefore[it] != restAfter[it] } == 0, "rest is untouched")
		assertTrue(poseBefore.indices.count { poseBefore[it] != poseAfter[it] } > 50, "the tail bent at the pose")
		t.deletePath(id)
		assertTrue(t.paths(pose).none { it.id == id })
	}
}
