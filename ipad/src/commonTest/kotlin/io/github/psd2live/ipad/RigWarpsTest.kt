package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Editing a warp after making it, on Boo's real rig (skipped without the file). */
class RigWarpsTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"

	@Test fun curveControlsDivisionsAndGridEditAWarp() {
		if (!okio.FileSystem.SYSTEM.exists(path.toPath())) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		val tail = RigParts.list(e).first { it.name.startsWith("tail") }
		val id = RigStructure.addWarp(e, tail.meshes, "Tail warp")
		assertTrue(RigWarps.list(e).any { it.id == id })
		val key = RigWarps.key(e, id, emptyMap())
		val v0 = RigWarps.view(e, id, key)
		assertEquals(2, v0.bezierRows); assertEquals(2, v0.bezierColumns)
		val corner = v0.controls.first { it.direction == null && it.row == 0 && it.column == 0 }
		// Drag the corner anchor 30 canvas px right: it lands about 30 px right on the canvas.
		RigWarps.drag(e, id, key, corner, 30f, 0f)
		val v1 = RigWarps.view(e, id, key)
		val moved = v1.controls.first { it.direction == null && it.row == 0 && it.column == 0 }
		assertTrue(kotlin.math.abs(moved.x - corner.x - 30f) < 4f && kotlin.math.abs(moved.y - corner.y) < 4f, "the anchor follows the finger: ${corner.x},${corner.y} -> ${moved.x},${moved.y}")
		assertTrue(v1.lattice.indices.any { kotlin.math.abs(v1.lattice[it] - v0.lattice[it]) > 1f }, "the cage bent")
		// More curve sections, then a finer grid that keeps the shape.
		RigWarps.divisions(e, id, key, 3, 4)
		val v2 = RigWarps.view(e, id, key)
		assertEquals(3, v2.bezierRows); assertEquals(4, v2.bezierColumns)
		RigWarps.grid(e, id, 8, 8)
		assertEquals(8, RigWarps.list(e).first { it.id == id }.rows)
		// All of it survives a re-rig (it is in the journal); undo steps back.
		val rerig = RigEditing(PSD2LivePipeline().buildPreview(source, e.model.config), source)
		assertEquals(8, RigWarps.list(rerig).first { it.id == id }.rows)
		e.undo()
		assertTrue(RigWarps.list(e).first { it.id == id }.rows != 8, "undo takes the grid back")
	}
}
