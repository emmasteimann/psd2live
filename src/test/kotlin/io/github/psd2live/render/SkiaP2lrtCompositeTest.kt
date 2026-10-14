package io.github.psd2live.render

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.umamo.render.puppet.compositeReference
import org.umamo.runtime.model.AlphaBlendMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Skia composite blender (the Metal preview's) against `BlendMath.compositeReference`, the spec umamo's GL
 * composite shader mirrors: every color mode under every alpha mode, opaque and translucent pixels.
 */
class SkiaP2lrtCompositeTest {
	private val info = ImageInfo(1, 1, ColorType.RGBA_8888, ColorAlphaType.PREMUL)

	private fun pixel(premul: IntArray): Image = Image.makeRaster(info, premul.map { it.toByte() }.toByteArray(), 4)

	private fun composite(src: IntArray, dst: IntArray, colorMode: Int, alphaMode: Int): FloatArray {
		val surface = Surface.makeRaster(info)
		val canvas = surface.canvas
		Paint().use { p -> p.blendMode = BlendMode.SRC; canvas.drawImage(pixel(dst), 0f, 0f, p) }
		Paint().use { layer ->
			layer.blender = SkiaP2lrtPainter.compositeBlender(colorMode, alphaMode, 1f, null, 0)
			canvas.saveLayer(null, layer)
			Paint().use { p -> p.blendMode = BlendMode.SRC; canvas.drawImage(pixel(src), 0f, 0f, p) }
			canvas.restore()
		}
		val bitmap = Bitmap()
		bitmap.allocPixels(info)
		surface.readPixels(bitmap, 0, 0)
		val bytes = bitmap.readPixels(info, 4, 0, 0)!!
		return FloatArray(4) { (bytes[it].toInt() and 0xff) / 255f }
	}

	@Test
	fun `every color and alpha mode matches the reference`() {
		assertEquals(18, org.umamo.runtime.model.BlendMode.entries.size)
		// Premultiplied RGBA bytes: opaque and translucent sources over opaque and translucent destinations.
		val sources = listOf(intArrayOf(200, 60, 120, 255), intArrayOf(90, 150, 30, 180), intArrayOf(40, 40, 120, 128))
		val destinations = listOf(intArrayOf(30, 180, 90, 255), intArrayOf(140, 70, 20, 200), intArrayOf(100, 100, 100, 150))
		val failures = mutableListOf<String>()
		for (colorMode in 0 until 18) for (alphaMode in 0 until 5) for (src in sources) for (dst in destinations) {
			val got = composite(src, dst, colorMode, alphaMode)
			val want = compositeReference(FloatArray(4) { src[it] / 255f }, FloatArray(4) { dst[it] / 255f },
				org.umamo.runtime.model.BlendMode.entries[colorMode], AlphaBlendMode.entries[alphaMode])
			if ((0 until 4).any { abs(got[it] - want[it]) > 3.5f / 255f }) {
				failures += "mode $colorMode/$alphaMode src ${src.toList()} dst ${dst.toList()}: got ${got.toList()} want ${want.toList()}"
			}
		}
		assertTrue(failures.isEmpty(), "${failures.size} mismatches, first: ${failures.take(5).joinToString("\n")}")
	}
}
