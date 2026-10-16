package io.github.psd2live.ipad

import io.github.psd2live.core.PipelineConfig
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Painting several of Boo's layers and applying them in one rebuild (skipped without the file). */
class PaintSeveralLayersTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"
	private val red = 0xFFFF0000.toInt()

	@Test fun twoPaintedLayersLandInOneApplyAndTheRestStay() {
		val p = okio.Path.Companion.run { path.toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val outcome = buildRig("paint-several-test", source, PipelineConfig(exportCmo3 = false), RigFiles.nowSeconds()) { _, _ -> }
		val layers = paintableLayers(outcome).filter { it.bounds.width > 40 && it.bounds.height > 40 }
		assertTrue(layers.size >= 3, "paintable: ${layers.map { it.name }}")
		val (a, b, untouched) = Triple(layers[0], layers[1], layers[2])
		fun middle(l: org.umamo.format.art.SourceLayer) = (l.bounds.left + l.bounds.width / 2f) to (l.bounds.top + l.bounds.height / 2f)
		val paintings = listOf(a, b, untouched).map { LayerPainting(outcome, it) }
		for (painting in paintings.take(2)) {
			val (x, y) = middle(painting.layer)
			painting.beginStroke(); painting.segment(x - 6f, y, x + 6f, y, 8f, 1f, red, 1f, false); painting.endStroke("Brush")
			assertEquals(red, painting.pixelAt(x, y), "painted ${painting.layer.name}")
		}
		assertTrue(!paintings[2].isDirty)
		val (ux, uy) = middle(untouched)
		val untouchedBefore = paintings[2].pixelAt(ux, uy)

		val applied = applyPaintings(outcome, paintings) { _, _ -> }
		fun after(l: org.umamo.format.art.SourceLayer) = LayerPainting(applied, applied.source.layers.first { it.id.raw == l.id.raw })
		for (l in listOf(a, b)) {
			val (x, y) = middle(l)
			assertEquals(red, after(l).pixelAt(x, y), "${l.name} kept its paint through the one rebuild")
		}
		assertEquals(untouchedBefore, after(untouched).pixelAt(ux, uy), "an unpainted layer is left alone")
		// Meshes stay: the same drawables as before the paint.
		assertEquals(outcome.drawables, applied.drawables)
		assertNotEquals(outcome.model, applied.model)
	}

	@Test fun paintPastTheEdgeGetsANewMeshAndShows() {
		val p = okio.Path.Companion.run { path.toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		val outcome = buildRig("paint-edge-test", source, PipelineConfig(exportCmo3 = false), RigFiles.nowSeconds()) { _, _ -> }
		val rest = outcome.preview.parameters.associate { it.id to it.default }
		val layers = paintableLayers(outcome).filter { it.bounds.width > 40 && it.bounds.height > 40 }
		val (inside, outside) = layers[0] to layers[1]
		// Inside: a stroke through the layer's middle stays inside its mesh.
		val a = LayerPainting(outcome, inside)
		val (ax, ay) = (inside.bounds.left + inside.bounds.width / 2f) to (inside.bounds.top + inside.bounds.height / 2f)
		a.beginStroke(); a.segment(ax - 6f, ay, ax + 6f, ay, 8f, 1f, red, 1f, false); a.endStroke("Brush")
		assertTrue(!a.pastEdge(), "a stroke in the middle of ${inside.name} stays inside its mesh")
		// Outside: a blob in the canvas corner, far from the layer and from the character.
		val b = LayerPainting(outcome, outside)
		val (bx, by) = 30f to 30f
		assertEquals(0, outcome.preview.render(rest, bx, by, 1f, 1f, 1, 1).argb[0] ushr 24, "the corner starts empty")
		b.beginStroke(); b.segment(bx - 10f, by, bx + 10f, by, 14f, 1f, red, 1f, false); b.endStroke("Brush")
		assertTrue(b.pastEdge() && b.rebuildsMesh, "paint in the corner is past ${outside.name}'s edge")

		val applied = applyPaintings(outcome, listOf(a, b)) { _, _ -> }
		val shown = applied.preview.render(rest, bx, by, 1f, 1f, 1, 1).argb[0]
		assertTrue((shown ushr 24) > 200 && (shown shr 16 and 255) > 200 && (shown shr 8 and 255) < 60, "the paint past the edge shows on the rig: ${shown.toUInt().toString(16)}")
		val mid = applied.preview.render(rest, ax, ay, 1f, 1f, 1, 1).argb[0]
		assertTrue((mid shr 16 and 255) > 150, "the inside repaint still lands: ${mid.toUInt().toString(16)}")
		assertEquals(outcome.drawables, applied.drawables, "a rebuilt mesh replaces the old one; no art mesh is added or lost")
		assertTrue(applied.config.rigEdits.authoringJournal.size > outcome.config.rigEdits.authoringJournal.size, "the rebuild is recorded in the document")

		// Keep the old mesh: the paint past the edge is cut off, as the desktop's "Keep existing".
		val c = LayerPainting(outcome, outside)
		c.beginStroke(); c.segment(bx - 10f, by, bx + 10f, by, 14f, 1f, red, 1f, false); c.endStroke("Brush")
		c.keepMesh = true
		val kept = applyPaintings(outcome, listOf(c)) { _, _ -> }
		assertEquals(0, kept.preview.render(rest, bx, by, 1f, 1f, 1, 1).argb[0] ushr 24, "kept mesh: the corner stays empty")
	}
}
