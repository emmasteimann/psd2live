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
}
