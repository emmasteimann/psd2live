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

    private fun glue(model: PuppetModel, request: JsonObject): JsonObject {
        val a = mesh(model, request.getValue("mesh_a").jsonPrimitive.content)
        val b = mesh(model, request.getValue("mesh_b").jsonPrimitive.content)
        require(a != b) { "Glue requires two different meshes" }
        fun hits(field: String, id: DrawableId): Set<Int> {
            val values = request[field]?.jsonArray.orEmpty().map { it.jsonPrimitive.int }
            val count = model.drawables.single { it.id == id }.mesh!!.vertexCount
            require(values.all { it in 0 until count }) { "$field contains an invalid vertex index" }
            return values.toSet()
        }
        val hitsA = hits("hits_a", a); val hitsB = hits("hits_b", b)
        val action = request.getValue("action").jsonPrimitive.content
        require(action == "remerge" || hitsA.isNotEmpty() || hitsB.isNotEmpty()) { "This glue action needs selected vertices" }
        val requested = request["id"]?.jsonPrimitive?.content
        require(requested == null || model.glues.none { it.id == requested &&
            !((it.meshA == a && it.meshB == b) || (it.meshA == b && it.meshB == a)) }) {
            "Glue ID belongs to a different mesh pair"
        }
        val id = requested ?: generateSequence(0) { it + 1 }.map { "Glue_${a.raw}_${b.raw}_$it" }
            .first { candidate -> model.glues.none { it.id == candidate } }
        return buildJsonObject {
            put("op", "canvas_glue_edit"); put("id", id); put("action", action)
            put("mesh_a", a.raw); put("mesh_b", b.raw)
            put("hits_a", JsonArray(hitsA.sorted().map(::JsonPrimitive))); put("hits_b", JsonArray(hitsB.sorted().map(::JsonPrimitive)))
            put("distance", request["distance"] ?: JsonPrimitive(40f))
            put("weight_mode", request["weight_mode"] ?: JsonPrimitive("balance"))
            put("delta", request["delta"] ?: JsonPrimitive(0.35f))
        }
    }

    private fun weights(model: PuppetModel, request: JsonObject): JsonArray = WorkspaceVertexGroupPaint.commands(model, request)
}
