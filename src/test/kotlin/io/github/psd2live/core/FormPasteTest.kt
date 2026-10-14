package io.github.psd2live.core

import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.canvasGeometryCommand
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.buildDeformerWorlds
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Layerloom patch (ONE-APP-PLAN S4.2): Paste Form / Paste Inverted. */
class FormPasteTest {
	// A symmetric strip about x = 0: (-2,0) (-1,0) (1,0) (2,0); rest is also the canvas-space rest.
	private val rest = floatArrayOf(-2f, 0f, -1f, 0f, 1f, 0f, 2f, 0f)

	@Test
	fun invertedPasteMirrorsOffsetsAcrossTheObject() {
		// Right half pushed right and up: +x at x=1 by 0.2, at x=2 by 0.5.
		val form = floatArrayOf(-2f, 0f, -1f, 0f, 1.2f, 0.1f, 2.5f, 0.3f)
		val out = FormPaste.points("mesh", form, rest, rest, rest, rest, inverted = true, axis = null)
		assertEquals(listOf(-2.5f, 0.3f, -1.2f, 0.1f, 1f, 0f, 2f, 0f), out.toList().map { (it * 1000).toInt() / 1000f })
		assertEquals(form.toList(), FormPaste.points("mesh", form, rest, rest, rest, rest, inverted = false, axis = null).toList())
	}

	@Test
	fun invertedPasteOntoTheOtherSideUsesTheModelAxis() {
		// A left part at x 8..9 copied onto a right part at x 11..12, mirrored about x = 10.
		val leftRest = floatArrayOf(8f, 0f, 9f, 0f); val leftForm = floatArrayOf(7.5f, 1f, 9f, 0f)
		val rightRest = floatArrayOf(11f, 0f, 12f, 0f)
		val out = FormPaste.points("mesh", leftForm, leftRest, leftRest, rightRest, rightRest, inverted = true, axis = 10f)
		assertEquals(listOf(11f, 0f, 12.5f, 1f), out.toList())
	}

	@Test
	fun pasteSpecialBlendsAndKeepsUnselectedPoints() {
		val current = floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f)
		val pasted = floatArrayOf(4f, 2f, 14f, 2f, 24f, 2f)
		assertEquals(listOf(2f, 1f, 12f, 1f, 22f, 1f), FormPaste.special(current, pasted, 0.5f, null).toList())
		assertEquals(listOf(0f, 0f, 14f, 2f, 20f, 0f), FormPaste.special(current, pasted, 1f, setOf(1)).toList())
	}

	@Test
	fun invertedRotationTurnsTheOtherWay() {
		val restRot = floatArrayOf(0f, 0f, 0f, -10f)
		val turned = floatArrayOf(1f, 0f, 1f + 10f * kotlin.math.sin(0.3f), -10f * kotlin.math.cos(0.3f))
		val out = FormPaste.points("rotation", turned, restRot, restRot, restRot, restRot, inverted = true, axis = null)
		assertEquals(-1f, out[0], 1e-5f)
		val angle = atan2(out[2] - out[0], -(out[3] - out[1]))
		assertEquals(-0.3f, angle, 1e-4f)
	}

	/**
	 * A real rig (LAYERLOOM_PARITY_DIR holding quill.psd; skipped without it). The generator builds head turns
	 * symmetric, so the AngleX = +30 form pasted inverted at -30 should land near the generated -30 form, much nearer
	 * than a plain paste; and the committed command replays to exactly the pasted points.
	 */
	@Test
	fun quillHeadTurnPastesMirrored() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val puppet = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd")).rig.puppet
		val angle = "ParamAngleX"
		val defaults = puppet.parameters.associate { it.id.raw to it.default }
		val worlds = buildDeformerWorlds(puppet.deformers, { defaults[it.raw] ?: 0f }, { defaults[it.raw] ?: 0f })
		// The generator keys head turns on warps (meshes ride them), so those are the forms to check.
		val candidates = puppet.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>()
			.filter { w -> w.geometryGrid?.axes?.any { it.parameterId.raw == angle } == true }
		assertTrue(candidates.isNotEmpty(), "some warp is keyed on $angle")
		var checked = 0
		for (w in candidates.sortedByDescending { it.rows * it.columns }.take(6)) {
			val id = w.id.raw
			val mapping = DrawableSpaceMapping(w.parent?.let { worlds[it] })
			val restLocal = RigGeometryTools.geometry(puppet, "warp", id, defaults).points
			val restWorld = mapping.localToWorld(restLocal)
			// A side part turned one way is its other-side counterpart turned the other way, mirrored about the
			// model's centre: copy from that (left eye -> right eye), as a rigger does. A centred part mirrors itself.
			val counterpart = Regex("(?i)\\b(left|right)\\b").find(w.name)?.let { m ->
				val other = if (m.value.equals("left", true)) "Right" else "Left"
				candidates.firstOrNull { it.name.equals(w.name.replaceRange(m.range, other), true) }
			}
			val source = counterpart ?: w
			val sourceMapping = DrawableSpaceMapping(source.parent?.let { worlds[it] })
			val sourceRest = RigGeometryTools.geometry(puppet, "warp", source.id.raw, defaults).points
			val sourceRestWorld = sourceMapping.localToWorld(sourceRest)
			val plus = RigGeometryTools.geometry(puppet, "warp", source.id.raw, defaults + (angle to 30f)).points
			val minus = RigGeometryTools.geometry(puppet, "warp", id, defaults + (angle to -30f)).points
			val moved = plus.indices.sumOf { abs(plus[it] - sourceRest[it]).toDouble() } / plus.size
			if (moved < 1e-4) continue
			val inverted = FormPaste.points("warp", plus, sourceRest, sourceRestWorld, restLocal, restWorld, inverted = true,
				axis = if (counterpart != null) puppet.canvasWidth / 2f else null)
			fun error(a: FloatArray) = a.indices.sumOf { abs(a[it] - minus[it]).toDouble() } / a.size
			val plain = FormPaste.points("warp", plus, sourceRest, sourceRestWorld, restLocal, restWorld, inverted = false, axis = null)
			println("${w.name} from ${source.name}: moved $moved, plain paste off by ${error(plain)}, inverted off by ${error(inverted)}")
			assertTrue(error(inverted) < error(plain) * 0.5, "${w.name}: the mirrored form is near the generated -30 form")
			val replayed = RigAuthoringJournal.apply(puppet, canvasGeometryCommand(EditHierarchyMode.DEFORM, "warp", id,
				w.geometryGrid!!.axes.associate { it.parameterId.raw to (defaults[it.parameterId.raw] ?: 0f) } + (angle to -30f), inverted))
			val after = RigGeometryTools.geometry(replayed, "warp", id, defaults + (angle to -30f)).points
			assertTrue(after.indices.all { abs(after[it] - inverted[it]) < 1e-3f + 1e-3f * abs(inverted[it]) }, "${w.name}: the paste replays exactly")
			checked++
		}
		assertTrue(checked > 0)
	}
}
