package io.github.psd2live.application

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.umamo.edit.MeshTopology
import org.umamo.runtime.model.*

/**
 * A vertex-group paint edit (brush stroke, gradient or invert on the simulation weights of some meshes) as the
 * journal commands that record it: the `vertex_group_paint` operation's work, without its request schema check (the
 * workspace API's), so any front end can paint weights the desktop's way.
 */
object WorkspaceVertexGroupPaint {
    fun commands(model: PuppetModel, request: JsonObject): JsonArray {
        val targets = request.getValue("targets").jsonArray.map { mesh(model, it.jsonPrimitive.content) }
        require(targets.distinct().size == targets.size) { "Weight targets must be unique" }
        val kind = VertexGroupKind.parse(request.getValue("kind").jsonPrimitive.content)
        val name = request["name"]?.jsonPrimitive?.content
        val action = request.getValue("action").jsonPrimitive.content
        val pose = pose(model, request)
        val connected = request["connected_only"]?.jsonPrimitive?.boolean ?: false
        val surfaces = CanvasWeightAuthoring.surfaces(model, targets, pose, connected)
        val reaches = surfaces.map { FloatArray(it.points.size) }
        fun point(value: JsonElement): CanvasBrushPoint {
            val numbers = value.jsonArray.map { it.jsonPrimitive.float }
            require(numbers.size == 2 && numbers.all(Float::isFinite))
            return CanvasBrushPoint(numbers[0], numbers[1])
        }
        if (action == "brush") {
            val points = request.getValue("points").jsonArray.map(::point)
            val tip = CanvasBrushTip(request.getValue("radius").jsonPrimitive.float,
                request["hardness"]?.jsonPrimitive?.float ?: 0.5f,
                request["shape"]?.jsonPrimitive?.content?.uppercase()?.let(CanvasBrushShape::valueOf) ?: CanvasBrushShape.CIRCLE,
                request["angle"]?.jsonPrimitive?.float ?: 0f, request["aspect"]?.jsonPrimitive?.float ?: 1f,
                request["falloff"]?.jsonPrimitive?.content?.uppercase()?.let(CanvasBrushFalloff::valueOf) ?: CanvasBrushFalloff.SMOOTH)
            val segments = if (points.size == 1) listOf(points[0] to points[0]) else points.zipWithNext()
            for ((from, to) in segments) {
                canvasWeightCheckpoint()
                val current = canvasBrushWeights(surfaces, from, to, tip, connected)
                for (at in reaches.indices) for (i in reaches[at].indices) {
                    if (i % 512 == 0) canvasWeightCheckpoint()
                    reaches[at][i] = maxOf(reaches[at][i], current[at][i])
                }
            }
        } else if (action == "gradient") {
            val from = point(request.getValue("from")); val to = point(request.getValue("to"))
            for (at in surfaces.indices) CanvasWeightAuthoring.gradient(surfaces[at].points, from, to).copyInto(reaches[at])
        }
        val mode = request["mode"]?.jsonPrimitive?.content?.uppercase()?.let(CanvasWeightAuthoring.Mode::valueOf)
            ?: CanvasWeightAuthoring.Mode.ADD
        val strength = request["strength"]?.jsonPrimitive?.float ?: 1f
        return buildJsonArray {
            for ((at, id) in targets.withIndex()) {
                canvasWeightCheckpoint()
                val existing = CanvasWeightAuthoring.group(model, id, kind, name)
                if (action == "invert" && existing == null) continue
                require(existing == null || existing.kind == kind) { "Group name belongs to a different weight kind" }
                val base = existing?.weights ?: FloatArray(reaches[at].size)
                val weights = if (action == "invert") CanvasWeightAuthoring.invert(base) else CanvasWeightAuthoring.apply(base,
                    reaches[at], mode, strength, if (mode == CanvasWeightAuthoring.Mode.SMOOTH)
                        MeshTopology.buildVertexAdjacency(base.size, model.drawables.single { it.id == id }.mesh!!.indices) else null)
                // A dab that reaches nothing never invents an empty group, matching the canvas.
                if (existing == null && weights.none { it > 0f }) continue
                add(VertexGroupJournal.encode(VertexGroup(CanvasWeightAuthoring.name(model, id, kind, name), id, kind, weights)))
            }
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
}
