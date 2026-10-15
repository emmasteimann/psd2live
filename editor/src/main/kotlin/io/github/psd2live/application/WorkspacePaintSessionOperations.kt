package io.github.psd2live.application

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import io.github.psd2live.project.WorkspaceWorkflowResult

/** Render/sample observe the isolated candidate, including its in-progress live stroke. */
fun WorkspacePaintSession.control(request: JsonObject, checkpoint: () -> Unit = {
    if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Paint session gesture cancelled")
}): WorkspaceWorkflowResult {
    val edit = request.getValue("edit").jsonObject
    validateOperationSchema(edit, WorkspacePaintSessionSchemas.edit)
    val expected = request.getValue("session_state").jsonPrimitive.content
    val action = edit.getValue("action").jsonPrimitive.content
    return atomic(expected) {
        when (action) {
            "gesture" -> gesture(edit.getValue("gesture").jsonObject, edit["name"]?.jsonPrimitive?.content
                ?: edit.getValue("gesture").jsonObject.getValue("mode").jsonPrimitive.content, expected, checkpoint)
            "undo" -> undo(expected)
            "redo" -> redo(expected)
            "jump" -> jump(edit.getValue("index").jsonPrimitive.int, expected)
            "abandon" -> abandonStroke(expected)
            "cancel" -> cancel(expected)
            "inspect", "sample", "render" -> require(sessionState == expected) { "Paint session state changed" }
        }
        var images = emptyList<ByteArray>()
        val metadata = buildJsonObject {
            put("kind", if (action in setOf("sample", "render")) action else "state"); put("session", snapshot())
            if (action == "sample") {
                val pixel = sample(edit.getValue("x").jsonPrimitive.int, edit.getValue("y").jsonPrimitive.int)
                put("rgba", JsonArray(listOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255, pixel ushr 24 and 255).map(::JsonPrimitive)))
            }
            if (action == "render") {
                val source = image(); val edge = edit["max_edge"]?.jsonPrimitive?.int ?: 512
                val scale = minOf(1.0, edge.toDouble() / maxOf(source.width, source.height))
                val output = BufferedImage(maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), BufferedImage.TYPE_INT_ARGB)
                output.createGraphics().also { graphics ->
                    try {
                        graphics.composite = java.awt.AlphaComposite.Src
                        graphics.drawImage(source, 0, 0, output.width, output.height, null)
                    } finally { graphics.dispose() }
                }
                val bytes = ByteArrayOutputStream().also { ImageIO.write(output, "png", it) }.toByteArray()
                put("width", output.width); put("height", output.height); images = listOf(bytes)
            }
        }
        WorkspaceWorkflowResult(metadata, images)
    }
}

fun registerPaintSessionOperations(registry: WorkspaceOperationRegistry, port: WorkspacePaintPort,
    statePort: WorkspaceStatePort, jobs: WorkspaceJobs) {
    val s = WorkspaceResultSchema
    val ids = mapOf("session_id" to s.handle(), "session_state" to s.handle())
    registry.register(WorkspaceOperationDefinition("paint_session_list",
        "List the current private paint sessions, including the GUI candidate and its stroke revision. Use a listed session_id to inspect or continue exactly that candidate.",
        s.obj(emptyMap()), WorkspaceOperationKind.QUERY, resultSchema = s.obj(mapOf("sessions" to s.array(WorkspacePaintSessionSchemas.snapshot))))) { _, _ ->
        WorkspaceOperationOutput(buildJsonObject { put("sessions", JsonArray(port.listPaintSessions())) })
    }
    registry.register(WorkspaceOperationDefinition("paint_session_begin",
        "Begin an isolated multi-stroke paint candidate from a committed layer, capturing its workspace state. Canvas X points right and Y down; gesture points, radii, widths and sample positions are canvas units. The candidate raster is the layer's own pixel grid (width x height pixels covering canvas_rect [left, top, width, height]), so strokes on a dense layer paint at its raster density. Edits, sampling and undo never alter project history until commit.",
        s.obj(mapOf("state" to s.handle(), "layer_id" to s.handle())), WorkspaceOperationKind.SESSION,
        resultSchema = WorkspacePaintSessionSchemas.result)) { request, _ ->
        WorkspaceOperationOutput(buildJsonObject { put("kind", "state"); put("session",
            port.beginPaintSession(request.getValue("state").jsonPrimitive.content, request.getValue("layer_id").jsonPrimitive.content).snapshot()) })
    }
    registry.register(WorkspaceOperationDefinition("paint_session_control",
        "Edit, undo, redo or jump within the private paint candidate; cancel discards the entire session, abandon discards only the active live stroke. Sample and render read candidate pixels. Pass the latest session_state to prevent concurrent stroke loss. Mutations reject a changed committed workspace; inspection and cancellation remain available.",
        s.obj(ids + ("edit" to WorkspacePaintSessionSchemas.edit)), WorkspaceOperationKind.SESSION,
        resultSchema = WorkspacePaintSessionSchemas.result)) { request, _ ->
        val result = port.controlPaintSession(request)
        WorkspaceOperationOutput(result.metadata, result.images)
    }
    registry.register(WorkspaceOperationDefinition("paint_session_commit",
        "Commit all candidate strokes once as one raster/mesh history node. Defaults to retaining mesh and authored bindings; rebuild_mesh migrates existing rig bindings. Both the captured workspace and session revision must match. A successful session cannot publish twice.",
        s.obj(mapOf("state" to s.handle()) + ids + mapOf("rebuild_mesh" to s.boolean(), "preserve_source_raster" to s.boolean()),
            setOf("state", "session_id", "session_state")), WorkspaceOperationKind.DOCUMENT,
        jobBacked = true, resultSchema = WorkspaceJobResultSchemas.snapshot("paint_session_commit"), jobResultSchema = WorkspacePaintSessionSchemas.commit)) { request, context ->
        startWorkspaceOperationJob(statePort, jobs, "paint_session_commit") {
            val completion = requireNotNull(currentCoroutineContext()[WorkspaceJobCompletion])
            withContext(WorkspacePaintJobExecution(completion)) {
                WorkspaceOperationOutput(port.commitPaintSession(request.getValue("state").jsonPrimitive.content,
                    request.getValue("session_id").jsonPrimitive.content, request.getValue("session_state").jsonPrimitive.content,
                    request["rebuild_mesh"]?.jsonPrimitive?.boolean ?: false, request["preserve_source_raster"]?.jsonPrimitive?.boolean ?: false,
                    context.author).compact())
            }
        }
    }
}
