package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An image added as a layer of Boo's real rig (skipped without the file). */
class RigAssetLayersTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"

	@Test fun anImageBecomesALayerWithItsOwnMeshThatMovesWithItsNeighbour() {
		if (!okio.FileSystem.SYSTEM.exists(path.toPath())) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		val layers = e.source!!.layers.size; val meshes = e.model.rig.puppet.drawables.size
		// A 40x30 red block with a transparent border, placed at 2 canvas units a pixel above a hair layer.
		val w = 48; val h = 38
		val argb = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 4 until 44 && y in 4 until 34) 0xFFFF2020.toInt() else 0 }
		// Above the top layer of the stack (a lower order is higher), so nothing in front covers the block.
		val hair = e.source!!.layers.filter { e.model.rig.puppet.drawables.any { d -> e.model.rig.layerIdByDrawableId[d.id.raw] == it.id.raw } }.minBy { it.order }
		val left = hair.bounds.left.toFloat(); val top = hair.bounds.top.toFloat()
		val id = RigAssetLayers.add(e, argb, w, h, "Bow", RigAssetLayers.Placement(left, top, 2f), hair.id.raw)
		val layer = e.source!!.layers.single { it.id.raw == id }
		assertEquals(layers + 1, e.source!!.layers.size)
		assertEquals("Bow", layer.name)
		// Trimmed to its opaque pixels: 40x30 pixels over 80x60 canvas units, starting 8 units in.
		assertEquals(40, layer.raster.width); assertEquals(30, layer.raster.height)
		assertTrue(kotlin.math.abs(layer.bounds.left - (left + 8f)) <= 1f && kotlin.math.abs(layer.bounds.width - 80) <= 1, "placed: ${layer.bounds}")
		assertTrue(layer.order < e.source!!.layers.single { it.id == hair.id }.order, "above the hair layer (a lower order is higher in the stack)")
		val mesh = e.model.rig.puppet.drawables.single { e.model.rig.layerIdByDrawableId[it.id.raw] == id }
		val hairParent = e.model.rig.puppet.drawables.first { e.model.rig.layerIdByDrawableId[it.id.raw] == hair.id.raw }.parentDeformerId
		assertEquals(hairParent, mesh.parentDeformerId, "it hangs from the hair's parent, so it moves with it")
		assertEquals(meshes + 1, e.model.rig.puppet.drawables.size)
		val hairOrder = e.model.rig.puppet.drawables.filter { e.model.rig.layerIdByDrawableId[it.id.raw] == hair.id.raw }.maxOf { it.drawOrder }
		assertTrue(mesh.drawOrder >= hairOrder, "it draws over the layer under it, not at the back: ${mesh.drawOrder} vs $hairOrder")
		val puppet = e.model.rig.puppet
		fun partOf(id: org.umamo.runtime.model.DrawableId) = puppet.parts.firstOrNull { p -> p.children.any { it is org.umamo.runtime.model.OrgChild.Drawable && it.id == id } }?.id
		assertEquals(partOf(puppet.drawables.first { e.model.rig.layerIdByDrawableId[it.id.raw] == hair.id.raw }.id), partOf(mesh.id), "it joins the part of the layer under it")
		// It draws: the middle of the block is red at rest.
		val rest = RigPreview(e.model).let { p -> p.render(p.parameters.associate { it.id to it.default }, left + 48f, top + 38f, 1f, 1f, 1, 1).argb[0] }
		// Clearly red (the part it joins may tint it, as it tints that part's own art).
		val (r, g, b) = Triple(rest shr 16 and 255, rest shr 8 and 255, rest and 255)
		assertTrue(r > 150 && r > g + 80 && r > b + 80, "the bow draws on top: ${rest.toUInt().toString(16)}")
		// It survives a full re-rig from the document.
		val rerig = PSD2LivePipeline().buildPreview(e.source!!, e.model.config)
		assertTrue(rerig.rig.puppet.drawables.any { rerig.rig.layerIdByDrawableId[it.id.raw] == id }, "a re-rig keeps it")
		e.undo()
		assertEquals(layers, e.source!!.layers.size, "undo takes it out")
	}
}
