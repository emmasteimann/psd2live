package io.github.psd2live.render

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.format.eval.P2lRuntime
import io.github.psd2live.targets.runtime.P2lrt
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Metal preview path end to end, on a real rig (LAYERLOOM_PARITY_DIR holding quill.psd; skipped without it or
 * without the runtime library): compile to .p2lrt as the preview does, evaluate in the runtime, capture the frame
 * and draw it with Skia. The rest pose covers the character's area, and turning the head changes the picture.
 * With LAYERLOOM_PREVIEW_OUT set, the frames are written there as PNGs to look at.
 */
class SkiaP2lrtPreviewTest {
	@Test
	fun `the runtime's frame draws with Skia and follows the pose`() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val runtime = P2lRuntime.load() ?: return
		val model = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"))
		runtime.load(P2lrt.write(RigIrCompiler.compile(model, simulations = true))).use { rig ->
			rig.behaviors(0)
			val skia = SkiaP2lrtModel.of(rig, model.atlas.pages.map { it.image })
			val (cw, ch) = rig.canvas
			val width = 400; val height = 600
			val scale = minOf(width / cw, height / ch)
			fun render(name: String, values: FloatArray): IntArray {
				rig.values = values
				rig.evaluate()
				val frame = SkiaP2lrtFrame.capture(rig, skia)
				val surface = Surface.makeRasterN32Premul(width, height)
				val started = System.nanoTime()
				SkiaP2lrtPainter.draw(surface.canvas, frame, scale, (width - cw * scale) / 2f, (height - ch * scale) / 2f)
				println("p2lrt skia draw $name: ${(System.nanoTime() - started) / 1_000_000} ms, ${frame.commands.size} commands")
				val image = surface.makeImageSnapshot()
				System.getenv("LAYERLOOM_PREVIEW_OUT")?.let { out ->
					Files.write(Path.of(out, "p2lrt-skia-$name.png"), image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
				}
				val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
				return IntArray(width * height) { bitmap.getColor(it % width, it / width) }
			}
			val rest = render("rest", rig.defaults.copyOf())
			val covered = rest.count { (it ushr 24) > 128 }
			assertTrue(covered > width * height / 10, "the rest pose covers too little: $covered pixels")
			val turned = rig.defaults.copyOf().also { values ->
				val i = rig.parameterIds.indexOfFirst { it.contains("AngleX", ignoreCase = true) }
				if (i >= 0) values[i] = 30f
			}
			val changed = render("angleX30", turned).zip(rest.toTypedArray()).count { (a, b) -> a != b }
			assertTrue(changed > 500, "turning the head changed only $changed pixels")
		}
	}
}
