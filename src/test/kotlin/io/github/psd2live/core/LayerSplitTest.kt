package io.github.psd2live.core

import io.github.psd2live.format.compile.FrameSpec
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cutting part of a layer into a new one, on real art (LAYERLOOM_PSD=<a character psd>; skipped without it): the
 * pieces composite back to the original exactly, the re-rig has one more mesh, existing meshes keep their IDs, and
 * hand tweaks elsewhere survive while those on the split layer are dropped (they no longer fit its new mesh).
 */
class LayerSplitTest {
	private fun frame(m: RigPreviewModel): IntArray { val ir = RigIrCompiler.compile(m); return IrFrameRenderer.open(ir, false).render(emptyMap(), 0f, FrameSpec.canvas(ir, 800), null).argb }

	@Test fun aSoftMaskSplitsALayerWithoutLosingPixels() {
		val psd = System.getenv("LAYERLOOM_PSD") ?: return
		val source = PsdReader.read(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(psd)))
		val pipeline = PSD2LivePipeline()
		var model = pipeline.buildPreview(source, PipelineConfig(exportCmo3 = false))
		// The layer with the most pixels: something worth cutting.
		val layer = source.layers.maxBy { it.raster.width * it.raster.height }
		val pose = mapOf("ParamAngleX" to 30f)
		val other = model.rig.puppet.drawables.first { d -> d.mesh != null && model.rig.layerIdByDrawableId[d.id.raw] != layer.id.raw }
		val g = RigGeometryTools.geometry(model.rig.puppet, "mesh", other.id.raw, pose)
		val px = g.points[0]; val py = -g.points[1]
		val s = PoseTweaks.begin(model.rig.puppet, pose, listOf(other.id.raw), px, py, 30f)
		for (i in 1..6) PoseTweaks.step(s, px + i * 3f, py)
		PoseTweaks.journaled(model, s)?.let { model = PoseTweaks.rebuilt(pipeline, model, it) }
		val journal = model.config.rigEdits.authoringJournal.size
		val space = PaintSpace.of(layer, source.widthPx, source.heightPx)
		val image = space.image(layer)
		val x0 = image.width * 0.6f
		val mask = newRigImage(image.width, image.height).also { m ->
			m.writeArgb(0, 0, image.width, image.height, IntArray(image.width * image.height) { i ->
				((((i % image.width - x0) / 6f + 0.5f).coerceIn(0f, 1f) * 255).toInt() shl 24) or 0xFF4060 })
		}
		val r = LayerSplit.split(model, layer.id.raw, space, image, mask, "${layer.name} part", "split-test", model.config.generationSource)
		// The two pieces' alphas add up to the original's, pixel for pixel.
		val restLayer = r.source.layers.first { it.id.raw == layer.id.raw }; val pieceLayer = r.source.layers.first { it.id.raw == "split-test" }
		fun alpha(l: org.umamo.format.art.SourceLayer) = (3 until l.raster.rgba.size step 4).sumOf { (l.raster.rgba[it].toInt() and 255).toLong() }
		assertEquals(alpha(layer), alpha(restLayer) + alpha(pieceLayer), "the pieces' alpha adds up to the original's")
		val after = pipeline.buildPreview(r.source, model.config.copy(generationSource = r.generationSource, rigEdits = r.rigEdits))
		assertEquals(model.rig.puppet.drawables.size + 1, after.rig.puppet.drawables.size, "one more mesh")
		for ((mesh, owner) in model.rig.layerIdByDrawableId) assertEquals(owner, after.rig.layerIdByDrawableId[mesh], "mesh $mesh keeps its layer")
		assertEquals(journal - r.droppedEdits, after.config.rigEdits.authoringJournal.size, "only the split layer's tweaks are dropped")
		val before = frame(model); val now = frame(after)
		val changed = before.indices.count { i -> (0..3).any { k -> kotlin.math.abs(((before[i] ushr (k * 8)) and 255) - ((now[i] ushr (k * 8)) and 255)) > 24 } }
		assertTrue(changed < before.size / 500, "the rest pose looks the same ($changed pixels changed)")
	}
}
