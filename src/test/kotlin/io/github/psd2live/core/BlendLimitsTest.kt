package io.github.psd2live.core

import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.canvasGeometryCommand
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterRange
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Layerloom patch (ONE-APP-PLAN S4.6): blend-shape weight limits. */
class BlendLimitsTest {
	@Test
	fun capFollowsTheCurveAndTheLowestLimitWins() {
		val a = BlendWeightLimit(ParameterId("A"), listOf(BlendWeightLimitPoint(0f, 1f), BlendWeightLimitPoint(10f, 0f)))
		val b = BlendWeightLimit(ParameterId("B"), listOf(BlendWeightLimitPoint(0f, 0.5f)))
		assertEquals(1f, BlendLimits.cap(listOf(a), mapOf("A" to -5f), emptyMap()))
		assertEquals(0.75f, BlendLimits.cap(listOf(a), mapOf("A" to 2.5f), emptyMap()), 1e-6f)
		assertEquals(0f, BlendLimits.cap(listOf(a), mapOf("A" to 20f), emptyMap()))
		assertEquals(0.5f, BlendLimits.cap(listOf(a, b), mapOf("A" to 0f), emptyMap()))
	}

	/**
	 * A real rig (LAYERLOOM_PARITY_DIR; skipped without it): a blend shape keyed on Quill's face warp, limited by
	 * head turn, shows fully facing forward, half at AngleX 15 and not at 30.
	 */
	@Test
	fun limitedBlendShapeFadesWithTheLimitingParameter() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val blend = ParameterId("ParamTestBlend")
		val base = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd")).rig.puppet
			.withParameterCreated(blend, "Test blend", ParameterKind.BLEND_SHAPE).withParameterRange(blend, 0f, 0f, 1f)
		val warp = base.deformers.filterIsInstance<Deformer.Warp>().first { it.name == "Face Warp Deformer" }
		val defaults = base.parameters.associate { it.id.raw to it.default }
		val rest = RigGeometryTools.geometry(base, "warp", warp.id.raw, defaults).points
		val shifted = FloatArray(rest.size) { if (it % 2 == 0) rest[it] + 0.05f else rest[it] }
		val key = warp.geometryGrid!!.axes.associate { it.parameterId.raw to (defaults[it.parameterId.raw] ?: 0f) } + (blend.raw to 1f)
		val keyed = RigAuthoringJournal.apply(base, canvasGeometryCommand(EditHierarchyMode.DEFORM, "warp", warp.id.raw, key, shifted))
		assertTrue((keyed.deformers.first { it.id == warp.id } as Deformer.Warp).blendShapes.any { it.parameterId == blend }, "the edit made a blend shape")
		val limited = BlendLimits.apply(keyed, mapOf(blend.raw to listOf(BlendWeightLimit(ParameterId("ParamAngleX"),
			listOf(BlendWeightLimitPoint(0f, 1f), BlendWeightLimitPoint(30f, 0f))))))
		fun shift(model: org.umamo.runtime.model.PuppetModel, angle: Float): Float {
			val on = RigGeometryTools.geometry(model, "warp", warp.id.raw, defaults + mapOf(blend.raw to 1f, "ParamAngleX" to angle)).points
			val off = RigGeometryTools.geometry(model, "warp", warp.id.raw, defaults + mapOf(blend.raw to 0f, "ParamAngleX" to angle)).points
			return on.indices.filter { it % 2 == 0 }.map { on[it] - off[it] }.average().toFloat()
		}
		println("unlimited 0/15/30: ${shift(keyed, 0f)} ${shift(keyed, 15f)} ${shift(keyed, 30f)}; limited: ${shift(limited, 0f)} ${shift(limited, 15f)} ${shift(limited, 30f)}")
		assertEquals(0.05f, shift(limited, 0f), 2e-3f)
		assertEquals(0.025f, shift(limited, 15f), 3e-3f)
		assertTrue(abs(shift(limited, 30f)) < 2e-3f)
		assertEquals(0.05f, shift(keyed, 30f), 5e-3f)
	}
}
