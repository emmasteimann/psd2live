package io.github.psd2live.core

import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.targets.cubism.Moc3Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Layerloom patch: motion user-data events. */
class MotionEventsTest {
	private val curve = MotionCurve("ParamAngleX", listOf(MotionKey(0f, 0f), MotionKey(1f, 30f)))

	@Test
	fun textIsUpstreamsWithoutEventsAndJsonRoundTrips() {
		val clip = MotionClip("motion_1", "Wave", curves = listOf(curve))
		// The data class's generated text, as upstream's MotionClip has it.
		assertEquals("MotionClip(id=motion_1, name=Wave, builtin=null, loop=false, duration=2.0, fps=30.0, fadeIn=1.0, fadeOut=1.0, " +
			"enabled=true, curves=${listOf(curve)})", clip.toString())
		assertTrue("events" !in MotionClips.toJson(clip).keys, "no events key until there are events")
		val withEvents = clip.copy(events = listOf(MotionEvent(0.5f, "wave_start"), MotionEvent(1.5f, "wave_end")))
		assertEquals(withEvents, MotionClips.fromJson(MotionClips.toJson(withEvents)))
		val withMarkers = withEvents.copy(markers = listOf(MotionMarker(0.25f, "Raise"), MotionMarker(1f, "Hold")))
		assertEquals(withMarkers, MotionClips.fromJson(MotionClips.toJson(withMarkers)))
		assertTrue("markers=" in withMarkers.toString() && "markers" !in clip.toString())
	}

	/** A real rig (LAYERLOOM_PARITY_DIR; skipped without it): the events reach motion3 UserData. */
	@Test
	fun eventsExportAsMotion3UserData() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val base = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"))
		val clip = MotionClip("motion_1", "Wave", curves = listOf(curve), events = listOf(MotionEvent(0.5f, "wave_start"), MotionEvent(9f, "past the end")))
		val model = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"), base.config.copy(rigEdits = base.config.rigEdits.copy(motionClips = listOf(clip))))
		val files = Moc3Target.bundle(RigIrCompiler.compile(model), ExportOptions("quill")).files.associate { it.name to it.bytes.decodeToString() }
		val motion = files.entries.first { it.key.endsWith(".motion3.json") && "Wave" in it.value.substringBefore("Curves") || it.key.contains("wave", true) }
		val json = Json.parseToJsonElement(motion.value).jsonObject
		val userData = json["UserData"]!!.jsonArray.map { it.jsonObject["Value"]!!.jsonPrimitive.content }
		assertEquals(listOf("wave_start"), userData, "events within the motion's length (${motion.key})")
	}
}
