package io.github.psd2live.ipad

import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.config
import io.github.psd2live.format.compile.FrameSpec
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The skeleton on Boo's real rig (skipped without the file). */
class RigSkeletonTest {
	private fun frame(m: RigPreviewModel, pose: Map<String, Float>): IntArray {
		val ir = RigIrCompiler.compile(m)
		return IrFrameRenderer.open(ir, false).render(pose, 0f, FrameSpec.canvas(ir, 500), null).argb
	}

	@Test fun anAutoSkeletonPosesTheArmsAndSurvivesReopening() {
		val p = okio.Path.Companion.run { "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd".toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		RigSkeleton.auto(e, "arms")
		val spec = assertNotNull(RigSkeleton.spec(e))
		println("SKELETON bones: " + spec.bones.map { "${it.name}(${it.role}) -> ${it.parameterId}" })
		val arm = spec.bones.first { it.role == io.github.psd2live.core.BoneRole.UPPER_ARM }
		assertTrue(e.puppet.parameters.any { it.id.raw == arm.parameterId }, "the arm bone drives a parameter")
		val pose = mapOf(arm.parameterId to 40f)
		val fast = frame(e.model, pose)
		// What the saved project rebuilds on reopening is the same rig.
		val reopened = PSD2LivePipeline().buildPreview(source, documentOf(source, e.model.config).config(PipelineConfig(exportCmo3 = false)))
		val slow = frame(reopened, pose)
		val differ = fast.indices.count { fast[it] != slow[it] }
		println("SKELETON vs reopened: $differ px differ of ${fast.size}")
		assertEquals(0, differ, "the reopened project poses the same")
		val rest = frame(e.model, emptyMap())
		assertTrue(rest.indices.count { rest[it] != fast[it] } > 100, "posing the arm moves it")
		// Moving a joint, a saved pose, and undo.
		RigSkeleton.move(e, arm.id, "tail", arm.tailX + 10f, arm.tailY + 10f)
		assertEquals(arm.tailX + 10f, RigSkeleton.spec(e)!!.bone(arm.id)!!.tailX, 0.01f)
		RigSkeleton.savePose(e, "Wave", pose)
		assertEquals(pose, RigSkeleton.spec(e)!!.savedPoses["Wave"])
		e.undo(); e.undo(); e.undo()
		assertEquals(null, RigSkeleton.spec(e))
	}
}
