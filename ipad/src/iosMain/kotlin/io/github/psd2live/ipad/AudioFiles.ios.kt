package io.github.psd2live.ipad

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.Foundation.NSURL

/** AVAudioFile decodes whatever iOS can play; the channels are averaged to one, as the desktop's afconvert does. */
@OptIn(ExperimentalForeignApi::class)
actual fun readAudioMono(path: String): io.github.psd2live.core.LipSync.Audio {
	val file = AVAudioFile(forReading = NSURL.fileURLWithPath(path), error = null)
	val format = file.processingFormat
	val frames = file.length
	require(frames > 0) { "That recording is empty" }
	val buffer = requireNotNull(AVAudioPCMBuffer(pCMFormat = format, frameCapacity = frames.toUInt())) { "Couldn't read that recording" }
	require(file.readIntoBuffer(buffer, error = null)) { "Couldn't read that recording" }
	val data = requireNotNull(buffer.floatChannelData) { "That recording isn't in a readable format" }
	val channels = format.channelCount.toInt().coerceAtLeast(1)
	val n = buffer.frameLength.toInt()
	val samples = FloatArray(n)
	for (c in 0 until channels) {
		val channel = data[c] ?: continue
		for (i in 0 until n) samples[i] += channel[i] / channels
	}
	return io.github.psd2live.core.LipSync.Audio(samples, format.sampleRate.toInt())
}
