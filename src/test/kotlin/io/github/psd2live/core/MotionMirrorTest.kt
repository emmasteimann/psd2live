package io.github.psd2live.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Layerloom patch (ONE-APP-PLAN S4.4): mirrored motions. */
class MotionMirrorTest {
	private val ids = setOf("ParamArmLA", "ParamArmRA", "ParamEyeLOpen", "ParamEyeROpen", "ParamBrowLY", "ParamBrowRY",
		"Left Hand", "Right Hand", "hair_l_1", "hair_r_1", "ParamSmileLevel", "ParamAngleX", "ParamLeg")

	@Test
	fun counterparts() {
		assertEquals("ParamArmRA", MotionMirror.counterpart("ParamArmLA", ids))
		assertEquals("ParamEyeLOpen", MotionMirror.counterpart("ParamEyeROpen", ids))
		assertEquals("ParamBrowRY", MotionMirror.counterpart("ParamBrowLY", ids))
		assertEquals("Right Hand", MotionMirror.counterpart("Left Hand", ids))
		assertEquals("hair_r_1", MotionMirror.counterpart("hair_l_1", ids))
		assertNull(MotionMirror.counterpart("ParamSmileLevel", ids))
		assertNull(MotionMirror.counterpart("ParamAngleX", ids))
		assertNull(MotionMirror.counterpart("ParamLeg", ids))
	}

	/** On a real rig (LAYERLOOM_PARITY_DIR; skipped without it): pairs are symmetric, and a mirrored clip mirrors back. */
	@Test
	fun quillParametersMirrorCleanly() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val parameters = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd")).rig.puppet.parameters
		val all = parameters.mapTo(HashSet()) { it.id.raw }
		val pairs = all.mapNotNull { id -> MotionMirror.counterpart(id, all)?.let { id to it } }
		println("pairs: " + pairs.sortedBy { it.first }.joinToString { "${it.first}<->${it.second}" })
		println("sideways: " + parameters.filter { MotionMirror.counterpart(it.id.raw, all) == null && MotionMirror.isSideways(it) }.joinToString { it.id.raw })
		pairs.forEach { (a, b) -> assertEquals(a, MotionMirror.counterpart(b, all), "$a and $b pair both ways") }
		val curves = parameters.take(40).map { p -> MotionCurve(p.id.raw, listOf(MotionKey(0f, p.default), MotionKey(1f, (p.default + p.max) / 2f, outHandle = MotionHandle(0.3f, 0.2f)))) }
		val clip = MotionClip("motion_1", "Wave", curves = curves)
		val twice = MotionMirror.mirrored(clip.copy(curves = MotionMirror.mirrored(clip, parameters)), parameters)
		assertEquals(curves.sortedBy { it.parameterId }, twice.sortedBy { it.parameterId }, "mirroring twice gives the motion back")
	}
}
