package io.github.psd2live.application

import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerSizeBudget
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.StableIds
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.WorkspaceAddLayerRequest
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceLayerInsertion
import io.github.psd2live.project.WorkspacePngAsset
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind

// An imported image as a layer of the document: the asset workflow's document edits, shared by every front end
// (the desktop's asset store and registrations stay on the desktop).

fun WorkspaceDocument.addLayer(
	asset: WorkspacePngAsset,
	request: WorkspaceAddLayerRequest,
    checkCancelled: () -> Unit = {},
): Pair<WorkspaceDocument, String> {
	// An unnamed layer is named from what it is made of, so the same request adds the same layer ID.
	val rawId = request.layerId?.trim().orEmpty().ifEmpty {
		StableIds.fresh(StableIds.stem("agent:", request.copy(layerId = null, expectedState = "", taskId = null))) { id -> source.layers.any { it.id.raw == id } }
	}
	require(rawId.none(Char::isISOControl)) { "Layer ID contains control characters" }
	require(source.layers.none { it.id.raw == rawId }) { "Layer ID already exists: $rawId" }
	val name = request.name.trim()
	require(name.isNotEmpty()) { "Layer name must not be blank" }
	require(request.opacity.isFinite() && request.opacity in 0f..1f) { "Layer opacity must be within 0..1" }
	val tag = enumValue<SemanticTag>(request.semanticTag, "semantic_tag")
	val side = enumValue<Side>(request.side, "side")
	val normalized = normalizeAssetRaster(asset, request.trimTransparent, checkCancelled)
	val added = WorkspaceSourceLayer(
		id = LayerId(rawId),
		name = name,
		groupPath = request.groupPath.trim('/'),
		kind = SourceLayerKind.Raster,
		visible = request.visible,
		order = 0,
		bounds = normalized.bounds,
		opacity = request.opacity,
		clipped = false,
		blend = LayerBlend.Normal,
		channelMask = ChannelMask.ALL,
		raster = normalized.raster,
		sourceAssetId = asset.public.id,
		sourceSpatialReferenceId = asset.public.placement.sourceViewId,
		derived = true,
		rect = normalized.rect,
	)
	val painterOrder = source.layers.toMutableList()
	val insertionIndex = when (val insertion = request.insertion) {
		WorkspaceLayerInsertion.Top -> painterOrder.size
		WorkspaceLayerInsertion.Bottom -> 0
		is WorkspaceLayerInsertion.Above -> painterOrder.anchorIndex(insertion.layerId) + 1
		is WorkspaceLayerInsertion.Below -> painterOrder.anchorIndex(insertion.layerId)
	}
	painterOrder.add(insertionIndex, added)
	val ordered = painterOrder.mapIndexed { index, layer ->
		WorkspaceSourceLayer.copyOf(layer, order = painterOrder.lastIndex - index)
	}
	val nextSource = WorkspaceSourceArt(source.widthPx, source.heightPx, ordered, source.groups.toList())
	val override = LayerClassificationOverride(
		type = LayerType.PRESET,
		tag = tag,
		side = side,
	)
	val nextParents = if (request.parentDeformerId == null) parentOverrides else parentOverrides + (rawId to request.parentDeformerId)
	return copy(
		source = nextSource,
		layerVisibility = layerVisibility + (rawId to request.visible),
		deletedLayerIds = deletedLayerIds - rawId,
		layerOverrides = layerOverrides + (rawId to override),
		parentOverrides = nextParents,
	) to rawId
}

private fun MutableList<SourceLayer>.anchorIndex(layerId: String): Int {
	indexOfFirst { it.id.raw == layerId }.takeIf { it >= 0 }?.let { return it }
	val baseId = layerId.removeSuffix(":l").removeSuffix(":r")
	return indexOfFirst { it.id.raw == baseId }.takeIf { it >= 0 }
		?: throw IllegalArgumentException("Insertion anchor layer not found: $layerId")
}

fun WorkspaceDocument.replacePlacedLayer(layerId: String, asset: WorkspacePngAsset, checkCancelled: () -> Unit = {}): WorkspaceDocument {
    val normalized = normalizeAssetRaster(asset, true, checkCancelled)
    val next = source.layers.map { layer ->
        if (layer.id.raw != layerId) layer else (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(
            bounds = normalized.bounds, raster = normalized.raster, rect = normalized.rect)
    }
    return copy(source = WorkspaceSourceArt(source.widthPx, source.heightPx, next, source.groups))
}

private data class NormalizedRaster(val bounds: LayerBounds, val raster: LayerRaster, val rect: LayerCanvasRect?)

/**
 * The asset's processed pixels at their own resolution, on the canvas rectangle its placement names.
 *
 * Nothing is resampled: the layer raster is the asset's pixels (cropped to their visible area when
 * [trimTransparent]), and the placement's canvas units per pixel become the layer's canvas rectangle, so a
 * 1024-pixel asset placed over 32 canvas units is a 32-unit layer holding 1024 pixels ([LayerSpace]).
 */
private fun normalizeAssetRaster(asset: WorkspacePngAsset, trimTransparent: Boolean, checkCancelled: () -> Unit): NormalizedRaster {
    checkCancelled()
	val rect = asset.public.placement.canvasRect
    require(listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() }) { "Asset placement is outside the canvas coordinate range" }
	val width = asset.public.pixelWidth; val height = asset.public.pixelHeight
	require(width > 0 && height > 0 && asset.rgba.size == width * height * 4) { "Invalid RGBA buffer length" }
	val crop = (if (trimTransparent) alphaBounds(width, height, asset.rgba, checkCancelled) else intArrayOf(0, 0, width, height))
		?: throw IllegalArgumentException("Generated PNG is fully transparent")
	val (cropLeft, cropTop, cropRight, cropBottom) = crop.toList()
	val unitsX = rect.width / width; val unitsY = rect.height / height
	val canvas = LayerCanvasRect(rect.left + cropLeft * unitsX, rect.top + cropTop * unitsY,
		(cropRight - cropLeft) * unitsX, (cropBottom - cropTop) * unitsY)
	LayerSizeBudget.require(cropRight - cropLeft, cropBottom - cropTop, canvas)
	val (bounds, stored) = LayerSizeBudget.enclosing(canvas)
	val rgba = if (cropLeft == 0 && cropTop == 0 && cropRight == width && cropBottom == height) asset.rgba.copyOf() else {
		val rowBytes = (cropRight - cropLeft) * 4
		ByteArray(rowBytes * (cropBottom - cropTop)).also { out ->
			for (y in cropTop until cropBottom) {
				if ((y - cropTop) % 256 == 0) checkCancelled()
				asset.rgba.copyInto(out, (y - cropTop) * rowBytes, (y * width + cropLeft) * 4, (y * width + cropLeft) * 4 + rowBytes)
			}
		}
	}
	return NormalizedRaster(bounds, LayerRaster(cropRight - cropLeft, cropBottom - cropTop, rgba), stored)
}

/** The edges (left, top, right, bottom) of the pixels with non-zero alpha, or null when there are none. */
private fun alphaBounds(width: Int, height: Int, rgba: ByteArray, checkCancelled: () -> Unit): IntArray? {
	var minX = width
	var minY = height
	var maxX = -1
	var maxY = -1
	for (y in 0 until height) {
        checkCancelled()
		for (x in 0 until width) {
			if (rgba[(y * width + x) * 4 + 3].toInt() == 0) continue
			minX = minOf(minX, x)
			minY = minOf(minY, y)
			maxX = maxOf(maxX, x)
			maxY = maxOf(maxY, y)
		}
	}
	return if (maxX < minX || maxY < minY) null else intArrayOf(minX, minY, maxX + 1, maxY + 1)
}

private inline fun <reified T : Enum<T>> enumValue(raw: String, field: String): T =
	runCatching { enumValueOf<T>(raw.trim().uppercase()) }
		.getOrElse { throw IllegalArgumentException("Unknown $field: $raw") }

