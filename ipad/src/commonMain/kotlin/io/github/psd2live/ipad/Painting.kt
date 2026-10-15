package io.github.psd2live.ipad

import io.github.psd2live.core.PaintPixels
import io.github.psd2live.core.PaintRasterSession
import io.github.psd2live.core.PaintSpace
import io.github.psd2live.core.RasterPaintCommit
import io.github.psd2live.core.RasterPaintEngine
import io.github.psd2live.core.RasterPaintShape
import io.github.psd2live.core.Rectangle
import io.github.psd2live.core.RigImage
import io.github.psd2live.core.height
import io.github.psd2live.core.newRigImage
import io.github.psd2live.core.readArgb
import io.github.psd2live.core.width
import io.github.psd2live.core.writeArgb
import io.github.psd2live.project.LayerCanvasRect
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.isFullyTransparent

/** The layers a rig's art can be painted on, top of the stack first. */
fun paintableLayers(outcome: RigOutcome): List<SourceLayer> =
	outcome.source.layers.filter { it.raster.width > 0 && it.raster.height > 0 && !it.raster.isFullyTransparent() }
		.sortedBy { it.order }

/**
 * One layer being painted on the iPad, with the desktop's paint engine: the layer's own pixel grid over the canvas
 * ([PaintSpace]), strokes that land once however the drag is cut up, and tile-patch undo ([PaintRasterSession]).
 * Everything the screen passes is in canvas units. Use it from one thread.
 */
class LayerPainting(val outcome: RigOutcome, val layer: SourceLayer) {
	val canvasWidth = outcome.source.widthPx
	val canvasHeight = outcome.source.heightPx
	val space = PaintSpace.of(layer, canvasWidth, canvasHeight)
	val image: RigImage = space.image(layer)
	private val session = PaintRasterSession(layer.id.raw, layer.name, image, copy(image))
	private var stroking = false

	/** Bumped on every change, so the screen knows to redraw. */
	var version = 0L
		private set

	init { session.onChanged = { version++ } }

	val canUndo: Boolean get() = !stroking && session.canUndo()
	val canRedo: Boolean get() = !stroking && session.canRedo()
	val isDirty: Boolean get() = session.isDirty

	fun beginStroke() { session.beginStroke(); stroking = true }

	/** One drag segment, canvas units; [radius] is the brush's in canvas pixels. */
	/** Drops the stroke in progress (a second finger landed): its pixels go back. */
	fun cancelStroke() { if (stroking) { session.abandonStroke(); stroking = false } }

	fun segment(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, hardness: Float, color: Int, opacity: Float, erase: Boolean) {
		if (!stroking) beginStroke()
		val tip = RasterPaintEngine.Tip(space.imageLength(radius), hardness)
		session.stroke().addSegment(space.canvasToImageX(x0), space.canvasToImageY(y0), space.canvasToImageX(x1), space.canvasToImageY(y1), tip)
			?.let { session.landSegment(it, color, opacity, erase) }
	}

	fun endStroke(name: String) { if (stroking) { session.recordStroke(name); stroking = false } }

	/** A bucket fill at canvas point ([x], [y]). */
	fun fill(x: Float, y: Float, color: Int, opacity: Float, tolerance: Int) {
		val px = kotlin.math.floor(space.canvasToImageX(x)).toInt(); val py = kotlin.math.floor(space.canvasToImageY(y)).toInt()
		RasterPaintEngine.floodFill(image, px, py, color, tolerance, Rectangle(0, 0, image.width, image.height),
			before = { session.willWrite(it) }, opacity = opacity)
		session.recordStroke("Fill")
	}

	/** The layer's own pixel under canvas point ([x], [y]), 0 where it has none. */
	fun pixelAt(x: Float, y: Float): Int {
		val px = kotlin.math.floor(space.canvasToImageX(x)).toInt(); val py = kotlin.math.floor(space.canvasToImageY(y)).toInt()
		return if (px in 0 until image.width && py in 0 until image.height) image.readArgb(px, py, 1, 1)[0] else 0
	}

	// ---- Shapes: previewed while the two corners are dragged, landed as one stroke when the pencil lifts. ----
	private var shapeFrom: Pair<Float, Float>? = null

	fun shapeBegin(x: Float, y: Float) { shapeFrom = space.canvasToImageX(x) to space.canvasToImageY(y) }

	/** The shape from the press to canvas ([x], [y]), redrawn over what was there before it. */
	fun shapeTo(x: Float, y: Float, shape: RasterPaintShape, color: Int, opacity: Float, width: Float, filled: Boolean) {
		val (x0, y0) = shapeFrom ?: return
		session.abandonStroke()
		val x1 = space.canvasToImageX(x); val y1 = space.canvasToImageY(y)
		val w = space.imageLength(width)
		val area = RasterPaintEngine.shapeArea(x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(), w, Rectangle(0, 0, image.width, image.height))
		session.edit(area) { RasterPaintEngine.drawShape(it, x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(), shape, color, opacity, w, filled, Rectangle(0, 0, image.width, image.height)) }
	}

	fun shapeEnd() { if (shapeFrom != null) { shapeFrom = null; session.recordStroke("Shape") } }

	// ---- The mask: which part of the layer a cut takes. Its own raster and history, painted like the layer. ----
	/** How much of each pixel the mask holds, as the alpha of a red overlay (the same grid as [image]). */
	val mask: RigImage = newRigImage(image.width, image.height)
	private val maskSession = PaintRasterSession("mask", "Mask", mask, newRigImage(image.width, image.height)).also { it.onChanged = { version++ } }
	private var masking = false
	val canUndoMask: Boolean get() = !masking && maskSession.canUndo()
	val canRedoMask: Boolean get() = !masking && maskSession.canRedo()
	val hasMask: Boolean get() = maskSession.isDirty

	fun maskSegment(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, hardness: Float, erase: Boolean) {
		if (!masking) { maskSession.beginStroke(); masking = true }
		val tip = RasterPaintEngine.Tip(space.imageLength(radius), hardness)
		maskSession.stroke().addSegment(space.canvasToImageX(x0), space.canvasToImageY(y0), space.canvasToImageX(x1), space.canvasToImageY(y1), tip)
			?.let { maskSession.landSegment(it, (0xFF shl 24) or MASK_COLOR, 1f, erase) }
	}

	fun maskEnd() { if (masking) { maskSession.recordStroke("Mask"); masking = false } }
	fun maskCancel() { if (masking) { maskSession.abandonStroke(); masking = false } }

	/** Adds to the mask the region of the layer around canvas point ([x], [y]) whose colour is within [tolerance]. */
	fun maskByColour(x: Float, y: Float, tolerance: Int) {
		val px = kotlin.math.floor(space.canvasToImageX(x)).toInt(); val py = kotlin.math.floor(space.canvasToImageY(y)).toInt()
		if (px !in 0 until image.width || py !in 0 until image.height) return
		val probe = copy(image)
		val area = RasterPaintEngine.floodFill(probe, px, py, 0x01FF00FE, tolerance, Rectangle(0, 0, image.width, image.height)) ?: return
		val before = image.readArgb(area.x, area.y, area.width, area.height); val after = probe.readArgb(area.x, area.y, area.width, area.height)
		maskSession.willWrite(area)
		val current = mask.readArgb(area.x, area.y, area.width, area.height)
		for (i in current.indices) if (before[i] != after[i] && (before[i] ushr 24) > 0) current[i] = (0xFF shl 24) or MASK_COLOR
		mask.writeArgb(area.x, area.y, area.width, area.height, current)
		maskSession.recordStroke("Select by colour")
	}

	fun clearMask() { maskSession.willWrite(Rectangle(0, 0, mask.width, mask.height)); RasterPaintEngine.clear(mask); maskSession.recordStroke("Clear mask") }
	fun undoMask() { maskSession.undo() }
	fun redoMask() { maskSession.redo() }

	/** Erases the masked part of the layer (softly, by the mask's alpha), as one paint stroke. */
	fun eraseMasked() {
		val w = image.width; val h = image.height
		val pixels = image.readArgb(0, 0, w, h); val m = mask.readArgb(0, 0, w, h)
		session.willWrite(Rectangle(0, 0, w, h))
		for (i in pixels.indices) { val a = pixels[i] ushr 24; val cut = (a * (m[i] ushr 24) + 127) / 255; pixels[i] = ((a - cut) shl 24) or (pixels[i] and 0xFFFFFF) }
		image.writeArgb(0, 0, w, h, pixels)
		session.recordStroke("Erase masked")
		clearMask()
	}

	/**
	 * Cuts the masked part out into a new layer named [name], directly above this one, and re-rigs. Paint on this layer
	 * so far goes with it. Hand tweaks on this layer's meshes are dropped (both pieces get new meshes); the count is
	 * in [RigOutcome.warnings]. Blocking.
	 */
	@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
	fun cutToNewLayer(name: String, progress: (String, Double) -> Unit): RigOutcome {
		val started = RigFiles.nowSeconds()
		progress("Cutting the layer", 0.02)
		val r = io.github.psd2live.core.LayerSplit.split(outcome.model, layer.id.raw, space, image, mask, name.ifBlank { "${layer.name} part" },
			"split-${kotlin.uuid.Uuid.random()}", outcome.config.generationSource)
		val config = outcome.config.copy(generationSource = r.generationSource, rigEdits = r.rigEdits)
		val rigged = buildRig(outcome.name, r.source, config, started, progress)
		return if (r.droppedEdits == 0) rigged else rigged.withWarning(
			"${r.droppedEdits} hand tweak${if (r.droppedEdits == 1) "" else "s"} on ${layer.name} were dropped: the cut gave it a new mesh.")
	}

	fun undo() { session.undo() }
	fun redo() { session.redo() }

	/**
	 * The rig rebuilt with the painted layer: the desktop's "repaint that keeps every mesh" - the pixels go into the
	 * source, the rig is generated from the art as it was first rigged, so meshes and deformers stay and only the
	 * textures follow the paint. Blocking.
	 */
	fun apply(progress: (String, Double) -> Unit): RigOutcome {
		val started = RigFiles.nowSeconds()
		progress("Cropping the painted layer", 0.01)
		val painted = space.crop(image)
		val pixels = PaintPixels(painted.raster, painted.rect ?: LayerCanvasRect.of(painted.bounds))
		val repainted = RasterPaintCommit.paintedSource(outcome.model, layer.id.raw, pixels)
		val config = outcome.config.copy(generationSource = outcome.config.generationSource ?: outcome.source)
		return buildRig(outcome.name, repainted.source, config, started, progress)
	}

	private companion object {
		const val MASK_COLOR = 0xFF4060
		fun copy(image: RigImage) = newRigImage(image.width, image.height).also {
			it.writeArgb(0, 0, image.width, image.height, image.readArgb(0, 0, image.width, image.height))
		}
	}
}

/** The block ([x], [y], [width], [height]) of [image], in its pixels, stretched to [outWidth] x [outHeight], nearest pixel. */
expect fun RigImage.regionBitmap(x: Float, y: Float, width: Float, height: Float, outWidth: Int, outHeight: Int): androidx.compose.ui.graphics.ImageBitmap

/** [image] as a Compose image at most [maxEdge] px on its longer side. */
expect fun RigImage.toImageBitmap(maxEdge: Int): androidx.compose.ui.graphics.ImageBitmap
