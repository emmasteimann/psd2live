package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/** The public paint gesture: strokes from [arguments] painted on the layer (desktop raster), then the shared commit. */
fun WorkspaceRasterEdits.paint(document: WorkspaceDocument, model: RigPreviewModel, arguments: JsonObject,
          work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
    val (painting, touched) = document.paintLayerImage(arguments, work)
    val region = painting.space.layerArea().let { area -> touched?.let { if (area.isEmpty) it else area.union(it) } ?: area }
    return prepare(document, model, WorkspacePaintRaster.capture(arguments.getValue("layer_id").jsonPrimitive.content,
        painting.image, painting.space, region, arguments["rebuild_mesh"]?.jsonPrimitive?.boolean ?: false,
        checkpoint = work::checkpoint), work)
}
