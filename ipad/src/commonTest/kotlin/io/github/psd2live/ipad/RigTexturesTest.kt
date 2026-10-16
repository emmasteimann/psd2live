package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Texture settings on Boo (skipped without the file): page size and a part's detail repack the atlas. */
class RigTexturesTest {
	@Test fun pageSizeAndDetailRepackTheAtlas() {
		val p = okio.Path.Companion.run { "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd".toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		val face = RigTextures.parts(e).first { it.name == "face" }
		RigTextures.setDensity(e, face.layerId, 0.5f)
		val smaller = RigTextures.parts(e).first { it.layerId == face.layerId }
		println("TEX face ${face.w}x${face.h} -> ${smaller.w}x${smaller.h}")
		assertTrue(smaller.w < face.w * 0.75f, "half detail makes a smaller tile")
		assertEquals(0.5f, smaller.density)
		RigTextures.setBudget(e, 2048, 2, 2)
		assertEquals(2048, RigTextures.budget(e).pageSize)
		e.undo(); e.undo()
		assertEquals(null, RigTextures.parts(e).first { it.layerId == face.layerId }.density)
	}
}
