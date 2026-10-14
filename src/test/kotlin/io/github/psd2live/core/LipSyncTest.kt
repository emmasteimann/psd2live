package io.github.psd2live.core

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Layerloom patch (ONE-APP-PLAN S4.9): lip sync from audio. */
class LipSyncTest {
	@Test
	fun wavReadsAndThinningKeepsTheShape() {
		// A 16-bit mono WAV: 0.5 s of a 220 Hz tone at 8 kHz.
		val rate = 8000; val n = rate / 2
		val pcm = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
		for (i in 0 until n) pcm.putShort((kotlin.math.sin(i * 2 * Math.PI * 220 / rate) * 16000).toInt().toShort())
		val wav = wav(pcm.array(), rate)
		val audio = LipSync.readWav(wav)
		assertEquals(rate, audio.rate); assertEquals(n, audio.samples.size)
		assertEquals(0.488f, audio.samples.maxOrNull()!!, 0.01f)
		val keys = LipSync.keys(floatArrayOf(0f, 0.5f, 1f, 1f, 1f, 0f), 30f, 0.01f) { it }
		assertEquals(listOf(0f, 1f, 1f, 0f), keys.map { it.value }, "the ramp's middle key and the hold's middle are dropped")
	}

	/** Real speech from macOS `say` (skipped where it's missing): closed in the pause, moving while talking. */
	@Test
	fun speechOpensTheMouthAndThePauseClosesIt() {
		val dir = Files.createTempDirectory("lipsync")
		val aiff = dir.resolve("speech.aiff")
		val made = runCatching {
			ProcessBuilder("say", "-o", aiff.toString(), "Hello, I am Quill. [[slnc 1200]] Nice to meet you, everyone!").start().waitFor() == 0
		}.getOrDefault(false)
		if (!made) return
		val audio = LipSync.read(aiff)
		val result = LipSync.analyze(audio, 30f)
		println("duration %.2fs, frames %d".format(audio.duration, result.open.size))
		// The longest run of closed frames is the 1.2 s pause.
		var best = 0; var run = 0
		for (v in result.open) { run = if (v < 0.08f) run + 1 else 0; best = maxOf(best, run) }
		println("longest closed run: $best frames; mean open while talking ${result.open.filter { it > 0.08f }.average()}")
		assertTrue(best in 25..45, "a closed stretch about as long as the 1.2 s pause")
		assertTrue(result.open.count { it > 0.5f } > result.open.size / 5, "the mouth opens well while talking")
		assertTrue(result.form.any { it > 0.2f } && result.form.any { it < -0.2f }, "form moves both ways")
		// Baked into a clip, in range, covering the audio.
		val open = Parameter(ParameterId("ParamMouthOpenY"), "Mouth Open", 0f, 1f, 0f)
		val form = Parameter(ParameterId("ParamMouthForm"), "Mouth Form", -1f, 1f, 0f)
		val clip = LipSync.bake(MotionClip("motion_1", "Hello", duration = 1f), result, open, form, aiff.toString())
		assertTrue(clip.duration >= audio.duration)
		assertEquals(aiff.toString(), clip.sound)
		val keys = clip.curve("ParamMouthOpenY")!!.keys
		assertTrue(keys.size < result.open.size, "thinned: ${keys.size} keys for ${result.open.size} frames")
		assertTrue(keys.all { it.value in 0f..1f } && clip.curve("ParamMouthForm")!!.keys.all { it.value in -0.8f..0.8f })
	}

	/** A real rig (LAYERLOOM_PARITY_DIR; skipped without it): the motion's sound travels with the Cubism export. */
	@Test
	fun soundIsCopiedAndNamedInModel3() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val audio = Files.createTempFile("voice", ".wav")
		Files.write(audio, wav(ByteArray(1600), 8000))
		val base = PSD2LivePipeline().buildPreview(java.nio.file.Path.of(dir, "quill.psd"))
		val clip = MotionClip("motion_1", "Hello", curves = listOf(MotionCurve("ParamMouthOpenY", listOf(MotionKey(0f, 0f), MotionKey(1f, 1f)))),
			sound = audio.toString())
		val model = PSD2LivePipeline().buildPreview(java.nio.file.Path.of(dir, "quill.psd"), base.config.copy(rigEdits = base.config.rigEdits.copy(motionClips = listOf(clip))))
		val files = io.github.psd2live.targets.cubism.Moc3Target.bundle(RigIrCompiler.compile(model), io.github.psd2live.format.compile.ExportOptions("quill"))
			.files.associate { it.name to it.bytes }
		val sound = files.keys.single { it.startsWith("sounds/") }
		assertTrue(sound.endsWith(".wav") && files.getValue(sound).size == Files.size(audio).toInt())
		val model3 = kotlinx.serialization.json.Json.parseToJsonElement(files.getValue("quill.model3.json").decodeToString()).let { it as kotlinx.serialization.json.JsonObject }
		val motions = (model3["FileReferences"] as kotlinx.serialization.json.JsonObject)["Motions"].toString()
		assertTrue("\"Sound\":\"$sound\"" in motions.replace(" ", ""), motions)
	}

	private fun wav(pcm: ByteArray, rate: Int): ByteArray {
		val b = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
		b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
		b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
		b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
		return b.array()
	}
}
