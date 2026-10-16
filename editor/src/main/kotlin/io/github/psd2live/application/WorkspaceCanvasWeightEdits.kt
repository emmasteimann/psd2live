package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*
import org.umamo.edit.MeshTopology
import org.umamo.runtime.model.*

/** Resolve canvas requests against each candidate, then persist materialized replay records. */
object WorkspaceCanvasWeightEdits {
    val supported = setOf("canvas_glue_edit", "vertex_group_paint")

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument =
        WorkspaceDocumentEdits.journal(document, model, commands(model.rig.puppet, operation))

    fun commands(model: PuppetModel, operation: WorkspaceDocumentOperation): JsonArray {
        validateOperationSchema(operation.request, WorkspaceCanvasWeightSchemas.request(operation.operation))
        return when (operation.operation) {
            "canvas_glue_edit" -> JsonArray(listOf(glue(model, operation.request)))
            "vertex_group_paint" -> weights(model, operation.request.getValue("edit").jsonObject)
            else -> error("Unsupported canvas weight operation: ${operation.operation}")
        }
    }

    private fun mesh(model: PuppetModel, raw: String): DrawableId {
        val id = DrawableId(raw.removePrefix("mesh:"))
        require(model.drawables.any { it.id == id && it.mesh != null }) { "Mesh not found: $raw" }
        return id
    }

    private fun pose(model: PuppetModel, request: JsonObject): Map<String, Float> {
        val result = request["pose"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.float }
        val parameters = model.parameters.associateBy { it.id.raw }
        require(result.all { (id, value) -> parameters[id]?.let { value.isFinite() && value in it.min..it.max } == true }) {
            "Pose contains an unknown or out-of-range parameter"
        }
        return result
    }

    private fun glue(model: PuppetModel, request: JsonObject): JsonObject = WorkspaceGlueEdit.command(model, request)

    private fun weights(model: PuppetModel, request: JsonObject): JsonArray = WorkspaceVertexGroupPaint.commands(model, request)
}
