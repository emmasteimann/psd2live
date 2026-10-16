package io.github.psd2live.ipad

import io.github.psd2live.core.CanvasDeformStroke
import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PoseTweaks
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.RasterImage
import org.umamo.runtime.model.PuppetModel

/**
 * Hand-tweaking a rig at a pose on the iPad: a deform brush over whatever is under the pencil, each finished stroke a
 * keyform edit in the rig's own edits (so re-rigs keep it and the Mac opens it), with undo and redo. Not thread-safe:
 * the screen calls it from one worker; [frame] only reads immutable models and may run beside it.
 */
class RigTweaking(val outcome: RigOutcome) {
	private val pipeline = PSD2LivePipeline()
	var model: RigPreviewModel = outcome.model
		private set
	private val undos = ArrayDeque<RigPreviewModel>()
	private val redos = ArrayDeque<RigPreviewModel>()
	private var session: CanvasDeformStroke.Session? = null

	/** What is on screen: the stroke in progress, else the committed rig. */
	@kotlin.concurrent.Volatile var shown: PuppetModel = model.rig.puppet
		private set

	val parameters: List<PreviewParameter> get() = model.rig.puppet.parameters.map { PreviewParameter(it.id.raw, it.name, it.min, it.max, it.default) }

	/**
	 * A new parameter (for a custom sway, a prop toggle...) named [name] with range [min]..[max]: it shows among the
	 * pose sliders, tweaks at its values become its keys, and physics or an expression can drive it. Undoable.
	 * Returns its id.
	 */
	fun addParameter(name: String, min: Float, max: Float, default: Float): String {
		val taken = model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
		val stem = "Param" + name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercase) }.ifEmpty { "Custom" }
		val id = generateSequence(1) { it + 1 }.map { if (it == 1) stem else "$stem$it" }.first { it !in taken }
		val edit = io.github.psd2live.core.RigParameterEdit(id, name.trim().ifEmpty { id }, min, max, default.coerceIn(min, max), created = true)
		val overlay = model.config.rigEdits
		undos.addLast(model); if (undos.size > 60) undos.removeFirst()
		redos.clear()
		model = pipeline.updateRigEdits(model, model.config.copy(rigEdits = overlay.copy(parameterEdits = overlay.parameterEdits + edit)))
		shown = model.rig.puppet
		return id
	}
	val canvasWidth: Float get() = model.rig.puppet.canvasWidth
	val canvasHeight: Float get() = model.rig.puppet.canvasHeight
	val canUndo get() = undos.isNotEmpty() && session == null
	val canRedo get() = redos.isNotEmpty() && session == null
	/** Hand tweaks and new parameters since the screen opened. */
	val edits get() = model.config.rigEdits.authoringJournal.size - outcome.model.config.rigEdits.authoringJournal.size +
		model.config.rigEdits.parameterEdits.size - outcome.model.config.rigEdits.parameterEdits.size

	/** Warp [id]'s lattice as shown now (the stroke in progress included) at [pose]. */
	fun lattice(pose: Map<String, Float>, id: String): PoseTweaks.Lattice? = PoseTweaks.lattices(shown, pose).firstOrNull { it.id == id }

	/** Every mesh's vertices as shown now at [pose], canvas pixels, for picking points. */
	fun vertices(pose: Map<String, Float>): List<Pair<String, FloatArray>> = PoseTweaks.vertices(shown, pose)

	/** Starts moving [selection] (vertex indices by mesh) rigidly with the pencil from canvas ([x], [y]). */
	fun beginPoints(pose: Map<String, Float>, selection: Map<String, Set<Int>>, x: Float, y: Float): Boolean {
		if (selection.values.all { it.isEmpty() }) return false
		session = PoseTweaks.beginPoints(model.rig.puppet, pose, selection, x, y)
		return true
	}

	/** The mesh drawn on top at canvas ([x], [y]), and its look at [pose]. */
	fun meshAt(pose: Map<String, Float>, x: Float, y: Float): String? = PoseTweaks.meshAt(shown, pose, x, y)
	fun look(meshId: String, pose: Map<String, Float>): PoseTweaks.Look = PoseTweaks.look(shown, meshId, pose)
	fun meshName(meshId: String): String = shown.drawables.firstOrNull { it.id.raw == meshId }?.name ?: meshId

	/** Sets [meshId]'s look at [pose]: one undoable edit. */
	fun setLook(meshId: String, pose: Map<String, Float>, look: PoseTweaks.Look) {
		val config = PoseTweaks.withLook(model, meshId, pose, look) ?: return
		undos.addLast(model); if (undos.size > 60) undos.removeFirst()
		redos.clear()
		model = PoseTweaks.rebuilt(pipeline, model, config)
		shown = model.rig.puppet
	}

	/** The warp a Warp-mode press at ([x], [y]) would push, for the overlay. */
	fun warpAt(pose: Map<String, Float>, x: Float, y: Float): PoseTweaks.Lattice? = PoseTweaks.warpUnder(shown, pose, x, y)

	/**
	 * Starts a stroke at canvas ([x], [y]); false when nothing is under the brush. With [warp], the most local warp
	 * deformer under the press is pushed instead of the meshes: one push moves the whole region it shapes.
	 */
	fun begin(pose: Map<String, Float>, x: Float, y: Float, radius: Float, hardness: Float, strength: Float, smooth: Boolean,
		warp: Boolean = false): Boolean {
		val puppet = model.rig.puppet
		if (warp) {
			val lattice = PoseTweaks.warpUnder(puppet, pose, x, y) ?: return false
			session = PoseTweaks.beginWarp(puppet, pose, lattice.id, x, y, radius, hardness, strength,
				if (smooth) CanvasDeformStroke.Action.SMOOTH else CanvasDeformStroke.Action.BRUSH)
			return true
		}
		val meshes = PoseTweaks.meshesUnder(puppet, pose, x, y, radius)
		if (meshes.isEmpty()) return false
		session = PoseTweaks.begin(puppet, pose, meshes, x, y, radius, hardness, strength,
			if (smooth) CanvasDeformStroke.Action.SMOOTH else CanvasDeformStroke.Action.BRUSH)
		return true
	}

	fun step(x: Float, y: Float) { session?.let { shown = PoseTweaks.step(it, x, y) } }

	/**
	 * Seals the stroke as one undoable edit. With [mirror], the same stroke is also done as its mirror image (the other
	 * side, at the mirrored pose) - both in the one undo step.
	 */
	fun end(mirror: Boolean = false) {
		val s = session ?: return
		session = null
		val config = PoseTweaks.journaled(model, s)
		if (config == null) { shown = model.rig.puppet; return }
		val before = model
		var next = PoseTweaks.rebuilt(pipeline, model, config)
		if (mirror) PoseTweaks.mirrored(next.rig.puppet, s)?.let { other ->
			PoseTweaks.journaled(next, other)?.let { next = PoseTweaks.rebuilt(pipeline, next, it) }
		}
		undos.addLast(before); if (undos.size > 60) undos.removeFirst()
		redos.clear()
		model = next
		shown = model.rig.puppet
	}

	fun cancel() { session?.cancel(); session = null; shown = model.rig.puppet }

	// ---- Deform paths (the desktop's path tool: path_put / path_deform / path_delete through the shared journal) ----

	/** A path as shown: its id, its mesh, and its points as canvas positions (x, y, ...) at the pose. */
	class PathShown(val id: String, val meshId: String, val points: FloatArray)

	private fun journal(edits: List<kotlinx.serialization.json.JsonObject>) {
		val overlay = model.config.rigEdits
		val recorded = io.github.psd2live.core.JournalRecording.record(model.rig.puppet, model.authored.rig.puppet, overlay,
			kotlinx.serialization.json.JsonArray(edits), model.primitiveSkins)
		val config = model.config.copy(rigEdits = io.github.psd2live.core.SwingAuthoring.withoutTargets(
			overlay.copy(authoringJournal = overlay.authoringJournal + recorded), io.github.psd2live.core.SwingAuthoring.deletedDeformers(recorded)))
		val next = pipeline.updateRigEdits(model, config)
		undos.addLast(model); if (undos.size > 60) undos.removeFirst()
		redos.clear(); model = next; shown = model.rig.puppet
	}

	fun paths(pose: Map<String, Float>): List<PathShown> {
		val verts = PoseTweaks.vertices(shown, pose).toMap()
		return shown.deformPaths.mapNotNull { p ->
			val v = verts[p.drawableId.raw] ?: return@mapNotNull null
			val out = FloatArray(p.points.size * 2)
			p.points.forEachIndexed { i, pt -> val (x, y) = pt.position(v); out[i * 2] = x; out[i * 2 + 1] = y }
			PathShown(p.id, p.drawableId.raw, out)
		}
	}

	private fun mapping(meshId: String, pose: Map<String, Float>) = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(shown,
		pose.mapKeys { org.umamo.runtime.model.ParameterId(it.key) }, org.umamo.runtime.model.DrawableId(meshId))) { "That part has no parent space" }

	/** A path along mesh [meshId] through canvas points at rest (2..128). Returns its id. */
	fun addPath(meshId: String, canvasPoints: List<Pair<Float, Float>>): String {
		require(canvasPoints.size >= 2) { "A path needs at least two points." }
		val m = mapping(meshId, emptyMap())
		val rest = io.github.psd2live.core.RigGeometryTools.geometry(shown, "mesh", meshId, emptyMap()).points
		var sx = 0f; var sy = 0f; for (i in 0 until rest.size / 2) { sx += rest[i * 2]; sy += rest[i * 2 + 1] }
		val seed = floatArrayOf(sx / (rest.size / 2), sy / (rest.size / 2))
		val local = canvasPoints.map { (x, y) -> m.worldToLocal(floatArrayOf(x, -y), seed, setOf(0)).also { require(it.all(Float::isFinite)) { "A point is off the part" } } }
		val request = kotlinx.serialization.json.buildJsonObject {
			put("target", kotlinx.serialization.json.JsonPrimitive("mesh:$meshId"))
			put("points", kotlinx.serialization.json.JsonArray(local.map { kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(it[0]), kotlinx.serialization.json.JsonPrimitive(it[1]))) }))
		}
		val (id, command) = io.github.psd2live.application.WorkspacePathCommands.createPutCommand(shown, request)
		journal(listOf(command))
		return id
	}

	/** Moves point [index] of path [pathId] to canvas ([x], [y]) at [pose]: the part bends with it at that pose. */
	fun movePathPoint(pathId: String, index: Int, x: Float, y: Float, pose: Map<String, Float>) {
		val path = shown.deformPaths.first { it.id == pathId }
		val meshId = path.drawableId.raw
		val axes = io.github.psd2live.core.RigGeometryTools.geometry(shown, "mesh", meshId, pose).axes
		// The posed parameters are the "selected" inputs: a pose the mesh has no key for gets one (as Tweak's strokes do).
		val defaults = shown.parameters.associate { it.id.raw to it.default }
		val posed = pose.filter { (id, v) -> kotlin.math.abs(v - (defaults[id] ?: v)) > 1e-4f }.keys.toList()
		val key = io.github.psd2live.core.canvasDeformationCoordinate(shown, axes, pose, posed)
		require(key.isNotEmpty()) { "Pose her first (a slider away from rest): a path bends the part at a pose." }
		val base = io.github.psd2live.core.RigGeometryTools.geometry(shown, "mesh", meshId, key).base
		val current = path.points.map { it.position(base) }
		val target = mapping(meshId, pose).worldToLocal(floatArrayOf(x, -y), floatArrayOf(current[index].first, current[index].second), setOf(0))
		require(target.all(Float::isFinite)) { "That point can't go there" }
		val moved = current.mapIndexed { i, p -> if (i == index) target[0] to target[1] else p }
		val command = io.github.psd2live.application.WorkspacePathCommands.createDeformCommand(kotlinx.serialization.json.buildJsonObject {
			put("target", kotlinx.serialization.json.JsonPrimitive("mesh:$meshId")); put("path_id", kotlinx.serialization.json.JsonPrimitive(pathId))
			put("key", kotlinx.serialization.json.JsonObject(key.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
			put("moved_points", kotlinx.serialization.json.JsonArray(moved.map { kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(it.first), kotlinx.serialization.json.JsonPrimitive(it.second))) }))
		})
		journal(listOf(command))
	}

	fun deletePath(pathId: String) {
		journal(listOf(io.github.psd2live.application.WorkspacePathCommands.createDeleteCommand(kotlinx.serialization.json.buildJsonObject {
			put("path_id", kotlinx.serialization.json.JsonPrimitive(pathId)) }).second))
	}

	fun undo() { if (!canUndo) return; redos.addLast(model); model = undos.removeLast(); shown = model.rig.puppet }
	fun redo() { if (!canRedo) return; undos.addLast(model); model = redos.removeLast(); shown = model.rig.puppet }

	private var compiledFor: Any? = null
	private var renderer: io.github.psd2live.format.compile.FrameSession? = null

	/** The canvas region ([left], [top], [width], [height]) at [pose], [outWidth] x [outHeight] pixels. */
	fun frame(pose: Map<String, Float>, left: Float, top: Float, width: Float, height: Float, outWidth: Int, outHeight: Int): RasterImage =
		io.github.psd2live.core.synchronized(this) {
			val puppet = shown
			val base = model
			if (compiledFor !== puppet) {
				val ir = RigIrCompiler.compile(base.copy(rig = base.rig.copy(puppet = puppet)))
				renderer?.close()
				renderer = IrFrameRenderer.open(ir, false)
				compiledFor = puppet
			}
			renderer!!.render(pose, 0f, FrameSpec(left, top, width, height, outWidth, outHeight), null)
		}

	/** Every mesh's triangle edges at [pose], canvas pixels (x0, y0, x1, y1, ...), for the wireframe overlay. */
	fun wires(pose: Map<String, Float>): List<FloatArray> = PoseTweaks.wireframes(shown, pose).map { it.second }

	/** The tweaked rig's runtime files written over the rig's folder, as the outcome the Rig tab shows. Blocking. */
	fun finish(progress: (String, Double) -> Unit): RigOutcome {
		val started = RigFiles.nowSeconds()
		progress("Writing the runtime files", 0.3)
		return writeRig(outcome.name, outcome.source, model.config, model, started, progress)
	}
}
