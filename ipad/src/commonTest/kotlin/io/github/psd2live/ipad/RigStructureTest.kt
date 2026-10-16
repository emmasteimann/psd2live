package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** New warps and rotations on Boo's real rig (skipped without the file): the parts move under them and keep their pose. */
class RigStructureTest {
	@Test fun aNewWarpAndRotationHoldThePartsWithoutMovingThem() {
		val p = okio.Path.Companion.run { "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd".toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		val lantern = RigParts.list(e).first { it.name == "objects 2" }
		val before = RigParts.bounds(e, lantern)!!
		val warp = RigStructure.addWarp(e, lantern.meshes, "Lantern warp")
		assertTrue(e.puppet.deformers.any { it.id.raw == warp && it.name == "Lantern warp" })
		assertTrue(lantern.meshes.all { m -> e.puppet.drawables.first { it.id.raw == m }.parentDeformerId?.raw == warp }, "the lantern hangs from the new warp")
		val afterWarp = RigParts.bounds(e, lantern)!!
		assertTrue((0..3).all { kotlin.math.abs(afterWarp[it] - before[it]) < 0.5f }, "its pose is kept: ${before.toList()} vs ${afterWarp.toList()}")
		// A rotation pivoting at the lantern's top, above the warp.
		val rot = RigStructure.addRotation(e, lantern.meshes, "Lantern swing", (before[0] + before[2]) / 2, before[1])
		assertEquals(rot, e.puppet.drawables.first { it.id.raw == lantern.meshes.first() }.parentDeformerId?.raw)
		val afterRot = RigParts.bounds(e, lantern)!!
		assertTrue((0..3).all { kotlin.math.abs(afterRot[it] - before[it]) < 1f }, "still in place: ${before.toList()} vs ${afterRot.toList()}")
		// A full re-rig keeps both.
		val rerig = PSD2LivePipeline().buildPreview(source, e.model.config)
		assertTrue(rerig.rig.puppet.deformers.any { it.id.raw == warp } && rerig.rig.puppet.deformers.any { it.id.raw == rot })
		e.undo(); e.undo()
		assertTrue(e.puppet.deformers.none { it.id.raw == warp || it.id.raw == rot })
	}
}
