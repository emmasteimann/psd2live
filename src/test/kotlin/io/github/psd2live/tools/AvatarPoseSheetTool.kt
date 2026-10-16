package io.github.psd2live.tools

import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.format.compile.FrameSpec
import org.umamo.format.psd.PsdReader
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * A character's rig at the poses that show whether it feels alive or like "a paper cut-out being bent": head turns,
 * tilt, eyes, blink, mouth, body lean - the whole figure, and the face up close. For reviewing an avatar by eye.
 *
 * LAYERLOOM_PSD=<psd> ./gradlew test --tests '*AvatarPoseSheetTool*'  (skipped without it) -> build/tools/avatar/<name>-*.png
 */
class AvatarPoseSheetTool {
	private val poses = listOf(
		"rest" to mapOf(),
		"look left (X -30)" to mapOf("ParamAngleX" to -30f),
		"look right (X +30)" to mapOf("ParamAngleX" to 30f),
		"look up (Y +30)" to mapOf("ParamAngleY" to 30f),
		"look down (Y -30)" to mapOf("ParamAngleY" to -30f),
		"tilt (Z +30)" to mapOf("ParamAngleZ" to 30f),
		"right + up" to mapOf("ParamAngleX" to 30f, "ParamAngleY" to 30f),
		"eyes left" to mapOf("ParamEyeBallX" to -1f),
		"blink" to mapOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 0f),
		"mouth open" to mapOf("ParamMouthOpenY" to 1f),
		"smile" to mapOf("ParamMouthForm" to 1f, "ParamMouthOpenY" to 0.5f),
		"body lean (Body X +10)" to mapOf("ParamBodyAngleX" to 10f, "ParamBodyAngleZ" to 10f),
	)

	@Test fun sheet() {
		val psd = System.getenv("LAYERLOOM_PSD") ?: return
		val name = File(psd).nameWithoutExtension
		val source = PsdReader.read(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(psd)))
		val model = PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false))
		val ir = RigIrCompiler.compile(model)
		val renderer = IrFrameRenderer.open(ir, false)
		val out = File("build/tools/avatar").apply { mkdirs() }
		fun frame(pose: Map<String, Float>, edge: Int) = renderer.render(pose, 0f, FrameSpec.canvas(ir, edge), null)
		val first = frame(emptyMap(), 700)
		// How much each pose changes from rest: pixels that differ, and the biggest move of any colour (a cheap "did it move" number).
		for ((label, pose) in poses.drop(1)) {
			val f = frame(pose, 700).argb
			val changed = f.indices.count { f[it] != first.argb[it] }
			println("AVATAR %-26s %6d px changed (%.1f%%)".format(label, changed, 100.0 * changed / f.size))
		}
		val whole = poses.map { (label, pose) -> label to frame(pose, 700).let { img(it.argb, it.width, it.height) } }
		write(grid(whole, 4), File(out, "$name-poses.png"))
		// The face: the top of the figure, rendered large and cropped to the head (the top ~38%, middle 60%).
		val big = poses.map { (label, pose) -> val f = frame(pose, 2400); label to img(f.argb, f.width, f.height) }
		val w = big[0].second.width; val h = big[0].second.height
		val faceTop = (System.getenv("LAYERLOOM_FACE_TOP")?.toFloat() ?: 0.04f) * h; val faceH = (System.getenv("LAYERLOOM_FACE_H")?.toFloat() ?: 0.34f) * h
		val faces = big.map { (l, i) -> l to i.getSubimage((w * 0.2).toInt(), faceTop.toInt(), (w * 0.6).toInt(), faceH.toInt().coerceAtMost(h - faceTop.toInt())) }
		write(grid(faces, 4, 360), File(out, "$name-face.png"))
		println("AVATAR sheets in ${out.absolutePath} (${first.width}x${first.height})")
	}

	private fun img(argb: IntArray, w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, w, h, argb, 0, w) }

	private fun grid(tiles: List<Pair<String, BufferedImage>>, cols: Int, tileW: Int? = null): BufferedImage {
		val tw = tileW ?: tiles[0].second.width
		val th = tiles[0].second.height * tw / tiles[0].second.width
		val rows = (tiles.size + cols - 1) / cols; val label = 26
		val sheet = BufferedImage(cols * tw, rows * (th + label), BufferedImage.TYPE_INT_RGB)
		val g = sheet.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
		g.color = Color(0xEEEAF4); g.fillRect(0, 0, sheet.width, sheet.height)
		g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
		for ((i, t) in tiles.withIndex()) {
			val x = (i % cols) * tw; val y = (i / cols) * (th + label)
			g.color = Color(0x302A40); g.drawString(t.first, x + 8, y + 19)
			g.drawImage(t.second, x, y + label, tw, th, null)
		}
		g.dispose(); return sheet
	}

	private fun write(image: BufferedImage, file: File) { ImageIO.write(image, "png", file); println("wrote $file") }
}
