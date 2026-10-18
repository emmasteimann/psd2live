package io.github.psd2live.ipad

import io.github.psd2live.core.LipSync
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import okio.Path.Companion.toPath
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lip sync on the iPad: a recording read by iOS (AVAudioFile), analysed and baked into a motion's mouth keys. */
class LipSyncIpadTest {
	/** 16 kHz mono 16-bit PCM: 0.4 s of tone, 0.4 s of near-silence, twice. */
	private fun wav(): ByteArray {
		val rate = 16000
		val samples = ShortArray(rate * 16 / 10) { i ->
			val loud = (i / (rate * 4 / 10)) % 2 == 0
			(sin(2 * PI * 220 * i / rate) * (if (loud) 20000 else 60)).toInt().toShort()
		}
		val data = samples.size * 2
		val out = okio.Buffer()
		out.writeUtf8("RIFF").writeIntLe(36 + data).writeUtf8("WAVE")
		out.writeUtf8("fmt ").writeIntLe(16).writeShortLe(1).writeShortLe(1).writeIntLe(rate).writeIntLe(rate * 2).writeShortLe(2).writeShortLe(16)
		out.writeUtf8("data").writeIntLe(data)
		samples.forEach { out.writeShortLe(it.toInt()) }
		return out.readByteArray()
	}

	@Test fun aRecordingBecomesMouthKeys() {
		val path = (okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "lipsync-test.wav")
		okio.FileSystem.SYSTEM.write(path) { write(wav()) }
		val audio = readAudioMono(path.toString())
		assertEquals(1.6f, audio.duration, 0.05f)
		val result = LipSync.analyze(audio, 30f)
		val open = Parameter(ParameterId("ParamMouthOpenY"), "Mouth open", 0f, 1f, 0f)
		val clip = LipSync.bake(MotionClip("m1", "Talk", duration = 1f), result, open, null, path.toString())
		val curve = clip.curve("ParamMouthOpenY")!!
		fun at(t: Float) = MotionClips.sample(curve, t)
		assertTrue(clip.duration >= 1.6f - 0.05f, "the motion grows to the recording: ${clip.duration}")
		assertTrue(at(0.2f) > 0.5f && at(1.0f) > 0.5f, "open while she speaks: ${at(0.2f)}, ${at(1.0f)}")
		assertTrue(at(0.6f) < 0.2f && at(1.4f) < 0.2f, "closed in the pauses: ${at(0.6f)}, ${at(1.4f)}")
		assertEquals(path.toString(), clip.sound)
	}
}
