package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The iPad's parameter editing on real art (Boo; skipped without the file): keys, ranges, new and deleted parameters, undo, and a re-rig keeps it. */
class RigParametersTest {
	private val path = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"
	private fun rig(): Pair<org.umamo.format.art.SourceArt, io.github.psd2live.core.RigPreviewModel>? {
		val p = okio.Path.Companion.run { path.toPath() }
		if (!okio.FileSystem.SYSTEM.exists(p)) return null
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(p) { readByteArray() })
		return source to PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false))
	}

	@Test fun keysRangesAndNewParametersStickAndUndo() {
		val (source, model) = rig() ?: return
		val e = RigEditing(model)
		val angle = RigParameters.list(e).first { it.id == "ParamAngleX" }
		assertTrue(angle.keys.size >= 3, "the head turn has keys: ${angle.keys}")
		assertTrue(!angle.created)

		// A key in between, then moved, then deleted.
		RigParameters.keys(e, "ParamAngleX", "add", listOf(15f))
		assertTrue(15f in RigParameters.list(e).first { it.id == "ParamAngleX" }.keys)
		RigParameters.keys(e, "ParamAngleX", "move", listOf(20f), from = 15f)
		val moved = RigParameters.list(e).first { it.id == "ParamAngleX" }.keys
		assertTrue(20f in moved && 15f !in moved, "moved: $moved")
		RigParameters.keys(e, "ParamAngleX", "delete", listOf(20f))
		assertTrue(20f !in RigParameters.list(e).first { it.id == "ParamAngleX" }.keys)

		// A new parameter, renamed and re-ranged, then undo walks back.
		val id = RigParameters.create(e, "Lantern swing", -1f, 1f, 0f, blendShape = false)
		var made = RigParameters.list(e).first { it.id == id }
		assertTrue(made.created); assertEquals("Lantern swing", made.name)
		RigParameters.update(e, made, "Lantern sway", -2f, 2f, 0f, blendShape = false, repeat = true)
		made = RigParameters.list(e).first { it.id == id }
		assertEquals("Lantern sway", made.name); assertEquals(-2f, made.min); assertTrue(made.repeat)
		// A range that would cut off a key is refused with a reason.
		assertFailsWith<IllegalArgumentException> { RigParameters.update(e, RigParameters.list(e).first { it.id == "ParamAngleX" }, "Angle X", -10f, 10f, 0f, false, false) }

		// The edits survive a full re-rig from the same config (as opening the project does).
		val rerig = PSD2LivePipeline().buildPreview(source, e.model.config)
		assertTrue(rerig.rig.puppet.parameters.any { it.id.raw == id && it.name == "Lantern sway" }, "re-rig keeps the new parameter")
		RigParameters.delete(e, RigParameters.list(e).first { it.id == id })
		assertTrue(RigParameters.list(e).none { it.id == id })
		e.undo()
		assertTrue(RigParameters.list(e).any { it.id == id }, "undo brings it back")
	}
}
