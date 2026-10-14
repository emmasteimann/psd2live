package io.github.psd2live.core

import io.github.psd2live.application.WorkspaceSkeletonMotionEdits
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.targets.cubism.Moc3Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Layerloom patch (ONE-APP-PLAN S4.8): part opacity tracks, on a real rig (LAYERLOOM_PARITY_DIR; skipped without it). */
class PartTracksTest {
	@Test
	fun partTracksEditExportAndFadeThePreview() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val base = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"))
		val puppet = base.rig.puppet
		val head = puppet.parts.first { it.name == "Head" }
		val track = PartTracks.trackOf(head.id.raw)
		val ranges = puppet.parameters.associate { it.id.raw to (it.min..it.max) } + PartTracks.ranges(puppet)

		// Keys go through the ordinary motion edits; 0..1 is the range.
		var clips = listOf(MotionClip("motion_1", "Hat off", duration = 1f))
		fun key(t: Float, v: Float) { clips = WorkspaceSkeletonMotionEdits.motion(clips, buildJsonObject {
			put("mode", "set_key"); put("id", "motion_1"); put("parameter", track)
			put("key", buildJsonObject { put("time", t); put("value", v) })
		}, ranges, null) }
		key(0f, 1f); key(1f, 0f)
		assertEquals(listOf(1f, 0f), clips.single().curves.single().keys.map { it.value })
		assertFailsWith<IllegalArgumentException> { key(0.5f, 2f) }

		// Reconcile keeps it while the part exists and drops it when it doesn't.
		assertEquals(clips, MotionClips.reconcileParameters(clips, puppet.parameters, puppet.parts.mapTo(HashSet()) { it.id.raw }))
		assertTrue(MotionClips.reconcileParameters(clips, puppet.parameters, emptySet()).single().curves.isEmpty())

		// Export: a motion with only a part track is written, as a PartOpacity curve.
		val model = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"), base.config.copy(rigEdits = base.config.rigEdits.copy(motionClips = clips)))
		val files = Moc3Target.bundle(RigIrCompiler.compile(model), ExportOptions("quill")).files.associate { it.name to it.bytes.decodeToString() }
		val motion = files.entries.single { it.key.endsWith(".motion3.json") && "PartOpacity" in it.value }
		val curve = Json.parseToJsonElement(motion.value).jsonObject["Curves"]!!.jsonArray.single { it.jsonObject["Target"]!!.jsonPrimitive.content == "PartOpacity" }.jsonObject
		assertEquals(head.id.raw, curve["Id"]!!.jsonPrimitive.content)

		// Preview: every mesh under Head (nested parts too) at half opacity, the rest untouched.
		val pose = mapOf(ParameterId(track) to 0.5f)
		val geometry = RigCanvasSupport.evaluate(model, pose)
		val faded = PartTracks.applied(model.rig.puppet, pose, geometry.opacity, { org.umamo.runtime.model.DrawableId(it) }, { 1f })
		val byId = model.rig.puppet.parts.associateBy { it.id }
		fun under(p: org.umamo.runtime.model.Part): Set<String> = p.children.flatMap { c -> when (c) {
			is OrgChild.Drawable -> listOf(c.id.raw); is OrgChild.Part -> byId[c.id]?.let(::under).orEmpty() } }.toSet()
		val headMeshes = under(model.rig.puppet.parts.first { it.id == head.id })
		assertTrue(headMeshes.isNotEmpty())
		for ((id, value) in faded) {
			val before = geometry.opacity[id] ?: 1f
			if (id.raw in headMeshes) assertEquals(before * 0.5f, value, 1e-5f, "${id.raw} fades") else assertEquals(before, value, 1e-6f)
		}
		println("Head fades ${headMeshes.size} meshes")
	}
}
