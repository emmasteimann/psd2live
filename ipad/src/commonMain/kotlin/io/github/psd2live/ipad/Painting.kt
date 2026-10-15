package io.github.psd2live.ipad

import io.github.psd2live.core.PaintPixels
import io.github.psd2live.core.PaintRasterSession
import io.github.psd2live.core.PaintSpace
import io.github.psd2live.core.RasterPaintCommit
import io.github.psd2live.core.RasterPaintEngine
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
		fun copy(image: RigImage) = newRigImage(image.width, image.height).also {
			it.writeArgb(0, 0, image.width, image.height, image.readArgb(0, 0, image.width, image.height))
		}
	}
}

/** The block ([x], [y], [width], [height]) of [image], in its pixels, stretched to [outWidth] x [outHeight], nearest pixel. */
expect fun RigImage.regionBitmap(x: Float, y: Float, width: Float, height: Float, outWidth: Int, outHeight: Int): androidx.compose.ui.graphics.ImageBitmap

/** [image] as a Compose image at most [maxEdge] px on its longer side. */
expect fun RigImage.toImageBitmap(maxEdge: Int): androidx.compose.ui.graphics.ImageBitmap
