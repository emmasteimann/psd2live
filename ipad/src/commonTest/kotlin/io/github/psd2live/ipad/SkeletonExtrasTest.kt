package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The skeleton's extra tools on Boo's real rig (skipped without the file). */
class SkeletonExtrasTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"

	private fun editing(): RigEditing? {
		if (!okio.FileSystem.SYSTEM.exists(path.toPath())) return null
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		return RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
	}

	@Test fun newBoneMirrorSubdivideDissolveFlexIkAndSymmetry() {
		val e = editing() ?: return
		RigSkeleton.auto(e, "arms")
		val start = assertNotNull(RigSkeleton.spec(e))
		val arm = start.bones.first { it.role.limb && it.parentId != null && start.bone(it.parentId!!)?.role?.limb != true }
		// A new bone hanging off the arm, drawn below its tip.
		val made = assertNotNull(RigSkeleton.create(e, arm.tailX, arm.tailY, arm.tailX, arm.tailY + 60f, arm.id))
		val spec1 = assertNotNull(RigSkeleton.spec(e))
		assertEquals(start.bones.size + 1, spec1.bones.size)
		assertEquals(arm.id, spec1.bone(made)!!.parentId)
		assertTrue(e.puppet.parameters.any { it.id.raw == spec1.bone(made)!!.parameterId }, "the new bone gets its own parameter in the rig")
		// Split in two, then merge back.
		RigSkeleton.subdivide(e, made, 2)
		assertEquals(spec1.bones.size + 1, RigSkeleton.spec(e)!!.bones.size)
		e.undo()
		assertEquals(spec1.bones.size, RigSkeleton.spec(e)!!.bones.size, "undo takes the split back")
		// Mirror: a reflected copy across the symmetry line.
		RigSkeleton.symmetry(e, e.puppet.canvasWidth / 2f)
		val mirrored = assertNotNull(RigSkeleton.mirror(e, made))
		val m = RigSkeleton.spec(e)!!.bone(mirrored)!!
		val axis = e.puppet.canvasWidth / 2f
		assertEquals(axis - (spec1.bone(made)!!.headX - axis), m.headX, 1f)
		// Flexible chain on, then straightened and back.
		RigSkeleton.flex(e, arm.id, true)
		assertTrue(RigSkeleton.spec(e)!!.bone(arm.id)!!.flex)
		RigSkeleton.flex(e, arm.id, false)
		assertTrue(!RigSkeleton.spec(e)!!.bone(arm.id)!!.flex)
		// Merge: split the new bone in two, then fold the second half into the first - one bone again.
		val count = RigSkeleton.spec(e)!!.bones.size
		RigSkeleton.subdivide(e, made, 2)
		assertEquals(count + 1, RigSkeleton.spec(e)!!.bones.size)
		val half = RigSkeleton.spec(e)!!.bones.first { it.parentId == made }
		assertTrue(io.github.psd2live.core.SkeletonAuthoring.canDissolve(RigSkeleton.spec(e)!!, half.id))
		RigSkeleton.dissolve(e, half.id)
		assertEquals(count, RigSkeleton.spec(e)!!.bones.size, "dissolving a split segment joins it back")
		// IK: dragging the forearm tip pulls the chain toward the finger.
		val lower = RigSkeleton.spec(e)!!.bones.first { b -> b.role.limb && b.parentId == arm.id && b.id != made && b.id != mirrored }
		RigSkeleton.ik(e, lower.id, 2, 1)
		assertEquals(2, RigSkeleton.spec(e)!!.bone(lower.id)!!.ik.chainLength)
		val bones = RigSkeleton.posed(e, emptyMap())
		val tip = bones.first { it.bone.id == lower.id }
		val target = tip.tailX + 25f to tip.tailY - 25f
		var pose = emptyMap<String, Float>()
		repeat(4) { pose = RigSkeleton.dragPose(e, RigSkeleton.posed(e, pose), lower.id, true, target.first, target.second, pose) }
		val after = RigSkeleton.posed(e, pose).first { it.bone.id == lower.id }
		val before = hypot(tip.tailX - target.first, tip.tailY - target.second)
		val now = hypot(after.tailX - target.first, after.tailY - target.second)
		assertTrue(now < before * 0.6f, "the tip moves toward the drag: $before -> $now")
		assertTrue(pose.size >= 2, "more than one bone turns: $pose")
		// It all survives a full re-rig from the stored edits.
		val rerig = RigEditing(PSD2LivePipeline().buildPreview(e.source!!, e.model.config), e.source)
		assertEquals(RigSkeleton.spec(e)!!.bones.size, RigSkeleton.spec(rerig)!!.bones.size)
	}

	@Test fun weightsCopyMirroredFromOneSleeveToTheOther() {
		val e = editing() ?: return
		RigSkeleton.auto(e, "arms")
		val spec = assertNotNull(RigSkeleton.spec(e))
		val meshes = RigSkeletonWeights.meshes(e, spec)
		// A part on the left arm and one on the right: the pair a mirrored copy is for.
		fun side(id: String) = RigSkeletonWeights.bones(spec, id).mapNotNull { spec.bone(it.id)?.side }.firstOrNull { it != io.github.psd2live.core.Side.NONE }
		val source = meshes.first { side(it.drawableId) == io.github.psd2live.core.Side.LEFT }.drawableId
		val target = meshes.first { side(it.drawableId) == io.github.psd2live.core.Side.RIGHT }.drawableId
		val axis = e.puppet.canvasWidth / 2f
		val withAxis = spec.copy(symmetryAxisX = axis)
		val next = RigSkeletonWeights.transfer(e, withAxis, source, target, mirror = true)
		assertTrue(next.manualWeights.containsKey(target), "the target gets copied weights")
		assertTrue(!withAxis.manualWeights.containsKey(target))
	}
}
