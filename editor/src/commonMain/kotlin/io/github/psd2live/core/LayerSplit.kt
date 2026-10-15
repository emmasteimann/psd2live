package io.github.psd2live.core

import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.LayerId
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer

/**
 * Cutting the masked part of a layer out into a layer of its own. The mask is soft: the new layer takes each pixel's
 * alpha times the mask and the original keeps the rest, so the two composite back to exactly the original pixels -
 * a cut never eats into the drawing along its edge, and nothing is invented under it.
 */
object LayerSplit {
	/**
	 * The split's new source, frozen generation input and rig edits. [droppedEdits] counts hand tweaks that were on the
	 * split layer's meshes: both pieces get new meshes, which those edits no longer fit, so they are left out.
	 */
	class Result(val source: WorkspaceSourceArt, val generationSource: SourceArt?, val pieceId: String,
		val rigEdits: RigEditOverlay, val droppedEdits: Int)

	/**
	 * [image] (the layer's pixels in [space]) split by [mask] (same grid; its alpha is how much of each pixel goes to the
	 * new layer). The new layer, [name] with id [pieceId], sits directly above [layerId]. [generationSource] (the frozen
	 * generation input, if the rig has one) gets the same split, so both pieces generate from their own pixels.
	 */
	fun split(model: RigPreviewModel, layerId: String, space: PaintSpace, image: RigImage, mask: RigImage,
		name: String, pieceId: String, generationSource: SourceArt?): Result {
		val w = image.width; val h = image.height
		require(mask.width == w && mask.height == h) { "The mask doesn't match the layer" }
		val pixels = image.readArgb(0, 0, w, h); val m = mask.readArgb(0, 0, w, h)
		val piece = IntArray(pixels.size); val rest = IntArray(pixels.size)
		var pieceCount = 0; var restCount = 0
		for (i in pixels.indices) {
			val p = pixels[i]; val a = p ushr 24
			val pa = (a * (m[i] ushr 24) + 127) / 255
			val ra = a - pa
			piece[i] = (pa shl 24) or (p and 0xFFFFFF); rest[i] = (ra shl 24) or (p and 0xFFFFFF)
			if (pa > 0) pieceCount++
			if (ra > 0) restCount++
		}
		require(pieceCount > 0) { "The mask doesn't cover any of this layer's pixels" }
		require(restCount > 0) { "The mask covers the whole layer - nothing would be left of it" }
		fun cropped(argb: IntArray) = space.crop(newRigImage(w, h).also { it.writeArgb(0, 0, w, h, argb) })
		val restLayer = cropped(rest); val pieceLayer = cropped(piece)
		val repainted = RasterPaintCommit.paintedSource(model, layerId,
			PaintPixels(restLayer.raster, restLayer.rect ?: LayerCanvasRect.of(restLayer.bounds))).source
		val original = repainted.layers.first { it.id.raw == layerId } as WorkspaceSourceLayer
		val newLayer = original.copy(id = LayerId(pieceId), name = name, bounds = pieceLayer.bounds, rect = pieceLayer.rect,
			raster = pieceLayer.raster, sourceAssetId = null, sourceSpatialReferenceId = null, derived = true)
		val meshes = model.rig.layerIdByDrawableId.filterValues { it == layerId || it.startsWith("$layerId:") }.keys
		val overlay = model.config.rigEdits
		val kept = overlay.authoringJournal.filterNot { entry ->
			val kind = (entry["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content
			val id = (entry["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
			kind == "mesh" && id in meshes
		}
		return Result(insertAbove(repainted, original, newLayer), generationSource?.let { gs ->
			// The frozen input: the original as what is left, the piece beside it.
			val restInGs = gs.layers.firstOrNull { it.id.raw == layerId }?.let { (it as? WorkspaceSourceLayer ?: WorkspaceSourceLayer.copyOf(it, it.order) as WorkspaceSourceLayer)
				.copy(bounds = original.bounds, rect = original.rect, raster = original.raster) } ?: return@let gs
			insertAbove(WorkspaceSourceArt(gs.widthPx, gs.heightPx, gs.layers.map { if (it.id.raw == layerId) restInGs else it }, gs.groups), restInGs, newLayer)
		}, pieceId, overlay.copy(authoringJournal = kept, splitDrawableIds = pinned(model, overlay)), overlay.authoringJournal.size - kept.size)
	}

	/**
	 * Every one-mesh layer's mesh pinned to the ID it has now, as the desktop does before an edit that changes what the
	 * generators read: generated IDs follow classification and sibling order, so a new layer would otherwise take an
	 * existing mesh's ID and the tweaks on it would land on the wrong mesh. The new layer gets an ID of its own.
	 */
	private fun pinned(model: RigPreviewModel, overlay: RigEditOverlay): Map<String, String> {
		val pins = overlay.splitDrawableIds
		val owned = model.rig.layerIdByDrawableId.entries.groupBy({ it.value }, { it.key })
		return pins + owned.filter { (layer, meshes) -> layer !in pins && meshes.size == 1 }.mapValues { it.value.single() }
	}

	/** [art] with [layer] placed directly above [below]: in the list and in the draw order (lower order draws on top). */
	private fun insertAbove(art: SourceArt, below: SourceLayer, layer: WorkspaceSourceLayer): WorkspaceSourceArt {
		val renumbered = art.layers.map { l ->
			val ws = l as? WorkspaceSourceLayer ?: WorkspaceSourceLayer.copyOf(l, l.order) as WorkspaceSourceLayer
			if (l.order >= below.order) ws.copy(order = l.order + 1) else ws
		}
		val at = renumbered.indexOfFirst { it.id == below.id }
		val list = renumbered.toMutableList().apply { add(at, layer.copy(order = below.order)) }
		return WorkspaceSourceArt(art.widthPx, art.heightPx, list, art.groups)
	}
}
