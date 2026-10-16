package io.github.psd2live.ipad

import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.format.compile.FrameSpec
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertTrue

/** Simulation presets on Boo's real rig (skipped without the file). */
class RigSimulationTest {
	@Test fun theClothingPresetBakesASimulationThatMovesHerRobe() {
		val p = okio.Path.Companion.run { "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd".toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		val before = e.puppet.parameters.map { it.id.raw }.toSet()
		var last = 0f
		val report = RigSimulation.preset(e, "clothing") { last = it }
		val sims = RigSimulation.list(e)
		println("SIM report=$report sims=" + sims.map { "${it.id} ${it.kind} targets=${it.targets} baked=${it.baked} fit=${it.fit}" } + " progress=$last")
		assertTrue(sims.isNotEmpty(), "a cloth simulation was made")
		val added = e.puppet.parameters.map { it.id.raw }.filter { it !in before }
		println("SIM new parameters: $added")
		if (sims.any { it.baked } && added.isNotEmpty()) {
			val ir = RigIrCompiler.compile(e.model); val r = IrFrameRenderer.open(ir, false)
			val rest = r.render(emptyMap(), 0f, FrameSpec.canvas(ir, 400), null).argb
			val moved = r.render(mapOf("ParamBodyAngleX" to 10f) + added.associateWith { 1f }, 0f, FrameSpec.canvas(ir, 400), null).argb
			assertTrue(rest.indices.count { rest[it] != moved[it] } > 100)
		}
		RigSimulation.delete(e, sims.first().id)
		assertTrue(RigSimulation.list(e).none { it.id == sims.first().id })
		e.undo()
		assertTrue(RigSimulation.list(e).any { it.id == sims.first().id }, "undo brings it back")
	}
}
