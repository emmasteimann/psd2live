package io.github.psd2live.application

import kotlinx.serialization.json.*

object WorkspacePaintSessionSchemas {
    private val s = WorkspaceResultSchema
    private val color = s.array(s.integer(0, 255), 4, 4)
    private val common = mapOf("color" to color, "opacity" to s.number(0, 1))
    private fun branch(mode: String, fields: Map<String, JsonObject>, required: Set<String>) =
        s.obj(common + fields + ("mode" to s.constant(mode)), required + "mode")
    val gesture = s.union(listOf(
        branch("brush", mapOf("points" to s.array(s.vector(2), 1, 512), "radius" to s.number(0.5, 512),
            "hardness" to s.number(0, 1)), setOf("points")),
        branch("pencil", mapOf("points" to s.array(s.vector(2), 1, 512), "radius" to s.number(0.5, 512)), setOf("points")),
        branch("eraser", mapOf("points" to s.array(s.vector(2), 1, 512), "radius" to s.number(0.5, 512),
            "hardness" to s.number(0, 1)), setOf("points")),
        branch("bucket", mapOf("point" to s.vector(2), "tolerance" to s.integer(0, 255)), setOf("point")),
        branch("shape", mapOf("from" to s.vector(2), "to" to s.vector(2), "shape" to s.choices("line", "rectangle", "ellipse"),
            "stroke_width" to s.number(1, 512), "filled" to s.boolean()), setOf("from", "to", "shape")),
        s.obj(mapOf("mode" to s.constant("clear"))),
    ))
    private fun control(action: String, fields: Map<String, JsonObject> = emptyMap(), required: Set<String> = fields.keys) =
        s.obj(fields + ("action" to s.constant(action)), required + "action")
    val edit = s.union(listOf(control("gesture", mapOf("gesture" to gesture, "name" to s.handle()), setOf("gesture")),
        control("undo"), control("redo"), control("jump", mapOf("index" to s.integer(0))), control("abandon"),
        control("cancel"), control("inspect"), control("sample", mapOf("x" to s.integer(0), "y" to s.integer(0))),
        control("render", mapOf("max_edge" to s.integer(128, 2048)), emptySet())))
    val snapshot = s.obj(mapOf("session_id" to s.handle(), "session_state" to s.handle(), "project_id" to s.handle(),
        "workspace_state" to s.handle(), "layer_id" to s.handle(), "layer_name" to s.string(),
        "width" to s.integer(1), "height" to s.integer(1), "canvas_rect" to s.vector(4), "index" to s.integer(0), "dirty" to s.boolean(),
        "active_stroke" to s.boolean(), "closed" to s.boolean(),
        "strokes" to s.array(s.obj(mapOf("id" to s.handle(), "name" to s.string())), 1, Int.MAX_VALUE)))
    val result = s.union(listOf(s.obj(mapOf("kind" to s.constant("state"), "session" to snapshot)),
        s.obj(mapOf("kind" to s.constant("sample"), "session" to snapshot, "rgba" to color)),
        s.obj(mapOf("kind" to s.constant("render"), "session" to snapshot, "width" to s.integer(1),
            "height" to s.integer(1)))))
    val commit = s.obj(s.identity + mapOf("applied" to s.constant(false), "changed" to s.array(s.handle(), 1, Int.MAX_VALUE)), s.identity.keys)
}
