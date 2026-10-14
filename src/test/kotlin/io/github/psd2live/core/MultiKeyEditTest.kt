package io.github.psd2live.core

import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.canvasGeometryCommand
import org.umamo.runtime.model.Deformer
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Layerloom patch (ONE-APP-PLAN S4.7): multiple-keys editing, on a real rig (LAYERLOOM_PARITY_DIR; skipped without it). */
class MultiKeyEditTest {
	@Test
	fun aNudgeAtOneKeyLandsAtEveryKey() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val puppet = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd")).rig.puppet
		val warp = puppet.deformers.filterIsInstance<Deformer.Warp>().first { it.name == "Face Warp Deformer" }
		val axes = warp.geometryGrid!!.axes
		val defaults = puppet.parameters.associate { it.id.raw to it.default }
		val key = axes.associate { it.parameterId.raw to (defaults[it.parameterId.raw] ?: 0f) }
		val before = RigGeometryTools.geometry(puppet, "warp", warp.id.raw, defaults + key).points
		val moved = before.copyOf().also { it[10] += 0.02f; it[11] -= 0.01f }
		val commands = MultiKeyEdit.expand(puppet, listOf(canvasGeometryCommand(EditHierarchyMode.DEFORM, "warp", warp.id.raw, key, moved)))
		val keyCount = axes.fold(1) { n, a -> n * a.keys.size }
		assertEquals(keyCount, commands.size, "one edit per key of the grid")
		val edited = RigAuthoringJournal.compile(puppet, kotlinx.serialization.json.JsonArray(commands)).first
		var checked = 0
		for (axis in axes) for (k in axis.keys) {
			val pose = defaults + key + (axis.parameterId.raw to k)
			val was = RigGeometryTools.geometry(puppet, "warp", warp.id.raw, pose).points
			val now = RigGeometryTools.geometry(edited, "warp", warp.id.raw, pose).points
			assertEquals(0.02f, now[10] - was[10], 1e-4f, "${axis.parameterId.raw}=$k: x moved by the nudge")
			assertEquals(-0.01f, now[11] - was[11], 1e-4f)
			assertTrue(now.indices.filter { it != 10 && it != 11 }.all { abs(now[it] - was[it]) < 1e-4f }, "other points stay")
			checked++
		}
		println("checked $checked keys of $keyCount on ${axes.map { it.parameterId.raw }}")
		val single = RigAuthoringJournal.compile(puppet, kotlinx.serialization.json.JsonArray(listOf(commands.first()))).first
		val turned = defaults + key + (axes.first().parameterId.raw to axes.first().keys.last())
		assertTrue(abs(RigGeometryTools.geometry(single, "warp", warp.id.raw, turned).points[10] - RigGeometryTools.geometry(puppet, "warp", warp.id.raw, turned).points[10]) < 1e-4f,
			"without it, other keys keep their form")
	}
}
