package io.github.psd2live.application

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/**
 * A glue edit between two meshes (brush welds along the seam, remerge, unglue, or paint which side follows which) as the
 * journal command that records it: the `canvas_glue_edit` operation's work without the workspace API's schema check, so
 * any front end can glue the desktop's way.
 */
object WorkspaceGlueEdit {
    fun command(model: PuppetModel, request: JsonObject): JsonObject {
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

    private fun mesh(model: PuppetModel, raw: String): DrawableId {
        val id = DrawableId(raw.removePrefix("mesh:"))
        require(model.drawables.any { it.id == id && it.mesh != null }) { "Mesh not found: $raw" }
        return id
    }
}
