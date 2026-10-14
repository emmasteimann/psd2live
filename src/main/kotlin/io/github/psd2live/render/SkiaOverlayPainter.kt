package io.github.psd2live.render

import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.ui.utils.toSkiaImage
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.VertexMode
import java.util.IdentityHashMap

/**
 * Draws an [OverlayScene] - the guide geometry the OpenGL renderer draws in its frame - with Skia, on whatever
 * canvas Compose hands over. On a Mac the window draws with Metal, so these guides are drawn on the GPU there
 * too, and the same code serves any Skia backend (an iPad's Metal included), where the OpenGL renderer cannot run.
 *
 * It replaces the Java2D guide pass for such windows: that pass painted every guide into a full-size image on the
 * CPU and converted it on each change, which is what made dragging on the Mac canvas heavy.
 *
 * Texture quads keep their Skia image while the same raster or page instance is drawn; [close] frees them.
 */
internal class SkiaOverlayPainter : AutoCloseable {
	private val rasters = IdentityHashMap<ByteArray, Image>()
	private val pages = IdentityHashMap<java.awt.image.BufferedImage, Image>()
	private val usedRasters: MutableSet<ByteArray> = java.util.Collections.newSetFromMap(IdentityHashMap())
	private val usedPages: MutableSet<java.awt.image.BufferedImage> = java.util.Collections.newSetFromMap(IdentityHashMap())

	fun paint(canvas: Canvas, overlay: OverlayScene, viewport: CanvasViewport) {
		if (overlay.items.isEmpty()) return
		usedRasters.clear(); usedPages.clear()
		Paint().use { paint ->
			paint.isAntiAlias = true
			for (item in overlay.items) {
				paint.reset()
				paint.isAntiAlias = true
				when (item) {
					is LineBatch -> lines(canvas, paint, item, viewport)
					is PointBatch -> points(canvas, paint, item, viewport)
					is FillBatch -> fill(canvas, paint, item, viewport)
					is PolylineBatch -> polyline(canvas, paint, item, viewport)
					is TextureQuad -> quad(canvas, paint, item, viewport)
				}
			}
		}
		// A raster or page no longer drawn is let go, as the GL renderer frees its texture.
		rasters.entries.removeIf { (key, image) -> (key !in usedRasters).also { if (it) image.close() } }
		pages.entries.removeIf { (key, image) -> (key !in usedPages).also { if (it) image.close() } }
	}

	private fun lines(canvas: Canvas, paint: Paint, batch: LineBatch, viewport: CanvasViewport) {
		val s = batch.segments
		if (s.size < 4) return
		val points = FloatArray(s.size / 4 * 4)
		var i = 0
		while (i + 3 < s.size) {
			points[i] = viewport.x(s[i]).toFloat()
			points[i + 1] = viewport.yFromWorld(s[i + 1]).toFloat()
			points[i + 2] = viewport.x(s[i + 2]).toFloat()
			points[i + 3] = viewport.yFromWorld(s[i + 3]).toFloat()
			i += 4
		}
		paint.color = batch.argb
		paint.mode = PaintMode.STROKE
		paint.strokeWidth = batch.width
		canvas.drawLines(points, paint)
	}

	private fun points(canvas: Canvas, paint: Paint, batch: PointBatch, viewport: CanvasViewport) {
		val c = batch.centers
		val inner = batch.radius - batch.ring
		for (i in 0 until c.size / 2) {
			val x = viewport.x(c[i * 2]).toFloat()
			val y = viewport.yFromWorld(c[i * 2 + 1]).toFloat()
			if (batch.ring > 0f) {
				paint.color = batch.strokeArgb
				canvas.drawCircle(x, y, batch.radius, paint)
				if (inner > 0f) {
					paint.color = batch.fillArgb
					canvas.drawCircle(x, y, inner, paint)
				}
			} else {
				paint.color = batch.fillArgb
				canvas.drawCircle(x, y, batch.radius, paint)
			}
		}
	}

	private fun fill(canvas: Canvas, paint: Paint, batch: FillBatch, viewport: CanvasViewport) {
		PathBuilder(PathFillMode.EVEN_ODD).use { path ->
			for (contour in batch.contours) {
				if (contour.size < 6) continue
				path.moveTo(viewport.x(contour[0]).toFloat(), viewport.yFromWorld(contour[1]).toFloat())
				for (i in 1 until contour.size / 2) path.lineTo(viewport.x(contour[i * 2]).toFloat(), viewport.yFromWorld(contour[i * 2 + 1]).toFloat())
				path.closePath()
			}
			paint.color = batch.argb
			path.detach().use { canvas.drawPath(it, paint) }
		}
	}

	private fun polyline(canvas: Canvas, paint: Paint, batch: PolylineBatch, viewport: CanvasViewport) {
		val p = batch.points
		if (p.size < 2) return
		paint.color = batch.argb
		if (p.size == 2) {
			// One point: a round cap's dot.
			canvas.drawCircle(viewport.x(p[0]).toFloat(), viewport.yFromWorld(p[1]).toFloat(), batch.width / 2f, paint)
			return
		}
		PathBuilder().use { path ->
			path.moveTo(viewport.x(p[0]).toFloat(), viewport.yFromWorld(p[1]).toFloat())
			for (i in 1 until p.size / 2) path.lineTo(viewport.x(p[i * 2]).toFloat(), viewport.yFromWorld(p[i * 2 + 1]).toFloat())
			if (batch.closed) path.closePath()
			// One stroke paints each pixel once, so the translucent halo does not darken where pieces meet.
			paint.mode = PaintMode.STROKE
			paint.strokeWidth = batch.width
			paint.strokeCap = PaintStrokeCap.ROUND
			paint.strokeJoin = PaintStrokeJoin.ROUND
			path.detach().use { canvas.drawPath(it, paint) }
		}
	}

	private fun quad(canvas: Canvas, paint: Paint, quad: TextureQuad, viewport: CanvasViewport) {
		val image = image(quad.texture) ?: return
		val sampling: SamplingMode = if (quad.nearest) FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)
			else FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)
		val saved = canvas.save()
		try {
			quad.clip?.let { clip ->
				PathBuilder().use { area ->
					for (i in 0 until clip.size / 4) {
						area.addRect(screenRect(viewport, clip[i * 4], clip[i * 4 + 1], clip[i * 4 + 2], clip[i * 4 + 3]))
					}
					area.detach().use { canvas.clipPath(it, antiAlias = false) }
				}
			}
			paint.setAlphaf(quad.alpha.coerceIn(0f, 1f))
			val w = image.width.toFloat()
			val h = image.height.toFloat()
			val corners = quad.corners
			if (corners == null) {
				val src = Rect.makeLTRB(quad.u0 * w, quad.v0 * h, quad.u1 * w, quad.v1 * h)
				val left = viewport.x(quad.x0).toFloat(); val top = viewport.yFromWorld(quad.y0).toFloat()
				val right = viewport.x(quad.x1).toFloat(); val bottom = viewport.yFromWorld(quad.y1).toFloat()
				// (x0, y0) takes (u0, v0) even when the rectangle runs the other way: flip by scaling.
				canvas.save()
				canvas.translate(left, top)
				val dw = right - left; val dh = bottom - top
				if (src.width != 0f && src.height != 0f && dw != 0f && dh != 0f) {
					canvas.scale(dw / src.width, dh / src.height)
					canvas.drawImageRect(image, src, Rect.makeWH(src.width, src.height), sampling, paint, true)
				}
				canvas.restore()
			} else {
				// A turned quad: two triangles sampling the image in its own pixels.
				val uv = floatArrayOf(quad.u0, quad.v0, quad.u1, quad.v0, quad.u1, quad.v1, quad.u0, quad.v1)
				val order = intArrayOf(0, 1, 2, 0, 2, 3)
				val positions = FloatArray(12); val texs = FloatArray(12)
				order.forEachIndexed { k, corner ->
					positions[k * 2] = viewport.x(corners[corner * 2]).toFloat()
					positions[k * 2 + 1] = viewport.yFromWorld(corners[corner * 2 + 1]).toFloat()
					texs[k * 2] = uv[corner * 2] * w
					texs[k * 2 + 1] = uv[corner * 2 + 1] * h
				}
				image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null).use { shader ->
					paint.shader = shader
					canvas.drawVertices(VertexMode.TRIANGLES, positions, null, texs, null, BlendMode.MODULATE, paint)
					paint.shader = null
				}
			}
		} finally { canvas.restoreToCount(saved) }
	}

	private fun screenRect(viewport: CanvasViewport, x0: Float, y0: Float, x1: Float, y1: Float): Rect {
		val ax = viewport.x(x0).toFloat(); val bx = viewport.x(x1).toFloat()
		val ay = viewport.yFromWorld(y0).toFloat(); val by = viewport.yFromWorld(y1).toFloat()
		return Rect.makeLTRB(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
	}

	private fun image(texture: QuadTexture): Image? = when (texture) {
		is RasterTexture -> {
			usedRasters += texture.rgba
			if (texture.width <= 0 || texture.height <= 0) null else rasters.getOrPut(texture.rgba) {
				Image.makeRaster(ImageInfo(texture.width, texture.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
					texture.rgba, texture.width * 4)
			}
		}
		is ImageTexture -> {
			usedPages += texture.image
			pages.getOrPut(texture.image) { texture.image.toSkiaImage() }
		}
	}

	override fun close() {
		rasters.values.forEach { it.close() }
		pages.values.forEach { it.close() }
		rasters.clear(); pages.clear()
	}
}
