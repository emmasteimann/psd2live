package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ParameterKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Masks, blends, culling, user data, hit areas, pose groups and blend limits on Boo's real rig (skipped without it). */
class RigPartLookTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"

	private fun editing(): RigEditing? {
		if (!okio.FileSystem.SYSTEM.exists(path.toPath())) return null
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		return RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
	}

	@Test fun partLookEditsLandUndoAndSurviveARerig() {
		val e = editing() ?: return
		val meshes = e.puppet.drawables.filter { it.mesh != null }
		val target = meshes.first { it.maskedBy.isEmpty() }
		val masker = meshes.first { it.id != target.id }
		RigPartLook.masks(e, target.id.raw, listOf(masker.id.raw))
		RigPartLook.invert(e, target.id.raw, true)
		RigPartLook.blend(e, target.id.raw, BlendMode.Screen)
		RigPartLook.alpha(e, target.id.raw, AlphaBlendMode.Atop)
		RigPartLook.culling(e, target.id.raw, true)
		RigPartLook.userData(e, target.id.raw, "blush")
		RigPartLook.hitArea(e, target.id.raw, "Cheek")
		val info = assertNotNull(RigPartLook.info(e, target.id.raw))
		assertEquals(listOf(masker.id.raw), info.maskedBy)
		assertTrue(info.invertMask && info.culling)
		assertEquals(BlendMode.Screen, info.blend); assertEquals(AlphaBlendMode.Atop, info.alpha)
		assertEquals("blush", info.userData); assertEquals("Cheek", info.hitArea)
		assertTrue(RigPartLook.needs53(info))
		// The preview draws it differently once screened and clipped.
		e.undo()
		assertEquals(null, RigPartLook.info(e, target.id.raw)!!.hitArea, "undo takes the last step back")
		RigPartLook.hitArea(e, target.id.raw, "Cheek")
		val rerig = RigEditing(PSD2LivePipeline().buildPreview(e.source!!, e.model.config), e.source)
		val again = assertNotNull(RigPartLook.info(rerig, target.id.raw))
		assertEquals(listOf(masker.id.raw), again.maskedBy); assertEquals(BlendMode.Screen, again.blend)
		assertEquals("blush", again.userData); assertEquals("Cheek", again.hitArea)
	}

	@Test fun poseGroupsAndBlendLimits() {
		val e = editing() ?: return
		val parts = RigPoseGroups.parts(e)
		assertTrue(parts.size >= 3)
		RigPoseGroups.put(e, listOf(listOf(parts[0].id, parts[1].id)), 0.3f, mapOf(parts[0].id to listOf(parts[2].id)))
		assertEquals(listOf(listOf(parts[0].id, parts[1].id)), RigPoseGroups.groups(e))
		assertEquals(0.3f, RigPoseGroups.fade(e), 1e-4f)
		assertEquals(mapOf(parts[0].id to listOf(parts[2].id)), RigPoseGroups.links(e))
		// A blend-shape parameter limited by another.
		val p = e.puppet.parameters
		val blend = p.firstOrNull { it.kind == ParameterKind.BLEND_SHAPE }
		if (blend != null) {
			val by = p.first { it.id != blend.id && it.kind != ParameterKind.BLEND_SHAPE }
			RigBlendLimits.put(e, blend.id.raw, listOf(RigBlendLimits.fade(e, by.id.raw, by.default, by.max)))
			assertEquals(1, RigBlendLimits.limits(e, blend.id.raw).size)
			RigBlendLimits.put(e, blend.id.raw, emptyList())
			assertTrue(RigBlendLimits.limits(e, blend.id.raw).isEmpty())
		} else println("PARTLOOK Boo has no blend-shape parameter; limits checked by the desktop's own rules only")
	}
}
