package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.SwingKind
import io.github.psd2live.core.SwingPreset
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A swing made, auditioned, kept and removed on Boo's real rig (skipped without the file). */
class RigSwingsTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"

	@Test fun aSwingAuditionsKeepsSurvivesAReRigAndRemoves() {
		if (!okio.FileSystem.SYSTEM.exists(path.toPath())) return
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		val e = RigEditing(PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false)), source)
		val before = RigSwings.list(e).size
		val hair = e.model.rig.puppet.drawables.first { it.mesh != null && it.name.contains("hair", true) && RigSwings.list(e).none { s -> it.id.raw in s.targets } }
		var draft = RigSwings.start(e, listOf(hair.id.raw), SwingPreset.HAIR)
		draft = RigSwings.kinds(e, draft, listOf(SwingKind.LATERAL, SwingKind.VERTICAL))
		assertEquals(2, draft.motions.size, "both directions")
		draft = RigSwings.segments(e, draft, 0, 2)
		assertEquals(2, draft.motions[0].segments)
		// The audition copy moves the hair; the rig itself doesn't yet.
		val (audition, prepared) = RigSwings.prepare(e, draft)
		val preview = RigPreview(audition)
		val rest = preview.parameters.associate { it.id to it.default }
		val still = preview.render(rest, 400).argb
		val swung = preview.render(rest + io.github.psd2live.application.WorkspaceSwingDrafts.audition(prepared, 0.4f).mapKeys { it.key.raw }, 400).argb
		assertTrue(still.indices.count { still[it] != swung[it] } > 50, "the audition swings the hair")
		assertTrue(prepared.motions.flatMap { it.parameterIds }.none { id -> e.model.rig.puppet.parameters.any { it.id.raw == id } }, "the rig is untouched until kept")
		RigSwings.commit(e, draft)
		assertEquals(before + 1, RigSwings.list(e).size)
		val ids = draft.motions.flatMap { it.parameterIds }
		assertTrue(ids.all { id -> e.model.rig.puppet.parameters.any { it.id.raw == id } }, "the kept swing's parameters are in the rig")
		// A full re-rig from the edits keeps it.
		val rerig = PSD2LivePipeline().buildPreview(source, e.model.config)
		assertTrue(ids.all { id -> rerig.rig.puppet.parameters.any { it.id.raw == id } }, "a re-rig keeps the swing")
		RigSwings.remove(e, draft.id, bake = false)
		assertEquals(before, RigSwings.list(e).size)
		assertTrue(ids.none { id -> e.model.rig.puppet.parameters.any { it.id.raw == id } }, "removed")
		e.undo()
		assertEquals(before + 1, RigSwings.list(e).size, "undo brings it back")
	}

	/** Goose's crash (2026-10-16): the size slider went to 0.8, past the model's 0.7, and the throw closed the app. */
	@Test fun shapeEditsPastTheLimitsAreHeldInsteadOfThrowing() {
		val draft = io.github.psd2live.core.RigSwingEdit.single("s", "Swing", SwingKind.LATERAL, listOf("m"), listOf("P"))
		val big = RigSwings.shape(draft, 0) { it.copy(magnitude = 0.8f) }
		assertTrue(big.motions[0].shape.magnitude <= io.github.psd2live.core.RigSwingEdit.MAX_MAGNITUDE, "held at the limit")
		val wild = RigSwings.shape(draft, 0) { it.copy(lift = 3f, softness = -1f) }
		assertTrue(wild.motions[0].shape.lift <= 0.5f && wild.motions[0].shape.softness >= 0f, "every value held")
		val nan = RigSwings.shape(draft, 0) { it.copy(magnitude = Float.NaN) }
		assertEquals(draft.motions[0].shape.magnitude, nan.motions[0].shape.magnitude, "a bad number keeps the old value")
	}
}
