package io.github.psd2live.application
import io.github.psd2live.core.concurrentMapOf
import io.github.psd2live.core.MessageDigest
import io.github.psd2live.core.base64Decode
import io.github.psd2live.core.putIfAbsent

import io.github.psd2live.core.StableIds
import io.github.psd2live.project.WorkspaceAddLayerRequest
import io.github.psd2live.project.WorkspaceCanvasPlacement
import io.github.psd2live.project.WorkspaceImportedPngAsset
import io.github.psd2live.project.WorkspaceLayerInsertion
import io.github.psd2live.project.WorkspacePngAsset
import io.github.psd2live.project.WorkspacePngImportRequest
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.WorkspaceViewSpatialMetadata
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.placementForGeneratedPng

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.LayerSizeBudget
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO

class WorkspacePngAssetStore(private val checkCancelled: () -> Unit = {}) {
	private val assets = concurrentMapOf<String, WorkspacePngAsset>()

	fun import(request: WorkspacePngImportRequest, spatial: WorkspaceViewSpatialMetadata): WorkspaceImportedPngAsset {
		require(request.png.size >= PNG_SIGNATURE.size && request.png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
			"asset import accepts PNG data only"
		}
		checkCancelled()
        val decoded = javax.imageio.stream.MemoryCacheImageInputStream(request.png.inputStream()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "The supplied bytes are not a decodable PNG" }
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216) { "PNG exceeds 16 megapixels" }
                checkCancelled()
                reader.read(0)
            } finally { reader.dispose() }
        }
        checkCancelled()
        // A local copy: the request is the rigger module's, so its property cannot be smart-cast here.
        val solidBackground = request.solidBackground
        val matte = if (request.referenceId != null && solidBackground != null) processGeneratedMatte(decoded,
            solidBackground, request.backgroundTolerance, request.processing, checkCancelled) else null
        val image = matte?.image ?: request.solidBackground?.let { cleanGeneratedMatte(decoded, it, request.backgroundTolerance, checkCancelled) } ?: decoded
        if (request.requireTransparency) {
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            require(pixels.any { it ushr 24 == 0 } && pixels.any { it ushr 24 > 0 }) {
                "Asset needs transparent background and visible pixels. Supply native alpha or an explicit solid_background for matte removal; a checkerboard is not transparency."
            }
        }
        require(image.width > 0 && image.height > 0) { "PNG dimensions must be positive" }
		val placement = if (request.referenceId != null) WorkspaceCanvasPlacement(spatial.coordinateSpace, spatial.viewRect, image.width, image.height,
            spatial.viewRect.width / image.width, spatial.viewRect.height / image.height, request.spatialReferenceId) else spatial.placementForGeneratedPng(
			sourceViewId = request.spatialReferenceId,
			imagePixelWidth = image.width,
			imagePixelHeight = image.height,
			sourcePixelRect = request.sourcePixelRect,
		)
		val rgba = image.toRgba(checkCancelled)
		val digest = sha256(if (request.solidBackground == null) request.png else java.io.ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
		val placementKey = listOf(
			placement.canvasRect.left,
			placement.canvasRect.top,
			placement.canvasRect.right,
			placement.canvasRect.bottom,
		).joinToString(":")
		val details = if (request.referenceId == null) kotlinx.serialization.json.JsonObject(emptyMap()) else kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive(2)); put("reference_id", kotlinx.serialization.json.JsonPrimitive(request.referenceId))
            put("solid_background", kotlinx.serialization.json.JsonPrimitive(request.solidBackground)); put("background_tolerance", kotlinx.serialization.json.JsonPrimitive(request.backgroundTolerance))
            put("registration_required", kotlinx.serialization.json.JsonPrimitive(true)); put("processing", request.processing)
            put("diagnostics", matte?.diagnostics ?: kotlinx.serialization.json.buildJsonObject { put("mode", kotlinx.serialization.json.JsonPrimitive("native_alpha")) }); put("raw_sha256", kotlinx.serialization.json.JsonPrimitive(sha256(request.png)))
            var left=image.width; var top=image.height; var right=0; var bottom=0
            for (i in 0 until image.width*image.height) {
                if (i % 4096 == 0) checkCancelled()
                if ((rgba[i*4+3].toInt() and 255)>0) {
                left=minOf(left,i%image.width);top=minOf(top,i/image.width)
                right=maxOf(right,i%image.width+1);bottom=maxOf(bottom,i/image.width+1)
                }
            }
            if (right>left && bottom>top) put("content_pixel_rect", Bounds(left.toFloat(),top.toFloat(),right.toFloat(),bottom.toFloat()).json())
        }
        val id = "asset-${sha256("$digest|$placementKey|${placement.sourceViewId}|$details".encodeToByteArray()).take(24)}"
		val imported = WorkspaceImportedPngAsset(id, digest, image.width, image.height, placement, details)
		checkCancelled()
		assets.putIfAbsent(id, WorkspacePngAsset(imported, rgba, request.png.copyOf()))
		return assets.getValue(id).public
	}

	fun require(assetId: String): WorkspacePngAsset =
		assets[assetId] ?: throw IllegalArgumentException("PNG asset not found: $assetId")

	fun find(assetId: String): WorkspacePngAsset? = assets[assetId]
    fun clear() { assets.clear() }

	fun remember(asset: WorkspacePngAsset): WorkspacePngAsset = asset.also { assets.putIfAbsent(it.public.id, it) }

	private companion object {
		val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
	}
}

private fun BufferedImage.toRgba(checkCancelled: () -> Unit = {}): ByteArray {
	val rgba = ByteArray(width * height * 4)
	var offset = 0
	for (y in 0 until height) for (x in 0 until width) {
        if (x == 0) checkCancelled()
		val argb = getRGB(x, y)
		rgba[offset++] = (argb ushr 16).toByte()
		rgba[offset++] = (argb ushr 8).toByte()
		rgba[offset++] = argb.toByte()
		rgba[offset++] = (argb ushr 24).toByte()
	}
	return rgba
}

fun decodePngBase64(raw: String): ByteArray {
	val payload = raw.substringAfter("base64,", raw).filterNot(Char::isWhitespace)
	return runCatching { base64Decode(payload) }
		.getOrElse { throw IllegalArgumentException("png_base64 is not valid Base64") }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString("") { "%02x".format(it.toInt() and 0xff) }
