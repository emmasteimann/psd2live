package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.project.config
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop's splits on Boo's real rig through the iPad's editing session (skipped without the file). */
class RigSplitsTest {
	private fun boo(): RigEditing? = rig("/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd")
	private fun rig(path: String): RigEditing? {
		val p = okio.Path.Companion.run { path.toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return null
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		return RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
	}

	@Test fun islandsSplitIntoNamedPartsAndUndo() {
		// Boo's layers are cut cleanly; Quill's art has layers with several islands.
		val e = rig("/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-quill3/quill_v3.psd") ?: return
		val before = RigParts.list(e)
		val (part, centers) = before.asSequence().map { it to RigSplits.islands(e, it.layerId) }.firstOrNull { it.second.size >= 2 }
			?: run { println("SPLITS no part with islands"); return }
		// The dots marking each island sit on the part (canvas units), not off to one side (Goose saw them adrift 10-16).
		val box = io.github.psd2live.core.LayerSpace.of(e.model.analysis.source.layers.first { it.id.raw == part.layerId }).canvasBounds()
		assertTrue(centers.all { (x, y) -> x >= box.left - 1f && x <= box.right + 1f && y >= box.top - 1f && y <= box.bottom + 1f },
			"island centres $centers inside ${part.name}'s box $box")
		val names = RigSplits.names(part.name, centers)
		println("SPLITS ${part.name} -> $names")
		RigSplits.splitIslands(e, part.layerId, names)
		val after = RigParts.list(e)
		assertTrue(names.all { n -> after.any { it.name == n } }, "the pieces are parts: ${after.map { it.name }}")
		assertTrue(after.none { it.layerId == part.layerId }, "the original is replaced")
		// The project document rebuilds the same rig (as reopening would).
		val again = PSD2LivePipeline().buildPreview(e.source!!, documentOf(e.source!!, e.model.config).config(PipelineConfig(exportCmo3 = false)))
		assertEquals(e.puppet.drawables.size, again.rig.puppet.drawables.size)
		e.undo()
		assertEquals(before.map { it.layerId }, RigParts.list(e).map { it.layerId }, "undo gives back the original part")
	}

	@Test fun aPolygonCutsAPartInTwo() {
		val e = boo() ?: return
		val part = RigParts.list(e).first { it.name.startsWith("tail") }
		val b = RigParts.bounds(e, part)!!
		val midX = (b[0] + b[2]) / 2
		// The left half of the tail's box.
		RigSplits.splitPolygon(e, part.layerId, listOf(b[0] - 5 to b[1] - 5, midX to b[1] - 5, midX to b[3] + 5, b[0] - 5 to b[3] + 5), "tail left", "tail right")
		val names = RigParts.list(e).map { it.name }
		assertTrue("tail left" in names && "tail right" in names, "both halves: $names")
	}

	@Test fun aDepthSplitPutsAPartBetweenTwoSlices() {
		val e = boo() ?: return
		val parts = RigParts.list(e)
		val neckwear = parts.first { it.name == "neckwear" }; val topwear = parts.first { it.name.startsWith("topwear") }
		val before = e.puppet.drawables.size
		RigSplits.splitDepth(e, topwear.meshes.first(), neckwear.meshes.first())
		println("DEPTH parts now: " + RigParts.list(e).map { it.name })
		assertTrue(e.puppet.drawables.size > before, "a slice was added")
	}
}
