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

	val parameters: List<PreviewParameter> = model.rig.puppet.parameters.map { PreviewParameter(it.id.raw, it.name, it.min, it.max, it.default) }
	val canvasWidth: Float get() = model.rig.puppet.canvasWidth
	val canvasHeight: Float get() = model.rig.puppet.canvasHeight
	val canUndo get() = undos.isNotEmpty() && session == null
	val canRedo get() = redos.isNotEmpty() && session == null
	val edits get() = model.config.rigEdits.authoringJournal.size - outcome.model.config.rigEdits.authoringJournal.size

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
