package io.github.psd2live.application
import io.github.psd2live.core.base64Encode
import io.github.psd2live.core.StableIds
import io.github.psd2live.core.DeformPathTools
import io.github.psd2live.core.RigGeometryTools
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.hypot
import kotlin.math.max

/**
 * Deform Path inspection, point binding and command construction - the plain part of [WorkspacePathEdits], shared with
 * the iPad. The desktop's displacement preview renderer (Java2D) stays in WorkspacePathEdits.
 */
object WorkspacePathCommands {
    fun parsePoints(rawPoints: JsonArray, vertices: FloatArray, indices: IntArray): List<DeformPathPoint> {
        require(rawPoints.size in 2..128) { "Paths require 2..128 points" }
        return rawPoints.map { elem ->
            when (elem) {
                is JsonObject -> {
                    if ("wa" in elem) {
                        DeformPathPoint(
                            a = elem.getValue("a").jsonPrimitive.int,
                            b = elem.getValue("b").jsonPrimitive.int,
                            c = elem.getValue("c").jsonPrimitive.int,
                            wa = elem.getValue("wa").jsonPrimitive.float,
                            wb = elem.getValue("wb").jsonPrimitive.float,
                            wc = elem.getValue("wc").jsonPrimitive.float,
                            corner = elem["corner"]?.jsonPrimitive?.boolean ?: false,
                        )
                    } else {
                        val x = elem.getValue("x").jsonPrimitive.float
                        val y = elem.getValue("y").jsonPrimitive.float
                        val corner = elem["corner"]?.jsonPrimitive?.boolean ?: false
                        DeformPathTools.bind(vertices, indices, x, y, corner)
                    }
                }
                is JsonArray -> {
                    require(elem.size >= 2) { "Point coordinate must have at least [x, y]" }
                    val x = elem[0].jsonPrimitive.float
                    val y = elem[1].jsonPrimitive.float
                    val corner = elem.getOrNull(2)?.jsonPrimitive?.boolean ?: false
                    DeformPathTools.bind(vertices, indices, x, y, corner)
                }
                else -> error("Point must be [x, y] or {x, y}")
            }
        }
    }

    fun parseMovedPoints(rawPoints: JsonArray): List<Pair<Float, Float>> {
        return rawPoints.map { elem ->
            when (elem) {
                is JsonArray -> {
                    require(elem.size >= 2) { "Moved point must have [x, y]" }
                    elem[0].jsonPrimitive.float to elem[1].jsonPrimitive.float
                }
                is JsonObject -> {
                    elem.getValue("x").jsonPrimitive.float to elem.getValue("y").jsonPrimitive.float
                }
                else -> error("Moved point must be [x, y] or {x, y}")
            }
        }
    }

    fun inspect(model: PuppetModel, arguments: JsonObject): JsonObject {
        val targetArg = arguments["target"]?.jsonPrimitive?.contentOrNull
        val pathIdArg = (arguments["path_id"] ?: arguments["id"])?.jsonPrimitive?.contentOrNull
        val meshId = targetArg?.removePrefix("mesh:")

        val filtered = model.deformPaths.filter { path ->
            (meshId == null || path.drawableId.raw == meshId) &&
            (pathIdArg == null || path.id == pathIdArg)
        }

        return buildJsonObject {
            putJsonArray("paths") {
                filtered.forEach { path ->
                    val drawable = model.drawables.firstOrNull { it.id == path.drawableId }
                    val mesh = drawable?.mesh
                    add(buildJsonObject {
                        put("id", path.id)
                        put("target", "mesh:${path.drawableId.raw}")
                        put("level", path.editLevel)
                        put("width", path.width)
                        put("hardness", path.hardness)
                        put("closed", path.closed)
                        put("pointCount", path.points.size)
                        if (mesh != null) {
                            val positions = DeformPathTools.positions(path, mesh.positions)
                            putJsonArray("points") {
                                path.points.forEachIndexed { i, p ->
                                    val (px, py) = positions[i]
                                    add(buildJsonObject {
                                        put("x", px)
                                        put("y", py)
                                        put("corner", p.corner)
                                        put("a", p.a)
                                        put("b", p.b)
                                        put("c", p.c)
                                        put("wa", p.wa)
                                        put("wb", p.wb)
                                        put("wc", p.wc)
                                    })
                                }
                            }
                            val bounds = RigGeometryTools.bounds(mesh.positions)
                            putJsonArray("meshBounds") {
                                bounds.forEach { add(JsonPrimitive(it)) }
                            }
                        }
                    })
                }
            }
        }
    }

    fun createPutCommand(model: PuppetModel, arguments: JsonObject): Pair<String, JsonObject> {
        val target = arguments.getValue("target").jsonPrimitive.content
        require(target.startsWith("mesh:")) { "Target must be mesh:<id>" }
        val meshId = target.removePrefix("mesh:")
        val drawable = model.drawables.singleOrNull { it.id.raw == meshId } ?: error("Mesh not found: $meshId")
        val mesh = requireNotNull(drawable.mesh) { "Drawable has no mesh" }

        val id = (arguments["id"] ?: arguments["path_id"])?.jsonPrimitive?.contentOrNull
            ?: StableIds.of("path_", arguments) { id -> model.deformPaths.any { it.id == id } }
        val rawPoints = arguments.getValue("points").jsonArray
        val boundPoints = parsePoints(rawPoints, mesh.positions, mesh.indices)

        val width = arguments["width"]?.jsonPrimitive?.floatOrNull ?: DeformPath.DEFAULT_WIDTH
        val hardness = arguments["hardness"]?.jsonPrimitive?.floatOrNull ?: DeformPath.DEFAULT_HARDNESS
        val closed = arguments["closed"]?.jsonPrimitive?.booleanOrNull ?: false
        val level = arguments["level"]?.jsonPrimitive?.intOrNull ?: 2

        val command = buildJsonObject {
            put("op", "path_put"); put("path_units", "cubism")
            put("id", id)
            put("target", target)
            put("width", width)
            put("hardness", hardness)
            put("closed", closed)
            put("level", level)
            putJsonArray("points") {
                boundPoints.forEach { p ->
                    add(buildJsonObject {
                        put("a", p.a)
                        put("b", p.b)
                        put("c", p.c)
                        put("wa", p.wa)
                        put("wb", p.wb)
                        put("wc", p.wc)
                        put("corner", p.corner)
                    })
                }
            }
        }
        return id to command
    }

    fun createDeleteCommand(arguments: JsonObject): Pair<String, JsonObject> {
        val pathId = (arguments["path_id"] ?: arguments["id"])?.jsonPrimitive?.content ?: error("Missing path_id")
        val command = buildJsonObject {
            put("op", "path_delete")
            put("id", pathId)
        }
        return pathId to command
    }

    fun createDeformCommand(arguments: JsonObject): JsonObject {
        val target = arguments.getValue("target").jsonPrimitive.content
        require(target.startsWith("mesh:")) { "Target must be mesh:<id>" }
        val pathId = (arguments["path_id"] ?: arguments["id"])?.jsonPrimitive?.content ?: error("Missing path_id")
        val key = arguments.getValue("key").jsonObject
        val movedPoints = arguments.getValue("moved_points").jsonArray

        return buildJsonObject {
            put("op", "path_deform")
            put("target", target)
            put("path_id", pathId)
            put("key", key)
            put("moved_points", movedPoints)
        }
    }
}
