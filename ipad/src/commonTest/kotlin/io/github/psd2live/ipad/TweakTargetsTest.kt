package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertTrue

/** Tweak by hand with target parts (Goose, 2026-10-16): a push moves only the targeted parts. Boo (skipped without the file). */
class TweakTargetsTest {
	@Test fun aPushWithATargetLeavesTheOtherPartsUnderTheBrushAlone() {
		val p = okio.Path.Companion.run { "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd".toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val model = PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false))
		val outcome = RigOutcome("boo.psd", 0, 0, 0, emptyList(), "", emptyList(), 0.0, RigPreview(model), source, model.config, model)
		val t = RigTweaking(outcome)
		val pose = mapOf("ParamAngleX" to 30f)
		// A spot two or more meshes cover: the middle of the face, a big brush.
		val (cx, cy) = t.canvasWidth / 2 to t.canvasHeight * 0.3f
		val under = t.vertices(pose).map { it.first }.toSet()
		val target = t.meshAt(pose, cx, cy) ?: error("nothing at the face")
		fun positions() = t.vertices(pose).toMap()
		t.targets = setOf(target)
		val before = positions()
		assertTrue(before.keys == setOf(target), "only the target's points are offered: ${before.keys}")
		t.targets = emptySet()
		val allBefore = positions()
		t.targets = setOf(target)
		assertTrue(t.begin(pose, cx, cy, 200f, 0.5f, 1f, false))
		t.step(cx + 40f, cy); t.end()
		t.targets = emptySet()
		val after = positions()
		fun moved(id: String) = allBefore.getValue(id).indices.any { kotlin.math.abs(allBefore.getValue(id)[it] - after.getValue(id)[it]) > 0.5f }
		assertTrue(moved(target), "the target moved")
		val others = under - target
		assertTrue(others.isNotEmpty() && others.none(::moved), "no other part moved: ${others.filter(::moved)}")
	}
}
