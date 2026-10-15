package io.github.psd2live.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.FileSystem
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The whole rigger on the iPad: a real PSD in, the rig built, the moc3 runtime files out - the same calls the desktop
 * makes. Runs when LAYERLOOM_PSD names a PSD (as the desktop's real-rig tests take theirs), else it is skipped.
 */
class PipelineOnIpadTest {
	@OptIn(ExperimentalForeignApi::class)
	@Test
	fun aRealPsdRigsAndExportsOnTheIpad() {
		val path = platform.posix.getenv("LAYERLOOM_PSD")?.toKString()?.takeIf { it.isNotBlank() } ?: return println("LAYERLOOM_PSD not set: skipped")
		val bytes = FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }
		val started = nanoTime()
		val config = PipelineConfig(exportCmo3 = false)
		val pipeline = PSD2LivePipeline()
		val model = pipeline.buildPreview(PsdReader.read(bytes), config)
		val built = nanoTime()
		val outcome = pipeline.exportFiles(model, path.substringAfterLast('/')) { name, data -> name to data }
		val files = outcome.files.toMap()
		println("iPad rig: ${model.rig.puppet.drawables.size} drawables, ${model.rig.puppet.deformers.size} deformers, " +
			"${model.rig.puppet.parameters.size} parameters in ${(built - started) / 1_000_000} ms; export ${files.keys.sorted()}; " +
			"${outcome.warnings.size} warnings")
		for ((name, data) in files.entries.sortedBy { it.key }) println("FILE $name ${hex(MessageDigest.getInstance("SHA-256").digest(data))}")
		outcome.warnings.forEach { println("WARN $it") }
		// PNG bytes differ by encoder (ImageIO on the desktop); the pixels must not.
		platform.posix.getenv("LAYERLOOM_DUMP")?.toKString()?.let { dump -> // DUMP: raw page pixels and lip tiles, for comparing platforms
			files.entries.filter { it.key.endsWith(".png") }.forEach { (name, data) -> FileSystem.SYSTEM.write("$dump/ios-${name.substringAfterLast('/')}.rgba".toPath()) { write(org.umamo.format.png.PngCodec.read(data).rgba) } }
			FileSystem.SYSTEM.write("$dump/ios-placements.txt".toPath()) { writeUtf8(model.atlas.placementByLayerId.entries.joinToString("\n") { (id, p) -> "$id ${p.x} ${p.y} ${p.width} ${p.height}" }) }
		}
		for ((name, data) in files.entries.filter { it.key.endsWith(".png") }) println("PIXELS $name ${hex(MessageDigest.getInstance("SHA-256").digest(org.umamo.format.png.PngCodec.read(data).rgba))}")
		// The preview: the rig posed and drawn with the shared software rasterizer, as the desktop's raster exports draw it.
		val ir = RigIrCompiler.compile(model)
		val session = IrFrameRenderer.open(ir, false)
		val frame = io.github.psd2live.format.compile.FrameSpec.canvas(ir, 1024)
		for ((label, pose) in listOf("neutral" to emptyMap(), "angleX30" to mapOf("ParamAngleX" to 30f))) {
			val image = session.render(pose, 0f, frame, null)
			val bytes = ByteArray(image.argb.size * 4).also { b -> image.argb.forEachIndexed { i, p -> b[i * 4] = (p ushr 24).toByte(); b[i * 4 + 1] = (p ushr 16).toByte(); b[i * 4 + 2] = (p ushr 8).toByte(); b[i * 4 + 3] = p.toByte() } }
			println("FRAME $label ${image.width}x${image.height} ${hex(MessageDigest.getInstance("SHA-256").digest(bytes))}")
			platform.posix.getenv("LAYERLOOM_DUMP")?.toKString()?.let { dump -> FileSystem.SYSTEM.write("$dump/ios-frame-$label.argb".toPath()) { write(bytes) } }
			assertTrue(image.argb.count { it ushr 24 != 0 } > image.argb.size / 20, "the $label frame draws the character")
		}
		session.close()
		assertTrue(model.rig.puppet.drawables.size > 10, "drawables")
		assertTrue(model.rig.puppet.deformers.isNotEmpty(), "deformers")
		assertTrue(model.rig.puppet.parameters.any { it.id.raw == "ParamAngleX" }, "a head angle parameter")
		val moc = files.entries.single { it.key.endsWith(".moc3") }
		assertTrue(moc.value.size > 1000 && moc.value.decodeToString(0, 3) == "MOC", "a moc3 file")
		assertTrue(files.keys.any { it.endsWith(".model3.json") }, "a model3.json")
		assertTrue(files.keys.any { it.endsWith(".png") }, "texture pages")
	}
}
