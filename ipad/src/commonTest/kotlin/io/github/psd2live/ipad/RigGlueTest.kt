package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mesh glue on Boo's real rig (skipped without the file): weld a seam, see it hold, unglue it. */
class RigGlueTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"

	@Test fun weldingASeamPairsThePartsKeepsRestAndCanBeUndone() {
		if (!okio.FileSystem.SYSTEM.exists(path.toPath())) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		val names = RigGlue.meshes(e)
		// Two parts that touch: the face and a front hair layer sit on each other.
		val face = names.first { it.second.contains("face", true) }.first
		val hair = names.first { it.second.contains("hair", true) && it.second.contains("front", true) }.first
		val restBefore = e.frame(emptyMap(), 0f, 0f, 1000f, 1000f, 200, 200).argb.copyOf()
		RigGlue.weldAll(e, face, hair, 60f)
		val welds = RigGlue.welds(e, face, hair)
		assertTrue(welds.isNotEmpty(), "the seam welded")
		assertTrue(RigGlue.pairs(e).any { setOf(it.first, it.second) == setOf(face, hair) })
		// Welds are made where both rest, so the welded points coincide at rest.
		assertTrue(welds.all { kotlin.math.hypot(it.ax - it.bx, it.ay - it.by) < 1.5f }, "welded points meet at rest")
		val restAfter = e.frame(emptyMap(), 0f, 0f, 1000f, 1000f, 200, 200).argb
		val changed = restBefore.indices.count { restBefore[it] != restAfter[it] }
		assertTrue(changed < restBefore.size / 200, "the picture at rest barely changes: $changed px")
		// It survives a full re-rig (it is in the journal).
		val rerig = RigEditing(PSD2LivePipeline().buildPreview(source, e.model.config), source)
		assertEquals(welds.size, RigGlue.welds(rerig, face, hair).size)
		RigGlue.removeAll(e, face, hair)
		assertTrue(RigGlue.welds(e, face, hair).isEmpty(), "unglued")
		e.undo()
		assertEquals(welds.size, RigGlue.welds(e, face, hair).size, "undo brings the welds back")
	}
}
