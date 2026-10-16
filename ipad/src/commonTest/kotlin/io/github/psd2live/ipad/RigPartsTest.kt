package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.project.transform
import org.umamo.format.psd.PsdReader
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Moving, scaling and turning whole parts on Boo's real rig (skipped without the file). */
class RigPartsTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"

	@Test fun aMovedPartMovesItsMeshesKeepsItsPlacementAndUndoes() {
		val p = okio.Path.Companion.run { path.toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val model = PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false))
		val e = RigEditing(model, source)
		val parts = RigParts.list(e)
		assertTrue(parts.size > 10, "parts: ${parts.map { it.name }}")
		val lantern = parts.firstOrNull { it.name.contains("object", true) || it.name.contains("lantern", true) } ?: parts.first()
		val before = assertNotNull(RigParts.bounds(e, lantern))
		RigParts.transform(e, lantern, RigParts.transformOf(20f, -10f, 1f, 0f, 0f, 0f))
		val after = assertNotNull(RigParts.bounds(e, RigParts.list(e).first { it.layerId == lantern.layerId }))
		assertTrue(abs(after[0] - before[0] - 20f) < 0.5f && abs(after[1] - before[1] + 10f) < 0.5f, "moved by (20, -10): ${before.toList()} -> ${after.toList()}")
		val layer = e.source!!.layers.first { it.id.raw == lantern.layerId }
		assertEquals(20f, layer.transform.e, 0.01f); assertEquals(-10f, layer.transform.f, 0.01f)
		// Turned and scaled about its middle: still one undo step each, and the box grows.
		val cx = (after[0] + after[2]) / 2; val cy = (after[1] + after[3]) / 2
		RigParts.transform(e, RigParts.list(e).first { it.layerId == lantern.layerId }, RigParts.transformOf(0f, 0f, 1.5f, 0f, cx, cy))
		val bigger = RigParts.bounds(e, RigParts.list(e).first { it.layerId == lantern.layerId })!!
		assertTrue(bigger[2] - bigger[0] > (after[2] - after[0]) * 1.4f, "scaled 1.5x")
		// A full re-rig from the edits keeps it.
		val rerig = PSD2LivePipeline().buildPreview(e.source!!, e.model.config)
		val again = RigParts.bounds(RigEditing(rerig, e.source), lantern)!!
		assertTrue(abs(again[0] - bigger[0]) < 0.5f, "re-rig keeps the move: ${again.toList()} vs ${bigger.toList()}")
		e.undo(); e.undo()
		val back = RigParts.bounds(e, lantern)!!
		assertTrue(abs(back[0] - before[0]) < 0.01f && e.source!!.layers.first { it.id.raw == lantern.layerId }.transform.isIdentity, "undo puts the mesh and the placement back")
	}
}
