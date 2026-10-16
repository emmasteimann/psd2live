package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mesh editing by hand in Tweak (subdivide, merge, knife) on Boo's real rig (skipped without the file). */
class MeshEditingTest {
	@Test fun subdivideMergeAndKnifeChangeTheMeshAndUndo() {
		val p = okio.Path.Companion.run { "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd".toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val model = PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false))
		val outcome = RigOutcome("boo.psd", 0, 0, 0, emptyList(), "", emptyList(), 0.0, RigPreview(model), source, model.config, model)
		val t = RigTweaking(outcome)
		// The biggest mesh, so its points have neighbours to work with.
		val (mesh, pts) = t.vertices(emptyMap()).maxBy { it.second.size }
		fun count() = t.vertices(emptyMap()).toMap().getValue(mesh).size / 2
		val n0 = count()
		// Subdivide around one point: more points.
		assertTrue(t.topology(mesh, "subdivide", listOf(0)))
		val n1 = count(); assertTrue(n1 > n0, "subdivide adds points: $n0 -> $n1")
		// Merge two neighbours: one fewer.
		assertTrue(t.topology(mesh, "merge", listOf(0, 1)))
		assertEquals(n1 - 1, count(), "merge makes one point of two")
		// Knife across two points far apart: the cut adds points along it.
		val n2 = count(); val far = (0 until n2).maxBy { i -> val v = t.vertices(emptyMap()).toMap().getValue(mesh); kotlin.math.hypot(v[i * 2] - v[0], v[i * 2 + 1] - v[1]) }
		val cut = t.topology(mesh, "knife", listOf(0, far))
		if (cut) assertTrue(count() >= n2, "a knife never removes points")
		// A full re-rig from the journal keeps the edits.
		val rerig = PSD2LivePipeline().buildPreview(source, t.model.config)
		assertEquals(count(), rerig.rig.puppet.drawables.first { it.id.raw == mesh }.mesh!!.vertexCount)
		if (cut) t.undo(); t.undo(); t.undo()
		assertEquals(n0, count(), "undo puts the mesh back")
	}

	@Test fun inflateSpreadsPointsAwayFromTheBrush() {
		val p = okio.Path.Companion.run { "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd".toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val model = PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false))
		val outcome = RigOutcome("boo.psd", 0, 0, 0, emptyList(), "", emptyList(), 0.0, RigPreview(model), source, model.config, model)
		val t = RigTweaking(outcome)
		val pose = mapOf("ParamAngleX" to 30f)
		val (mesh, before) = t.vertices(pose).maxBy { it.second.size }
		var cx = 0f; var cy = 0f; for (i in before.indices step 2) { cx += before[i]; cy += before[i + 1] }; cx /= before.size / 2; cy /= before.size / 2
		t.targets = setOf(mesh)
		fun spread(v: FloatArray): Float { var d = 0f; for (i in v.indices step 2) { val dd = kotlin.math.hypot(v[i] - cx, v[i + 1] - cy); if (dd < 120f) d += dd }; return d }
		assertTrue(t.begin(pose, cx, cy, 120f, 0.5f, 1f, false, inflate = true))
		t.step(cx + 2f, cy); t.step(cx + 4f, cy); t.end()
		val after = t.vertices(pose).toMap().getValue(mesh)
		assertTrue(spread(after) > spread(before), "points near the brush moved outward")
	}
}
