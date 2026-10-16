package io.github.psd2live.ipad

import io.github.psd2live.application.WorkspaceLayerInsertionEdits
import io.github.psd2live.application.addLayer
import io.github.psd2live.core.Bounds
import io.github.psd2live.project.WorkspaceAddLayerRequest
import io.github.psd2live.project.WorkspaceCanvasPlacement
import io.github.psd2live.project.WorkspaceImportedPngAsset
import io.github.psd2live.project.WorkspaceLayerInsertion
import io.github.psd2live.project.WorkspacePngAsset

/**
 * An image placed as a new layer (the desktop's "add layer from asset"): its pixels at their own resolution over the
 * canvas rectangle it was placed on, put in the stack above a layer, and meshed under that layer's parent so it moves
 * with it - a ribbon added above the hair moves with the head. Through the desktop's document edits.
 */
object RigAssetLayers {
	/** Where an image goes: its top-left corner on the canvas and canvas units per image pixel. */
	data class Placement(val left: Float, val top: Float, val scale: Float)

	/**
	 * Adds [argb] ([width] x [height]) as layer [name] at [placement], above [aboveLayerId] (or on top), hanging from
	 * that layer's parent (or from nothing with [followNothing]). One undoable step; the new layer's id. Blocking.
	 */
	fun add(e: RigEditing, argb: IntArray, width: Int, height: Int, name: String, placement: Placement,
		aboveLayerId: String?, followNothing: Boolean = false): String {
		require(width > 0 && height > 0 && argb.size == width * height) { "The image is empty" }
		require(placement.scale.isFinite() && placement.scale > 0f) { "The image needs a size" }
		val rgba = ByteArray(width * height * 4)
		for (i in argb.indices) {
			val p = argb[i]
			rgba[i * 4] = (p ushr 16).toByte(); rgba[i * 4 + 1] = (p ushr 8).toByte(); rgba[i * 4 + 2] = p.toByte(); rgba[i * 4 + 3] = (p ushr 24).toByte()
		}
		val sha = io.github.psd2live.core.MessageDigest.getInstance("SHA-256").digest(rgba).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
		val rect = Bounds(placement.left, placement.top, placement.left + width * placement.scale, placement.top + height * placement.scale)
		val asset = WorkspacePngAsset(WorkspaceImportedPngAsset("ipad-image:${sha.take(16)}", sha, width, height,
			WorkspaceCanvasPlacement("canvas", rect, width, height, placement.scale, placement.scale, "ipad")), rgba)
		val parent = if (followNothing || aboveLayerId == null) null else e.model.rig.puppet.drawables
			.firstOrNull { e.model.rig.layerIdByDrawableId[it.id.raw] == aboveLayerId }?.parentDeformerId?.raw
		val request = WorkspaceAddLayerRequest(asset.public.id, "", name.ifBlank { "Image" }, null, "",
			aboveLayerId?.let { WorkspaceLayerInsertion.Above(it) } ?: WorkspaceLayerInsertion.Top, "unknown", "none", true, 1f, true, null, parent)
		val document = e.document()
		val (added, id) = document.addLayer(asset, request)
		// Like a file import: the layer is the user's, with its own mesh under its parent, which the generators never make.
		e.applyDocumentEdit(WorkspaceLayerInsertionEdits.materialize(added, e.model, setOf(id), parent) {})
		return id
	}
}
