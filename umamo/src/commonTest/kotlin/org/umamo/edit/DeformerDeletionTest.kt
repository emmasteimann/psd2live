package org.umamo.edit

import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

class DeformerDeletionTest {
	private val parameter = ParameterId("p")
	private val parentId = DeformerId("parent")
	private val childId = DeformerId("child")

	@Test fun rotationDeletionSamplesNonzeroAndInterpolatedDefaults() {
		val grid = KeyformGrid(listOf(KeyformAxis(parameter, floatArrayOf(-1f, 0f, 1f))),
			listOf(10f, 20f, 30f).mapIndexed { index, x ->
				KeyformCell(intArrayOf(index), RotationPivotForm(x, 0f, index * 10f, 1f + index * 0.1f))
			})
		for (default in listOf(-1f, -0.5f, 0.25f, 1f)) {
			assertPreserved(model(Deformer.Rotation(parentId, "parent", null, null, 15f, grid), default))
		}
	}

	@Test fun warpDeletionInterpolatesTheLatticeAtDefault() {
		val grid = KeyformGrid(listOf(KeyformAxis(parameter, floatArrayOf(-1f, 1f))),
			listOf(10f, 30f).mapIndexed { index, x -> KeyformCell(intArrayOf(index),
				WarpLatticeForm(floatArrayOf(x, 0f, x + 1f, 0f, x, 1f, x + 1f, 1f))) })
		assertPreserved(model(Deformer.Warp(parentId, "parent", null, null, 1, 1, true, grid), -0.5f))
	}

	@Test fun mirroredRotationDeletionKeepsTheRuntimeHeadingAndChildReflections() {
		for (flipX in listOf(false, true)) for (flipY in listOf(false, true)) {
			val parent = Deformer.Rotation(parentId, "parent", null, null, 35f,
				grid(RotationPivotForm(4f, 7f, 10f, 1.5f)), flipX = flipX, flipY = flipY)
			for (childFlip in listOf(false, true)) {
				val before = model(parent, 0f)
				assertPreserved(before.copy(deformers = before.deformers.map {
					if (it.id == childId) (it as Deformer.Rotation).copy(flipX = childFlip) else it
				}))
			}
		}
	}

	@Test fun deletionSamplesKeyedParentReflectionsAtDefault() {
		val flags = KeyformGrid<ChannelValue>(listOf(KeyformAxis(parameter, floatArrayOf(-1f, 1f))),
			listOf(KeyformCell(intArrayOf(0), ChannelValue.Flag(true)), KeyformCell(intArrayOf(1), ChannelValue.Flag(false))))
		val parent = Deformer.Rotation(parentId, "parent", null, null, 20f,
			grid(RotationPivotForm(3f, 2f, 0f, 1f)),
			channelGrids = ChannelGrids(mapOf(FormChannel.FLIP_X to flags, FormChannel.FLIP_Y to flags)))
		assertPreserved(model(parent, -1f))
	}

	private fun grid(form: RotationPivotForm) = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), form)))

	private fun model(parent: Deformer, default: Float): PuppetModel {
		val child = Deformer.Rotation(childId, "child", parent.id, null, 20f,
			grid(RotationPivotForm(0.4f, 0.7f, 5f, 1.2f)))
		return PuppetModel(listOf(Parameter(parameter, "p", -1f, 1f, default)), emptyList(),
			listOf(parent, child), emptyList(), emptyList(), null)
	}

	private fun assertPreserved(before: PuppetModel) {
		val after = before.withDeformerDeleted(parentId)
		val defaults = before.parameters.associate { it.id to it.default }
		val pose: (ParameterId) -> Float = { defaults[it] ?: 0f }
		val oldWorld = buildDeformerWorlds(before.deformers, pose, pose).getValue(childId)
		val newWorld = buildDeformerWorlds(after.deformers, pose, pose).getValue(childId)
		for ((x, y) in listOf(0f to 0f, 1f to 0f, 0f to 1f, -2f to 3f)) {
			val old = FloatArray(2); val new = FloatArray(2)
			oldWorld.apply(x, y, old, 0); newWorld.apply(x, y, new, 0)
			assertEquals(old[0], new[0], 0.001f, "X at ($x,$y)")
			assertEquals(old[1], new[1], 0.001f, "Y at ($x,$y)")
		}
	}
}
