package io.github.psd2live.ipad

import io.github.psd2live.core.PipelineConfig
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pose sheet on Quill's real rig (skipped without the file). */
class RigPoseSheetTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-quill3/quill_v3.psd"

	@Test fun everyPoseDrawsMovesAndSaves() {
		if (!okio.FileSystem.SYSTEM.exists(path.toPath())) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		val outcome = buildRig("pose-sheet-test", source, PipelineConfig(exportCmo3 = false), RigFiles.nowSeconds()) { _, _ -> }
		val w = outcome.source.widthPx; val h = outcome.source.heightPx
		var seen = 0
		val whole = RigPoseSheet.render(outcome.preview, w, h, null, 240) { _, _ -> seen++ }
		assertEquals(RigPoseSheet.poses.size, whole.size); assertEquals(whole.size, seen)
		val by = whole.associateBy { it.pose.label }
		assertEquals(0.0, by.getValue("rest").changed)
		assertTrue(whole.all { it.missing.isEmpty() }, "Quill has every pose's parameters: ${whole.filter { it.missing.isNotEmpty() }.map { it.pose.label to it.missing }}")
		for (label in listOf("look left (X -30)", "look right (X +30)", "tilt (Z +30)", "blink", "body lean (Body X +10)"))
			assertTrue(by.getValue(label).changed > 0.001, "$label moves: ${by.getValue(label).changed}")
		// The face crop is its own, smaller region at the same edge.
		val face = RigPoseSheet.render(outcome.preview, w, h, RigPoseSheet.Face(), 240)
		assertTrue(face[0].image.width.toFloat() / face[0].image.height > whole[0].image.width.toFloat() / whole[0].image.height,
			"the face crop is wider for its height than the whole figure")
		assertTrue(face.first { it.pose.label == "blink" }.changed > by.getValue("blink").changed, "a blink is a bigger share of the face than of the figure")
		val (sw, sh, argb) = RigPoseSheet.sheet(whole)
		assertEquals(sw * sh, argb.size); assertTrue(sw > 4 * whole[0].image.width && sh > 3 * whole[0].image.height)
		val folder = RigPoseSheet.write(outcome, whole, false) { iw, ih, px -> io.github.psd2live.ipad.studio.encodePng(iw, ih, px) }
		val files = okio.FileSystem.SYSTEM.list(folder.toPath()).map { it.name }
		assertTrue("pose-sheet-test-poses.png" in files && "01 rest.png" in files && "09 blink.png" in files, "files: $files")
	}
}
