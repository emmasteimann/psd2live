package io.github.psd2live.application
import io.github.psd2live.project.Files
import io.github.psd2live.project.isAbsolute
import io.github.psd2live.project.pathOf

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.transform

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*

fun sourceArtwork(arguments: JsonObject): Pair<WorkspaceSourceArt, Map<String, LayerClassificationOverride>> {
    val width = arguments.getValue("width").jsonPrimitive.int; val height = arguments.getValue("height").jsonPrimitive.int
    require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) { "Canvas exceeds 16 megapixels" }
    val files = arguments.getValue("layers").jsonArray
    require(files.size in 1..32)
    val overrides = mutableMapOf<String, LayerClassificationOverride>()
    var pixels = 0L
    val layers = files.mapIndexed { index, entry ->
        val a = entry.jsonObject
        val file = pathOf(a.text("path"))
        require(file.isAbsolute && Files.isRegularFile(file) && Files.size(file) in 8..67_108_864) { "Invalid local image path or file budget" }
        val image = LayerImport.decodeRasterFile(file.toFile())
        pixels += image.width.toLong() * image.height
        require(pixels <= 33_554_432) { "Artwork exceeds total raster budget" }
        val rgba = image.rgba
        // Made from the file entry and its place, so the same request names the same layers.
        val id = StableIds.stem("artwork:", index, a)
        a["role"]?.jsonPrimitive?.content?.let { role -> overrides[id] = LayerClassificationOverride(type = LayerType.PRESET,
            tag = enumValueOf<SemanticTag>(role.uppercase()), side = enumValueOf<Side>((a["side"]?.jsonPrimitive?.content ?: "none").uppercase())) }
        WorkspaceSourceLayer(LayerId(id), a.text("name"), "", SourceLayerKind.Raster, true, files.lastIndex - index,
            LayerBounds(a["x"]?.jsonPrimitive?.int ?: 0, a["y"]?.jsonPrimitive?.int ?: 0, image.width, image.height),
            1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(image.width, image.height, rgba), null, null, false)
    }
    return WorkspaceSourceArt(width, height, layers, emptyList()) to overrides
}
