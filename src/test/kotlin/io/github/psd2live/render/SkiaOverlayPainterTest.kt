package io.github.psd2live.render

import io.github.psd2live.core.CanvasViewport
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Skia guide painter puts each kind of guide where the GPU renderer does: world y up, pixel sizes in pixels. */
class SkiaOverlayPainterTest {
	// World (x, y) lands on pixel (x, 100 - y).
	private val viewport = CanvasViewport(1.0, 0.0, 100.0, 100f, 100f)

	private fun render(vararg items: OverlayItem): (Int, Int) -> Int {
		val surface = Surface.makeRasterN32Premul(100, 100)
		SkiaOverlayPainter().use { it.paint(surface.canvas, OverlayScene(items.toList()), viewport) }
		val bitmap = org.jetbrains.skia.Bitmap()
		bitmap.allocN32Pixels(100, 100)
		surface.readPixels(bitmap, 0, 0)
		return { x, y -> bitmap.getColor(x, y) }
	}

	private fun alpha(argb: Int) = argb ushr 24

	@Test
	fun `lines points fills and polylines land in screen space`() {
		val red = 0xFFFF0000.toInt()
		val px = render(
			LineBatch(red, 3f, floatArrayOf(10f, 90f, 40f, 90f)),          // a row at y = 10
			PointBatch(0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 6f, 2f, floatArrayOf(70f, 70f)), // centre (70, 30)
			FillBatch(0xFFFFFFFF.toInt(), listOf(floatArrayOf(10f, 40f, 30f, 40f, 30f, 20f, 10f, 20f))), // y 60..80
			PolylineBatch(0xFF00FFFF.toInt(), 4f, floatArrayOf(60f, 10f, 90f, 10f), closed = false), // row y = 90
		)
		assertEquals(red, px(25, 10))
		assertEquals(0, alpha(px(25, 20)))
		assertEquals(0xFF00FF00.toInt(), px(70, 30), "the disc's middle takes its fill")
		assertEquals(0xFF0000FF.toInt(), px(74, 30), "its outer ring takes the stroke colour")
		assertEquals(0xFFFFFFFF.toInt(), px(20, 70))
		assertEquals(0xFF00FFFF.toInt(), px(75, 90))
	}

	@Test
	fun `a texture quad maps its first corner to u0 v0 and respects its clip`() {
		// 2 x 1 raster: left texel red, right texel blue (straight RGBA).
		val rgba = byteArrayOf(-1, 0, 0, -1, 0, 0, -1, -1)
		val texture = RasterTexture(2, 1, rgba)
		val px = render(TextureQuad(texture, 0f, 100f, 40f, 80f, nearest = true))
		assertEquals(0xFFFF0000.toInt(), px(5, 10))
		assertEquals(0xFF0000FF.toInt(), px(35, 10))
		// Clipped to the world rectangle x 0..20: the right half stays empty.
		val clipped = render(TextureQuad(texture, 0f, 100f, 40f, 80f, nearest = true, clip = floatArrayOf(0f, 100f, 20f, 80f)))
		assertEquals(0xFFFF0000.toInt(), clipped(5, 10))
		assertTrue(alpha(clipped(35, 10)) == 0)
	}
}
