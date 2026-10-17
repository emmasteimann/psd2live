package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import androidx.compose.ui.graphics.toPixelMap
import kotlin.time.measureTime

/** Where a canvas frame's time goes on Boo (prints only; skipped without the file). Simulator debug: compare ratios, not ms. */
class RenderBenchTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"

	@Test fun frameCosts() {
		if (!okio.FileSystem.SYSTEM.exists(path.toPath())) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		val cw = e.puppet.canvasWidth; val ch = e.puppet.canvasHeight
		e.frame(emptyMap(), 0f, 0f, cw, ch, 64, 64) // compile once
		val out = StringBuilder("BENCH canvas ${cw}x${ch}\n")
		for ((label, w) in listOf("fit 700w" to 700, "1MP" to 1000, "2.2MP" to 1480)) {
			val h = (w * ch / cw).toInt()
			var img: io.github.psd2live.format.compile.RasterImage? = null
			val r = measureTime { repeat(3) { img = e.frame(emptyMap(), 0f, 0f, cw, ch, w, h) } } / 3
			val b = measureTime { repeat(3) { img!!.toImageBitmap() } } / 3
			out.append("BENCH $label ${w}x$h render=$r bitmap=$b\n")
		}
		// A zoomed crop: a quarter of the canvas at 2.2MP.
		val crop = measureTime { repeat(3) { e.frame(emptyMap(), cw / 4, ch / 4, cw / 2, ch / 2, 1480, (1480 * ch / cw).toInt()) } } / 3
		out.append("BENCH crop-quarter 2.2MP render=$crop\n")
		val posed = measureTime { repeat(3) { e.frame(mapOf("ParamAngleX" to 20f), 0f, 0f, cw, ch, 1000, (1000 * ch / cw).toInt()) } } / 3
		out.append("BENCH posed 1MP render=$posed\n")
		println(out)
		okio.FileSystem.SYSTEM.write("/private/tmp/claude-502/-Users-goosible-Downloads-beautiful-failure-copy/818085c4-7ee9-433c-b85d-eff01e588b80/scratchpad/bench.txt".toPath()) { writeUtf8(out.toString()) }
	}

	/** Frames drawn in bands across the cores are pixel for pixel the whole frame: the top half drawn as its own frame (other band edges) matches. */
	@Test fun bandsDrawExactlyTheWholeFrame() {
		if (!okio.FileSystem.SYSTEM.exists(path.toPath())) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		val cw = e.puppet.canvasWidth; val ch = e.puppet.canvasHeight
		val pose = mapOf("ParamAngleX" to 20f)
		val full = e.frame(pose, 0f, 0f, cw, ch, 1000, 1500)
		val top = e.frame(pose, 0f, 0f, cw, ch / 2f, 1000, 750)
		var differ = 0
		for (i in top.argb.indices) if (top.argb[i] != full.argb[i]) differ++
		kotlin.test.assertEquals(0, differ, "pixels that differ between the banded whole frame and its top half")
		kotlin.test.assertTrue(full.argb.count { it != 0 } > 100_000, "she is drawn")
	}

	/** The fast hand-over gives the same bytes the old per-pixel loop did (BGRA from 0xAARRGGBB). */
	@Test fun bitmapHandOverKeepsTheColours() {
		val image = io.github.psd2live.format.compile.RasterImage(2, 1, intArrayOf(0x80112233.toInt(), 0xFF445566.toInt()))
		val pixels = image.toImageBitmap().toPixelMap()
		kotlin.test.assertEquals(androidx.compose.ui.graphics.Color(0x44, 0x55, 0x66, 0xFF), pixels[1, 0])
		val c = pixels[0, 0]
		kotlin.test.assertEquals(0x80 / 255f, c.alpha, 0.01f); kotlin.test.assertEquals(0x11 / 255f, c.red, 0.02f); kotlin.test.assertEquals(0x33 / 255f, c.blue, 0.02f)
	}
}
