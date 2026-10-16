package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Layer roles set by hand on Boo's real rig (skipped without the file). */
class RigLayersTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"

	@Test fun aRoleSetByHandRigsTheLayerForItAndSurvivesAReRig() {
		if (!okio.FileSystem.SYSTEM.exists(path.toPath())) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		val layers = RigLayers.list(e)
		assertTrue(layers.size > 10 && layers.none { it.chosen }, "guessed from names at first")
		// An object layer rigged as front hair instead: it now gets the front hair's motion.
		val obj = layers.first { it.tag == SemanticTag.OBJECTS || it.tag == SemanticTag.HEADWEAR }
		val drawable = { e.model.rig.puppet.drawables.first { e.model.rig.layerIdByDrawableId[it.id.raw] == obj.id } }
		val parentBefore = drawable().parentDeformerId
		RigLayers.classify(e, obj.id, SemanticTag.FRONT_HAIR, Side.NONE)
		val after = RigLayers.list(e).first { it.id == obj.id }
		assertEquals(SemanticTag.FRONT_HAIR, after.tag); assertTrue(after.chosen)
		assertTrue(drawable().parentDeformerId != parentBefore, "rigged as front hair it hangs from another deformer: $parentBefore -> ${drawable().parentDeformerId}")
		// Kept in the config, so a full re-rig keeps it.
		val rerig = RigEditing(PSD2LivePipeline().buildPreview(source, e.model.config), source)
		assertEquals(SemanticTag.FRONT_HAIR, RigLayers.list(rerig).first { it.id == obj.id }.tag)
		// Unchanged classification changes nothing; automatic forgets it.
		val undoDepth = e.canUndo
		RigLayers.classify(e, obj.id, SemanticTag.FRONT_HAIR, Side.NONE)
		RigLayers.automatic(e, obj.id)
		val back = RigLayers.list(e).first { it.id == obj.id }
		assertEquals(obj.tag, back.tag); assertTrue(!back.chosen && undoDepth)
		assertEquals(parentBefore, drawable().parentDeformerId, "back to its old place")
	}
}
