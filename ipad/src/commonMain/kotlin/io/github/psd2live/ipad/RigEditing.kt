package io.github.psd2live.ipad

import io.github.psd2live.core.GeneratedParameterAdoption
import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.core.JournalRecording
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.RigParameterEdit
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.SwingAuthoring
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.RasterImage
import io.github.psd2live.ui.parameterKeyMarks
import io.github.psd2live.project.transform
import io.github.psd2live.project.config
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel

/**
 * Editing a rig on the iPad the way the desktop editor does: every change is one of the editor's own edit commands
 * (`parameter_keys`, `structure`, `warp`, ...), recorded into the rig's authoring journal by the same shared code
 * (`JournalRecording`, `GeneratedParameterAdoption`) and replayed onto the rig, so re-rigs keep it and the Mac opens it.
 * Undo and redo; [finish] writes the rig's files. The screens for parameters, parts, draw order, structure... use it.
 * Not thread-safe: call from one worker; [frame] only reads immutable models and may run beside it.
 */
class RigEditing(private val start: RigPreviewModel, startSource: org.umamo.format.art.SourceArt? = null) {
	constructor(outcome: RigOutcome) : this(outcome.model, outcome.source)
	private val pipeline = PSD2LivePipeline()
	var model: RigPreviewModel = start
		private set
	/** The art the rig is built from (a moved part's placement lives on its layer, for painting); saved with the project. */
	var source: org.umamo.format.art.SourceArt? = startSource
		private set
	private val undos = ArrayDeque<Pair<RigPreviewModel, org.umamo.format.art.SourceArt?>>()
	private val redos = ArrayDeque<Pair<RigPreviewModel, org.umamo.format.art.SourceArt?>>()

	val puppet: PuppetModel get() = model.rig.puppet
	val canUndo get() = undos.isNotEmpty()
	val canRedo get() = redos.isNotEmpty()
	/** Changes since the screen opened (journal entries and parameter definitions). */
	val edits get() = model.config.rigEdits.authoringJournal.size - start.config.rigEdits.authoringJournal.size +
		model.config.rigEdits.parameterEdits.size - start.config.rigEdits.parameterEdits.size +
		(if (model.config.drawOrderOverrides != start.config.drawOrderOverrides) 1 else 0)

	private fun commit(config: PipelineConfig, nextSource: org.umamo.format.art.SourceArt? = source) {
		val next = pipeline.updateRigEdits(model, config)
		undos.addLast(model to source); if (undos.size > 60) undos.removeFirst()
		redos.clear(); model = next; source = nextSource
	}

	/** Editor commands recorded on the shown rig, replayed on the authored one (the desktop's `journal`). Throws a readable message when the engine refuses. */
	fun journal(edits: List<JsonObject>, nextSource: org.umamo.format.art.SourceArt? = source) {
		val overlay = model.config.rigEdits
		val journal = JournalRecording.record(model.rig.puppet, model.authored.rig.puppet, overlay, JsonArray(edits), model.primitiveSkins)
		// A deleted warp leaves the swings that moved it in the same step.
		commit(model.config.copy(rigEdits = SwingAuthoring.withoutTargets(overlay.copy(authoringJournal = overlay.authoringJournal + journal), SwingAuthoring.deletedDeformers(journal))), nextSource)
	}

	/**
	 * A parameter defined, changed or deleted, as the desktop's parameter dialog does it: a `structure` command (a
	 * generated parameter it changes is taken into the edits first), and motions trimmed to the new range.
	 */
	fun defineParameter(mode: String, id: String, edit: RigParameterEdit? = null) {
		val command = buildJsonObject {
			put("op", "structure")
			putJsonArray("edits") { add(buildJsonObject {
				put("action", mode); put("kind", "parameter"); put("id", id)
				edit?.let { put("name", it.name); put("min", it.min); put("max", it.max); put("default", it.default); put("parameter_kind", it.kind.name); put("repeat", it.repeat) }
			}) }
		}
		val overlay = model.config.rigEdits
		val journal = GeneratedParameterAdoption.adopted(model.rig.puppet, model.authored.rig.puppet, listOf(command))
		val clips = if (mode == "delete") MotionClips.withoutParameter(overlay.motionClips, id)
			else if (edit != null) MotionClips.withParameterRange(overlay.motionClips, id, edit.min, edit.max) else overlay.motionClips
		commit(model.config.copy(rigEdits = overlay.copy(authoringJournal = overlay.authoringJournal + journal, motionClips = clips)))
	}

	/**
	 * A document edit (the desktop's WorkspaceDocumentEdits families shared so far: depth split, mesh/polygon split)
	 * as one undoable step: the shared edit runs on the project document, and the rig is built again from the result,
	 * exactly as opening that project would build it.
	 */
	fun applyDocument(operation: String, request: JsonObject, work: io.github.psd2live.application.WorkspaceRasterWork = io.github.psd2live.application.WorkspaceRasterWork.Direct,
		simulationWork: io.github.psd2live.application.WorkspaceSimulationWork = io.github.psd2live.application.WorkspaceSimulationWork.Direct,
		report: (JsonObject) -> Unit = {}) {
		val art = requireNotNull(source) { "This rig has no source art to edit" }
		val document = documentOf(art, model.config)
		val op = io.github.psd2live.application.WorkspaceDocumentOperation(operation, request)
		val next = when (operation) {
			in io.github.psd2live.application.WorkspaceDepthSplitEdits.supported -> io.github.psd2live.application.WorkspaceDepthSplitEdits.apply(op, document, model, work)
			in io.github.psd2live.application.WorkspacePartitionEdits.supported -> io.github.psd2live.application.WorkspacePartitionEdits.apply(op, document, model, work)
			in io.github.psd2live.application.WorkspaceSimulationEdits.supported -> io.github.psd2live.application.WorkspaceSimulationEdits.apply(op, document, model, simulationWork).also { report(it.report) }.document
			else -> throw IllegalArgumentException("$operation isn't on the iPad yet")
		}
		val config = next.config(PipelineConfig(exportCmo3 = false))
		val rebuilt = pipeline.buildPreview(next.source, config)
		undos.addLast(model to source); if (undos.size > 60) undos.removeFirst()
		redos.clear(); model = rebuilt; source = next.source
	}

	/** A settings change (draw order, visibility...) as one undoable step. */
	fun reconfigure(config: PipelineConfig) = commit(config)

	/**
	 * A change the quick replay can't build (the skeleton generates bones, deformers and parameters) as one undoable
	 * step: the rig built again from the art with [config], as LayerloomJob.setUp and opening a project do.
	 */
	fun rebuild(config: PipelineConfig) {
		val art = requireNotNull(source) { "This rig has no source art to rebuild from" }
		val next = pipeline.buildPreview(art, config)
		undos.addLast(model to source); if (undos.size > 60) undos.removeFirst()
		redos.clear(); model = next
	}

	fun undo() { if (!canUndo) return; redos.addLast(model to source); undos.removeLast().let { model = it.first; source = it.second } }
	fun redo() { if (!canRedo) return; undos.addLast(model to source); redos.removeLast().let { model = it.first; source = it.second } }

	private var compiledFor: Any? = null
	private var renderer: io.github.psd2live.format.compile.FrameSession? = null

	/** The canvas region ([left], [top], [width], [height]) at [pose], [outWidth] x [outHeight] pixels. */
	fun frame(pose: Map<String, Float>, left: Float, top: Float, width: Float, height: Float, outWidth: Int, outHeight: Int): RasterImage =
		io.github.psd2live.core.synchronized(this) {
			val m = model
			if (compiledFor !== m) { renderer?.close(); renderer = IrFrameRenderer.open(RigIrCompiler.compile(m), false); compiledFor = m }
			renderer!!.render(pose, 0f, FrameSpec(left, top, width, height, outWidth, outHeight), null)
		}

	/** The edited rig's runtime files written over [outcome]'s folder, as the outcome the Rig tab shows. Blocking. */
	fun finish(outcome: RigOutcome, progress: (String, Double) -> Unit): RigOutcome =
		writeRig(outcome.name, source ?: outcome.source, model.config, model, RigFiles.nowSeconds(), progress)
}

/** Parameters and their keys on the iPad: the desktop's Parameters panel, its keys editor and its parameter dialog. */
object RigParameters {
	class Info(val id: String, val name: String, val min: Float, val max: Float, val default: Float, val blendShape: Boolean, val repeat: Boolean,
		val keys: List<Float>, val created: Boolean, val generated: Boolean)

	fun list(e: RigEditing): List<Info> {
		val marks = e.puppet.parameterKeyMarks()
		val generated = GeneratedParameterAdoption.generated(e.model.rig.puppet, e.model.authored.rig.puppet)
		// Made by hand: not in the rig the generator built.
		val base = e.model.baseRig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
		return e.puppet.parameters.map { p ->
			val m = marks[p.id]
			val keys = if (p.kind == ParameterKind.BLEND_SHAPE) m?.blendKeys.orEmpty() else m?.gridKeys.orEmpty()
			Info(p.id.raw, p.name, p.min, p.max, p.default, p.kind == ParameterKind.BLEND_SHAPE, p.repeat, keys,
				p.id.raw !in base,
				p.id.raw in generated)
		}
	}

	/** Key points of [id]: "add" values, "move" one from [from] to a value, "delete" one (the desktop's keys editor). */
	fun keys(e: RigEditing, id: String, action: String, values: List<Float>, from: Float? = null) = e.journal(listOf(buildJsonObject {
		put("op", "parameter_keys"); put("parameter", id); put("action", action)
		put("values", JsonArray(values.map(::JsonPrimitive))); from?.let { put("from", it) }
	}))

	/** Name, range, default, kind and repeat of an existing parameter. */
	fun update(e: RigEditing, info: Info, name: String, min: Float, max: Float, default: Float, blendShape: Boolean, repeat: Boolean) {
		require(min < max) { "The lowest value has to be below the highest." }
		require(info.keys.all { it in min..max }) { "Keys at ${info.keys.filter { it !in min..max }.joinToString()} are outside that range: move or delete them first." }
		e.defineParameter("update", info.id, RigParameterEdit(info.id, name.trim().ifEmpty { info.name }, min, max, default.coerceIn(min, max),
			if (blendShape) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL, repeat, created = info.created))
	}

	/** A new parameter; returns its id. */
	fun create(e: RigEditing, name: String, min: Float, max: Float, default: Float, blendShape: Boolean): String {
		require(min < max) { "The lowest value has to be below the highest." }
		val taken = e.puppet.parameters.mapTo(HashSet()) { it.id.raw }
		val stem = "Param" + name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercase) }.ifEmpty { "Custom" }
		val id = generateSequence(1) { it + 1 }.map { if (it == 1) stem else "$stem$it" }.first { it !in taken }.take(64)
		e.defineParameter("create", id, RigParameterEdit(id, name.trim().ifEmpty { id }, min, max, default.coerceIn(min, max),
			if (blendShape) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL, false, created = true))
		return id
	}

	/** Deletes a parameter you made or took in (a swing's or simulation's own goes with it instead). */
	fun delete(e: RigEditing, info: Info) {
		require(!info.generated) { "${info.name} belongs to a swing or simulation: remove that instead." }
		e.defineParameter("delete", info.id)
	}
}

/**
 * Parts on the iPad: the desktop's Select mode (`layer_transform`). A part moves, scales or turns as a whole; its
 * pixels and texture stay, its meshes move at rest (one `canvas_geometry` per mesh, recorded like any mesh edit) and
 * its layer keeps the placement for painting. Any part, under any deformer.
 */
object RigParts {
	class Part(val layerId: String, val name: String, val meshes: List<String>)

	fun list(e: RigEditing): List<Part> {
		val byLayer = e.model.rig.layerIdByDrawableId
		val meshes = e.puppet.drawables.filter { it.mesh != null }.groupBy { byLayer[it.id.raw] }
		val src = e.source ?: return emptyList()
		return src.layers.mapNotNull { l -> meshes[l.id.raw]?.let { ms -> Part(l.id.raw, l.name, ms.map { it.id.raw }) } }.asReversed()
	}

	/** The part whose mesh is at canvas point ([x], [y]) at rest, front first. */
	fun at(e: RigEditing, x: Float, y: Float): Part? {
		val mesh = io.github.psd2live.core.PoseTweaks.meshAt(e.puppet, emptyMap(), x, y) ?: return null
		val layer = e.model.rig.layerIdByDrawableId[mesh] ?: return null
		return list(e).firstOrNull { it.layerId == layer }
	}

	/** The part's box at rest, canvas pixels (x0, y0, x1, y1). */
	fun bounds(e: RigEditing, part: Part): FloatArray? {
		var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
		for ((id, pts) in io.github.psd2live.core.PoseTweaks.vertices(e.puppet, emptyMap())) if (id in part.meshes)
			for (i in 0 until pts.size / 2) { x0 = minOf(x0, pts[i * 2]); x1 = maxOf(x1, pts[i * 2]); y0 = minOf(y0, pts[i * 2 + 1]); y1 = maxOf(y1, pts[i * 2 + 1]) }
		return if (x1 < x0) null else floatArrayOf(x0, y0, x1, y1)
	}

	/** Scale, then turn ([degrees], clockwise on screen) about ([px], [py]), then move by ([dx], [dy]) - the desktop's `transform`. */
	fun transformOf(dx: Float, dy: Float, scale: Float, degrees: Float, px: Float, py: Float): io.github.psd2live.project.LayerTransform {
		val r = degrees * kotlin.math.PI / 180.0
		val cos = kotlin.math.cos(r).toFloat(); val sin = kotlin.math.sin(r).toFloat()
		val a = cos * scale; val b = sin * scale; val c = -sin * scale; val d = cos * scale
		return io.github.psd2live.project.LayerTransform(a + 0f, b + 0f, c + 0f, d + 0f, px - (a * px + c * py) + dx + 0f, py - (b * px + d * py) + dy + 0f)
	}

	fun transform(e: RigEditing, part: Part, delta: io.github.psd2live.project.LayerTransform) {
		if (delta.isIdentity) return
		val puppet = e.puppet
		val edits = part.meshes.map { id -> moved(puppet, id, delta) }
		val src = e.source
		val next = src?.let { s ->
			io.github.psd2live.project.WorkspaceSourceArt(s.widthPx, s.heightPx, s.layers.map { l ->
				if (l.id.raw != part.layerId) l else (io.github.psd2live.project.WorkspaceSourceLayer.copyOf(l, l.order) as io.github.psd2live.project.WorkspaceSourceLayer)
					.copy(layerTransform = delta.after(l.transform).takeUnless { it.isIdentity })
			}, s.groups)
		}
		e.journal(edits, next)
	}

	/** The Select-mode edit that takes mesh [id]'s rest geometry through [delta] on the canvas (the desktop's `moved`). */
	private fun moved(puppet: PuppetModel, id: String, delta: io.github.psd2live.project.LayerTransform): JsonObject {
		val geometry = io.github.psd2live.core.RigGeometryTools.geometry(puppet, "mesh", id, emptyMap())
		val mapping = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(puppet, emptyMap(), org.umamo.runtime.model.DrawableId(id))) { "Mesh $id has no parent space" }
		// World space is canvas with y up.
		val world = mapping.localToWorld(geometry.points)
		val target = FloatArray(world.size)
		for (i in 0 until world.size / 2) {
			val x = world[i * 2]; val y = -world[i * 2 + 1]
			target[i * 2] = delta.x(x, y); target[i * 2 + 1] = -delta.y(x, y)
		}
		val local = mapping.worldToLocalLinearized(target, geometry.points, geometry.points, (0 until world.size / 2).toSet())
		val reached = mapping.localToWorld(local)
		require(local.all(Float::isFinite) && reached.indices.all { kotlin.math.abs(reached[it] - target[it]) < 0.05f }) { "That part can't reach there under what holds it" }
		val key = io.github.psd2live.core.canvasDeformationCoordinate(puppet, geometry.axes, emptyMap(), emptyList())
		return buildJsonObject {
			put("op", "canvas_geometry"); put("kind", "mesh"); put("id", id)
			put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
			put("preserve_image", false)
			put("points", JsonArray(local.map(::JsonPrimitive)))
		}
	}
}

/**
 * Draw order on the iPad: the desktop's draw-order ruler (`layer_draw_order`). A part given an order (0..1000, higher
 * in front) keeps it over any animated order until reset; reset gives back the generated or authored order.
 */
object RigDrawOrder {
	class Entry(val layerId: String, val name: String, val order: Float, val overridden: Boolean)

	/** Every part, front first, by its order at rest (ties: the later-drawn in front, as the renderer does). */
	fun list(e: RigEditing): List<Entry> {
		val overrides = e.model.config.drawOrderOverrides
		val byLayer = e.model.rig.layerIdByDrawableId
		val names = e.source?.layers?.associate { it.id.raw to it.name }.orEmpty()
		val seen = LinkedHashMap<String, Pair<Float, Int>>()
		e.puppet.drawables.forEachIndexed { index, d ->
			val layer = byLayer[d.id.raw] ?: d.id.raw
			val order = overrides[layer] ?: overrides[d.id.raw] ?: d.drawOrder
			val prev = seen[layer]
			if (prev == null || order > prev.first || (order == prev.first && index > prev.second)) seen[layer] = order to index
		}
		// Only your parts: meshes the rigger made itself (a mouth's lip strokes) follow their part.
		return seen.entries.filter { it.key in names }.sortedWith(compareByDescending<Map.Entry<String, Pair<Float, Int>>> { it.value.first }.thenByDescending { it.value.second })
			.map { (layer, v) -> Entry(layer, names.getValue(layer), v.first, layer in overrides) }
	}

	/** Sets [layerId]'s order (0..1000), or resets it when [order] is null - one undoable step. */
	fun set(e: RigEditing, layerId: String, order: Float?) {
		val before = e.model.config.drawOrderOverrides
		val after = if (order == null) before - layerId else {
			require(order.isFinite() && order in 0f..1000f) { "Draw order is 0 to 1000." }
			before + (layerId to order)
		}
		if (after != before) e.reconfigure(e.model.config.copy(drawOrderOverrides = after))
	}

	/** Puts [layerId] just in front of ([inFront] true) or just behind the part next to it in [list]. */
	fun step(e: RigEditing, list: List<Entry>, layerId: String, inFront: Boolean) {
		val i = list.indexOfFirst { it.layerId == layerId }
		val j = if (inFront) i - 1 else i + 1
		if (i < 0 || j !in list.indices) return
		// Halfway between the neighbour and the part beyond it, so it never ties with either.
		val near = list[j].order
		val beyond = list.getOrNull(if (inFront) j - 1 else j + 1)?.order ?: (if (inFront) near + 2f else near - 2f)
		set(e, layerId, ((near + beyond) / 2f).coerceIn(0f, 1000f))
	}
}

/**
 * New deformers on the iPad: the desktop's Edit mode create tools (`canvas_create_warp` with its bezier divisions,
 * `canvas_create_rotation`), placed in the parent's own units as the desktop places them. The chosen meshes move
 * under the new deformer and keep their pose.
 */
object RigStructure {
	private fun localPoints(puppet: PuppetModel, id: String) = io.github.psd2live.core.RigGeometryTools.geometry(puppet, "mesh", id, emptyMap()).points

	/** Why [meshes] can't share a new parent, or null when they can. */
	fun problem(e: RigEditing, meshes: List<String>): String? {
		if (meshes.isEmpty()) return "Pick a part first."
		val parents = e.puppet.drawables.filter { it.id.raw in meshes }.map { it.parentDeformerId }.distinct()
		return if (parents.size > 1) "These parts hang from different deformers: pick parts that move together." else null
	}

	/** A warp (a grid of [rows] x [cols]) around [meshes], a little bigger than them. Returns its id. */
	fun addWarp(e: RigEditing, meshes: List<String>, name: String, rows: Int = 5, cols: Int = 5, bezierRows: Int = 2, bezierCols: Int = 2): String {
		problem(e, meshes)?.let { throw IllegalArgumentException(it) }
		val puppet = e.puppet
		var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
		for (id in meshes) { val p = localPoints(puppet, id); for (i in 0 until p.size / 2) { x0 = minOf(x0, p[i * 2]); x1 = maxOf(x1, p[i * 2]); y0 = minOf(y0, p[i * 2 + 1]); y1 = maxOf(y1, p[i * 2 + 1]) } }
		val mx = (x1 - x0) * 0.06f; val my = (y1 - y0) * 0.06f
		val id = io.github.psd2live.core.StableIds.fresh(io.github.psd2live.core.StableIds.stem("Warp_", meshes, name, rows, cols)) { t -> puppet.deformers.any { it.id.raw == t } }
		val cmd = buildJsonObject {
			put("op", "canvas_create_warp"); put("id", id); put("name", name.trim().ifEmpty { "Warp" })
			put("rows", rows); put("columns", cols)
			put("bounds", buildJsonObject { put("x", x0 - mx); put("y", y0 - my); put("w", (x1 - x0) + 2 * mx); put("h", (y1 - y0) + 2 * my) })
			put("add_to", "parent_of_selected"); put("meshes", JsonArray(meshes.map(::JsonPrimitive)))
		}
		val controls = io.github.psd2live.core.RigBezierJournal.prepare(io.github.psd2live.core.CanvasEdits.apply(puppet, cmd), e.model.config.rigEdits, "divisions",
			buildJsonObject { put("target", "warp:$id"); put("rows", bezierRows); put("columns", bezierCols) })
		e.journal(listOf(cmd, controls))
		return id
	}

	/** A rotation deformer pivoting at canvas point ([px], [py]) above [meshes], its handle pointing up. Returns its id. */
	fun addRotation(e: RigEditing, meshes: List<String>, name: String, px: Float, py: Float): String {
		problem(e, meshes)?.let { throw IllegalArgumentException(it) }
		val puppet = e.puppet
		val first = meshes.first()
		val mapping = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(puppet, emptyMap(), org.umamo.runtime.model.DrawableId(first))) { "That part has no parent space" }
		val pts = localPoints(puppet, first)
		var sx = 0f; var sy = 0f; for (i in 0 until pts.size / 2) { sx += pts[i * 2]; sy += pts[i * 2 + 1] }
		val seed = floatArrayOf(sx / (pts.size / 2), sy / (pts.size / 2))
		// Canvas is y down, world y up: the handle points up on screen, a tenth of the canvas long.
		val len = maxOf(puppet.canvasWidth, puppet.canvasHeight) * 0.1f
		val o = mapping.worldToLocal(floatArrayOf(px, -py), seed, setOf(0))
		val t = mapping.worldToLocal(floatArrayOf(px, -(py - len)), seed, setOf(0))
		require(o.all(Float::isFinite) && t.all(Float::isFinite)) { "The pivot can't be placed there" }
		val angle = (kotlin.math.atan2((t[1] - o[1]).toDouble(), (t[0] - o[0]).toDouble()) * 180.0 / kotlin.math.PI).toFloat()
		val id = io.github.psd2live.core.StableIds.fresh(io.github.psd2live.core.StableIds.stem("Rotation_", meshes, name, px, py)) { s -> puppet.deformers.any { it.id.raw == s } }
		e.journal(listOf(buildJsonObject {
			put("op", "canvas_create_rotation"); put("preservePose", true); put("id", id); put("name", name.trim().ifEmpty { "Rotation" })
			put("origin", JsonArray(listOf(o[0], o[1]).map(::JsonPrimitive))); put("angle", angle)
			put("handle_length", kotlin.math.hypot(t[0] - o[0], t[1] - o[1]).coerceAtLeast(1e-4f))
			put("add_to", "parent_of_selected"); put("meshes", JsonArray(meshes.map(::JsonPrimitive)))
		}))
		return id
	}

	/** The deformer each mesh hangs from, by name (for showing where a part sits). */
	fun parentName(e: RigEditing, mesh: String): String? {
		val p = e.puppet.drawables.firstOrNull { it.id.raw == mesh }?.parentDeformerId ?: return null
		return e.puppet.deformers.firstOrNull { it.id == p }?.name
	}
}

/** Splitting parts on the iPad: the desktop's mesh-island split, polygon split and depth split. */
object RigSplits {
	/** How many separate islands [layerId]'s art has (0 when it can't be split that way), with each island's middle. */
	fun islands(e: RigEditing, layerId: String): List<Pair<Float, Float>> =
		io.github.psd2live.application.WorkspacePartitionEdits.componentPlan(e.model, layerId)?.components?.map { it.centerX to it.centerY }.orEmpty()

	/** Names for the islands: left/right or top/bottom for two, numbered otherwise (the desktop dialog's defaults). */
	fun names(base: String, centers: List<Pair<Float, Float>>): List<String> = if (centers.size == 2) {
		val horizontal = kotlin.math.abs(centers[0].first - centers[1].first) >= kotlin.math.abs(centers[0].second - centers[1].second)
		val firstIsLow = if (horizontal) centers[0].first <= centers[1].first else centers[0].second <= centers[1].second
		val (a, b) = if (horizontal) "left" to "right" else "top" to "bottom"
		if (firstIsLow) listOf("$base $a", "$base $b") else listOf("$base $b", "$base $a")
	} else centers.indices.map { "$base ${it + 1}" }

	fun splitIslands(e: RigEditing, layerId: String, names: List<String>) = e.applyDocument("source_split_components", buildJsonObject {
		put("layer_id", layerId); put("names", JsonArray(names.map(::JsonPrimitive)))
	})

	/** Cuts [layerId] with a canvas polygon into the part inside ([inside]) and the rest ([outside]). */
	fun splitPolygon(e: RigEditing, layerId: String, polygon: List<Pair<Float, Float>>, inside: String, outside: String) = e.applyDocument("source_split_polygon", buildJsonObject {
		put("layer_id", layerId); put("names", JsonArray(listOf(inside, outside).map(::JsonPrimitive)))
		put("polygon", JsonArray(polygon.map { (x, y) -> JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))) }))
	})

	/** [sourceMesh] in two slices, front and back, with [middleMesh] drawn between them (a scarf round a neck). */
	fun splitDepth(e: RigEditing, sourceMesh: String, middleMesh: String) = e.applyDocument("source_split_depth", buildJsonObject {
		put("source_id", sourceMesh); put("middle_ids", JsonArray(listOf(JsonPrimitive(middleMesh))))
	})
}

/**
 * The skeleton on the iPad: the desktop's Skeleton mode (`skeleton_*` through the shared WorkspaceSkeletonMotionEdits):
 * propose one from the art (arms only or the full body), move joints, bind parts to bones, remove a limb, switch it off,
 * and saved poses. Bones become parameters the pose sliders, Tweak and motions use.
 */
object RigSkeleton {
	fun spec(e: RigEditing): io.github.psd2live.core.SkeletonSpec? = e.model.config.rigEdits.skeleton

	private fun edit(e: RigEditing, request: JsonObject) {
		val m = e.model
		val next = io.github.psd2live.application.WorkspaceSkeletonMotionEdits.skeleton(m.config.rigEdits.skeleton, request) {
			io.github.psd2live.core.LayerSkeleton.propose(m.analysis, m.baseRig, request["proposal"]?.let { (it as JsonPrimitive).content } ?: "full", m.config.armJoints)
		}
		io.github.psd2live.core.SkeletonDraftEdits.validated(next, m.rig.puppet.drawables.mapTo(HashSet()) { it.id.raw })
		// The skeleton generates bones, deformers and parameters: only a full build makes them (the quick replay doesn't).
		e.rebuild(m.config.copy(rigEdits = m.config.rigEdits.copy(skeleton = next)))
	}

	/** A skeleton proposed from the art: "arms" (arms only) or "full" (arms, legs, tail, wings). */
	fun auto(e: RigEditing, proposal: String) = edit(e, buildJsonObject { put("mode", "auto"); put("proposal", proposal) })
	fun enable(e: RigEditing, on: Boolean) = edit(e, buildJsonObject { put("mode", "enable"); put("enabled", on) })
	/** Moves a bone's [end] ("head" or "tail") to canvas ([x], [y]); connected joints follow, as on the desktop. */
	fun move(e: RigEditing, boneId: String, end: String, x: Float, y: Float) = edit(e, buildJsonObject {
		put("mode", "move"); put("bone_id", boneId); put("end", end); put("point", JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))))
	})
	/** Binds part mesh [drawableId] to [boneId] (null: back to automatic). */
	fun bind(e: RigEditing, drawableId: String, boneId: String?) = edit(e, buildJsonObject {
		put("mode", "bind"); put("drawable_id", drawableId); boneId?.let { put("bone_id", it) }
	})
	fun remove(e: RigEditing, boneId: String) = edit(e, buildJsonObject { put("mode", "remove"); put("bone_id", boneId) })
	/** Bone settings: name, joint limits (degrees). */
	fun bone(e: RigEditing, boneId: String, name: String? = null, minAngle: Float? = null, maxAngle: Float? = null) = edit(e, buildJsonObject {
		put("mode", "bone"); put("bone", buildJsonObject {
			put("id", boneId); name?.let { put("name", it) }; minAngle?.let { put("minAngle", it) }; maxAngle?.let { put("maxAngle", it) }
		})
	})

	/** Saves the current pose of the skeleton's parameters as [name] (replacing one of that name). */
	fun savePose(e: RigEditing, name: String, pose: Map<String, Float>) {
		val s = requireNotNull(spec(e)) { "Make a skeleton first." }
		val ids = s.bones.filter { !it.role.anchor }.map { it.parameterId }.toSet()
		val values = pose.filterKeys { it in ids }
		e.reconfigure(e.model.config.copy(rigEdits = e.model.config.rigEdits.copy(skeleton = s.copy(savedPoses = s.savedPoses + (name.trim() to values)))))
	}
	fun deletePose(e: RigEditing, name: String) {
		val s = spec(e) ?: return
		e.reconfigure(e.model.config.copy(rigEdits = e.model.config.rigEdits.copy(skeleton = s.copy(savedPoses = s.savedPoses - name))))
	}
}

/**
 * Simulation on the iPad: the desktop's Simulate mode (shared WorkspaceSimulationEdits): cloth and hair simulated as
 * soft bodies and baked into the rig's own deformers, from presets (front hair, back hair, clothing - only where it
 * hangs loose), or the classic sway back; re-bake, clear a bake, remove one. Each is one undoable step.
 */
object RigSimulation {
	class Sim(val id: String, val name: String, val kind: String, val targets: Int, val baked: Boolean, val fit: Float?, val errorPx: Float?)

	fun list(e: RigEditing): List<Sim> = e.model.config.rigEdits.simEdits.map { s ->
		Sim(s.id, s.name, s.kind.name.lowercase(), s.targets.size, s.bake != null, s.bake?.fit, s.bake?.maxErrorPx)
	}

	/** Runs a simulation document edit; [progress] hears 0..1 while it bakes. Returns the edit's report (warnings...). */
	fun run(e: RigEditing, operation: String, request: JsonObject, progress: (Float) -> Unit = {}): JsonObject {
		var report = JsonObject(emptyMap())
		val work = object : io.github.psd2live.application.WorkspaceSimulationWork {
			override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = action(progress) { false }
		}
		e.applyDocument(operation, request, simulationWork = work) { report = it }
		return report
	}

	/** "front_hair", "back_hair", "clothing"; "classic_front_hair" / "classic_back_hair" put the generated sway back; "remove_clothing". */
	fun preset(e: RigEditing, name: String, progress: (Float) -> Unit = {}) = run(e, "model_apply_preset", buildJsonObject { put("preset", name) }, progress)
	fun bake(e: RigEditing, id: String, progress: (Float) -> Unit = {}) = run(e, "simulation_bake", buildJsonObject { put("id", id) }, progress)
	fun clearBake(e: RigEditing, id: String) = run(e, "simulation_clear_bake", buildJsonObject { put("id", id) })
	fun delete(e: RigEditing, id: String) = run(e, "simulation_delete", buildJsonObject { put("id", id) })
}

/**
 * Textures on the iPad: the desktop's atlas view and texture settings (`atlas_set_budget`, `layer_set_pixel_density`):
 * the atlas pages VTube Studio loads, how big they are and how many, and per part how much texture detail it gets.
 * Stored as the desktop stores them (the atlas setting, textureOverrides), so the Mac opens them; the rig is rebuilt.
 */
object RigTextures {
	class Part(val layerId: String, val name: String, val page: Int, val x: Int, val y: Int, val w: Int, val h: Int, val density: Float?)

	fun budget(e: RigEditing): io.github.psd2live.core.AtlasBudget = e.model.config.effectiveAtlasBudget()
	fun pages(e: RigEditing) = e.model.atlas.pages
	fun fit(e: RigEditing) = e.model.atlas.fit
	fun notices(e: RigEditing) = e.model.atlas.notices

	fun parts(e: RigEditing): List<Part> {
		val names = e.source?.layers?.associate { it.id.raw to it.name }.orEmpty()
		val overrides = e.model.config.textureOverrides
		return e.model.atlas.placementByLayerId.mapNotNull { (id, p) -> names[id]?.let { Part(id, it, p.page, p.x, p.y, p.width, p.height, overrides[id]?.density) } }
			.sortedWith(compareBy({ it.page }, { it.name }))
	}

	/** Page size (512..8192), how many pages at most, padding between tiles. */
	fun setBudget(e: RigEditing, pageSize: Int, maxPages: Int, padding: Int) {
		val b = io.github.psd2live.core.AtlasBudget(pageSize, maxPages, padding)
		e.rebuild(e.model.config.copy(atlasBudget = b, atlasSize = pageSize, texturePadding = padding))
	}

	/** How much detail part [layerId] gets: 1 = as drawn, 2 = twice, 0.5 = half; null = automatic. */
	fun setDensity(e: RigEditing, layerId: String, density: Float?) {
		val current = e.model.config.textureOverrides[layerId] ?: io.github.psd2live.project.TextureOverride()
		val next = current.copy(density = density)
		val map = if (next.isDefault) e.model.config.textureOverrides - layerId else e.model.config.textureOverrides + (layerId to next)
		e.rebuild(e.model.config.copy(textureOverrides = map))
	}
}
