package io.github.psd2live.ipad

import io.github.psd2live.core.CanvasWeightAuthoring
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.SkeletonWeightBrushMode
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import org.umamo.runtime.model.VertexGroupKind
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Weights painted by hand on Boo's real rig (skipped without the file). */
class RigWeightsTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"

	private fun editing(): RigEditing? {
		if (!okio.FileSystem.SYSTEM.exists(path.toPath())) return null
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		return RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
	}

	@Test fun simulationWeightsPaintUndoFillInvertAndRemove() {
		val e = editing() ?: return
		val meshes = RigSimulationWeights.meshes(e)
		val target = meshes.first { m -> RigSimulationWeights.weights(e, m.drawableId, VertexGroupKind.PIN)!!.weights.size > 30 }
		val before = assertNotNull(RigSimulationWeights.weights(e, target.drawableId, VertexGroupKind.PIN))
		assertTrue(before.weights.all { it == 0f } && !RigSimulationWeights.has(e, target.drawableId, VertexGroupKind.PIN))
		// A stroke through the mesh's middle vertex.
		val mid = before.positions.size / 4 * 2
		val (x, y) = before.positions[mid] to before.positions[mid + 1]
		RigSimulationWeights.paint(e, target.drawableId, VertexGroupKind.PIN, listOf(x - 5f to y, x + 5f to y), 30f, 0.8f, CanvasWeightAuthoring.Mode.ADD, 1f)
		val after = assertNotNull(RigSimulationWeights.weights(e, target.drawableId, VertexGroupKind.PIN))
		assertTrue(after.weights[mid / 2] > 0.5f, "the vertex under the stroke is pinned: ${after.weights[mid / 2]}")
		val far = after.positions.indices.step(2).filter { hypot(after.positions[it] - x, after.positions[it + 1] - y) > 80f }
		assertTrue(far.all { after.weights[it / 2] == 0f }, "vertices far from the stroke are untouched")
		e.undo()
		assertTrue(!RigSimulationWeights.has(e, target.drawableId, VertexGroupKind.PIN), "undo takes the group away again")
		RigSimulationWeights.fill(e, target.drawableId, VertexGroupKind.STIFFNESS, 1f)
		assertTrue(RigSimulationWeights.weights(e, target.drawableId, VertexGroupKind.STIFFNESS)!!.weights.all { it == 1f })
		RigSimulationWeights.invert(e, target.drawableId, VertexGroupKind.STIFFNESS)
		assertTrue(RigSimulationWeights.weights(e, target.drawableId, VertexGroupKind.STIFFNESS)!!.weights.all { it == 0f })
		RigSimulationWeights.delete(e, target.drawableId, VertexGroupKind.STIFFNESS)
		assertTrue(!RigSimulationWeights.has(e, target.drawableId, VertexGroupKind.STIFFNESS))
		// The painted group survives a full re-rig from the edits (it is in the journal).
		RigSimulationWeights.fill(e, target.drawableId, VertexGroupKind.MASS, 0.7f)
		val rerig = RigEditing(PSD2LivePipeline().buildPreview(e.source!!, e.model.config), e.source)
		assertEquals(0.7f, RigSimulationWeights.weights(rerig, target.drawableId, VertexGroupKind.MASS)!!.weights.first(), 0.001f)
	}

	@Test fun skeletonWeightsPaintedThenAppliedStayInTheRig() {
		val e = editing() ?: return
		RigSkeleton.auto(e, "arms")
		val spec = assertNotNull(RigSkeleton.spec(e))
		val mesh = RigSkeletonWeights.meshes(e, spec).first { RigSkeletonWeights.bones(spec, it.drawableId).size >= 2 }
		val bones = RigSkeletonWeights.bones(spec, mesh.drawableId)
		// The bone that moves the mesh's middle vertex least: painting it on is a visible change.
		val start = bones.associateWith { assertNotNull(RigSkeletonWeights.weights(e, spec, mesh.drawableId, it.id)) }
		val probe = start.values.first().positions.size / 4 * 2
		val bone = bones.minBy { start.getValue(it).weights[probe / 2] }
		val base = start.getValue(bone)
		val (x, y) = base.positions[probe] to base.positions[probe + 1]
		val draft = RigSkeletonWeights.paint(e, spec, mesh.drawableId, bone.id, listOf(x to y), 25f, 1f, SkeletonWeightBrushMode.ADD)
		val painted = assertNotNull(RigSkeletonWeights.weights(e, draft, mesh.drawableId, bone.id))
		assertTrue(painted.weights[probe / 2] > base.weights[probe / 2] + 0.3f, "${bone.name} now moves that vertex: ${base.weights[probe / 2]} -> ${painted.weights[probe / 2]}")
		assertTrue(RigSkeleton.spec(e)!!.manualWeights[mesh.drawableId] == null, "a stroke only edits the draft, not the rig")
		RigSkeletonWeights.apply(e, draft)
		val applied = assertNotNull(RigSkeleton.spec(e))
		assertTrue(applied.manualWeights.containsKey(mesh.drawableId), "the rig carries the painted weights")
		val again = assertNotNull(RigSkeletonWeights.weights(e, applied, mesh.drawableId, bone.id))
		assertEquals(painted.weights[probe / 2], again.weights[probe / 2], 0.02f)
	}
}
