package io.github.psd2live.tools
import io.github.psd2live.core.buildPreview

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.SkeletonAutoBuilder
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The auto skeleton drawn over its sample, for checking joint placement and forks by eye: bones as lines from head
 * (ring) to tail (dot), coloured by role, into build/tools/skeleton-auto/<sample>.png. PSD2LIVE_SAMPLE names the
 * sample (tml by default); PSD2LIVE_SAMPLES takes several, comma separated; PSD2LIVE_ZOOM=left,top,right,bottom crops, in canvas pixels.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*SkeletonAutoViewTool.view'
 */
class SkeletonAutoViewTool {
	@Test fun view() {
		requireTools()
		val samples = setting("PSD2LIVE_SAMPLES", "").split(",").filter { it.isNotBlank() }
			.map { name -> File(name).takeIf { it.isFile }?.let { Sample(it.nameWithoutExtension, it.toPath()) } ?: Sample(name, java.nio.file.Path.of("examples/$name/psd-input/$name.psd")) }
			.ifEmpty { listOf(Sample.fromEnvironment()) }
		val out = output("skeleton-auto")
		for (sample in samples) {
			val plain = PSD2LivePipeline().buildPreview(sample.path)
			val spec = SkeletonAutoBuilder.build(plain.analysis, plain.rig)
			println("== ${sample.name}")
			val a = plain.analysis.anchors
			println("  anchors shoulderY=%.0f hipY=%.0f body=%s character=%s".format(a.shoulderY, a.hipY, a.body, a.character))
			for (l in plain.analysis.layers.filter { it.semantic.tag in io.github.psd2live.core.SkeletonAutoBuilder.limbTags || it.semantic.tag == io.github.psd2live.core.SemanticTag.TOPWEAR || it.semantic.tag == io.github.psd2live.core.SemanticTag.BOTTOMWEAR })
				println("  layer %-30s %-10s side=%-5s type=%s bounds=%s".format(l.source.name.take(30), l.semantic.tag, l.semantic.side, l.semantic.type, l.bounds))
			val verts = SkeletonAutoBuilder.canvasVertices(plain.rig.puppet)
			val layerTag = plain.analysis.layers.associate { it.source.id.raw to it.semantic.tag }
			for ((d, v) in verts) {
				val tag = layerTag[plain.rig.layerIdByDrawableId[d] ?: d] ?: continue
				if (tag !in io.github.psd2live.core.SkeletonAutoBuilder.limbTags && tag != io.github.psd2live.core.SemanticTag.TOPWEAR && tag != io.github.psd2live.core.SemanticTag.BOTTOMWEAR) continue
				val xs = (0 until v.size / 2).map { v[it * 2] }; val ys = (0 until v.size / 2).map { v[it * 2 + 1] }
				println("  mesh %-24s %-10s n=%d x=%.0f..%.0f y=%.0f..%.0f".format(d.take(24), tag, v.size / 2, xs.min(), xs.max(), ys.min(), ys.max()))
			}
			for (b in spec.bones) println("  %-14s %-10s parent=%-12s head=(%.0f,%.0f) tail=(%.0f,%.0f) meshes=%d".format(
				b.id, b.role, b.parentId, b.headX, b.headY, b.tailX, b.tailY, b.drawableIds.size))
			val canvas = Renderer(plain, 900).canvas
			val c = plain.analysis.anchors.character
			val zoom = System.getenv("PSD2LIVE_ZOOM")?.split(",")?.map { it.toFloat() }
			val rect = zoom?.let { io.github.psd2live.core.Bounds(it[0], it[1], it[2], it[3]) }
				?: io.github.psd2live.core.Bounds(maxOf(0f, c.left - 20f), maxOf(0f, c.top - 20f), minOf(canvas.right, c.right + 20f), minOf(canvas.bottom, c.bottom + 20f))
			val image = Renderer(plain, 900).render(emptyMap(), rect)
			val scale = image.width / rect.width
			val g = image.createGraphics()
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			fun px(x: Float) = ((x - rect.left) * scale)
			fun py(y: Float) = ((y - rect.top) * scale)
			for (b in spec.bones) {
				val hue = (b.role.ordinal * 0.13f) % 1f
				g.color = Color.getHSBColor(hue, 0.9f, 0.95f)
				g.stroke = BasicStroke(3f)
				g.drawLine(px(b.headX).toInt(), py(b.headY).toInt(), px(b.tailX).toInt(), py(b.tailY).toInt())
				g.color = Color.WHITE
				g.drawOval(px(b.headX).toInt() - 5, py(b.headY).toInt() - 5, 10, 10)
				g.color = Color.BLACK
				g.fillOval(px(b.tailX).toInt() - 3, py(b.tailY).toInt() - 3, 6, 6)
			}
			g.dispose()
			ImageIO.write(image, "png", File(out, "${sample.name}.png"))
		}
	}
}
