package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Draw order on Boo's real rig (skipped without the file): a part moved in front stays there, reset and undo give it back. */
class RigDrawOrderTest {
	@Test fun aPartMovedInFrontStaysThereAndResets() {
		val p = okio.Path.Companion.run { "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd".toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		val list = RigDrawOrder.list(e)
		println("DRAWORDER " + list.joinToString { "${it.name}=${it.order}" })
		val back = list.last()
		RigDrawOrder.set(e, back.layerId, 1000f)
		val now = RigDrawOrder.list(e)
		assertEquals(back.layerId, now.first().layerId, "the back part is in front")
		assertTrue(now.first().overridden)
		// The renderer draws it last: its mesh is the last drawn at rest.
		val frontMesh = e.puppet.drawables.filter { e.model.rig.layerIdByDrawableId[it.id.raw] == back.layerId }.map { it.drawOrder }
		assertTrue(frontMesh.all { it == 1000f }, "its meshes carry the order: $frontMesh")
		RigDrawOrder.set(e, back.layerId, null)
		assertEquals(back.layerId, RigDrawOrder.list(e).last().layerId, "reset gives back the generated order")
		e.undo()
		assertEquals(back.layerId, RigDrawOrder.list(e).first().layerId, "undo brings the override back")
		// Saved with the project's settings and kept by a full re-rig.
		val rerig = PSD2LivePipeline().buildPreview(source, e.model.config)
		assertEquals(back.layerId, RigDrawOrder.list(RigEditing(rerig, source)).first().layerId)
		assertTrue(io.github.psd2live.project.WorkspaceSettingsCodec.encode(e.model.config).toString().contains(back.layerId))
	}
}
