package io.github.psd2live.ipad.studio

import okio.FileSystem
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals

/** Quill's own PSD through the studio, when it's on this machine (skipped elsewhere). */
class StudioRealArtTest {
	@OptIn(kotlin.time.ExperimentalTime::class)
	@Test fun quillImportsAndExports() {
		val path = "/private/tmp/claude-502/-Users-goosible-Downloads-beautiful-failure-copy/818085c4-7ee9-433c-b85d-eff01e588b80/scratchpad/rigbase/quill.psd".toPath()
		if (!FileSystem.SYSTEM.exists(path)) return
		val bytes = FileSystem.SYSTEM.read(path) { readByteArray() }
		val t0 = kotlin.time.TimeSource.Monotonic.markNow()
		val project = StudioPsd.import(bytes, "quill.psd")
		val t1 = t0.elapsedNow()
		val out = StudioPsd.export(project)
		val t2 = t0.elapsedNow()
		val back = PsdReader.read(out)
		val source = PsdReader.read(bytes)
		println("quill: ${project.width}x${project.height}, ${project.parts.size} parts, import $t1, export ${t2 - t1}, psd ${out.size / 1024} KB, json ${project.fileText().length / 1024} KB")
		assertEquals(source.layers.count { it.raster.width > 0 && it.raster.height > 0 }, project.parts.size)
		assertEquals(project.parts.map { it.name }, back.layers.map { it.name }.filter { it != "Remaining artwork" })
		// The same pixels come back for every layer.
		val srcLayers = source.layers.filter { it.raster.width > 0 && it.raster.height > 0 }
		for ((i, l) in back.layers.filter { it.name != "Remaining artwork" }.withIndex()) {
			val s = srcLayers[i]
			fun alphaSum(r: org.umamo.format.art.LayerRaster) = (3 until r.rgba.size step 4).sumOf { r.rgba[it].toInt() and 0xFF }.toLong()
			assertEquals(alphaSum(s.raster), alphaSum(l.raster), "alpha of ${s.name}")
		}
	}
}
