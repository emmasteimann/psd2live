package io.github.psd2live.ui.state

import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

/**
 * Layerloom patch (ONE-APP-PLAN S4.9): a motion's sound played while the animation editor plays the motion. Java plays
 * WAV and AIFF itself; anything else is converted to WAV once (afconvert) and kept for the session.
 */
object MotionAudio {
	private var clip: Clip? = null
	private val converted = HashMap<String, File>()

	@Synchronized
	fun play(path: String?, fromSeconds: Float) {
		stop()
		val source = path?.let(::File)?.takeIf { it.isFile } ?: return
		runCatching {
			val playable = if (source.extension.lowercase() in setOf("wav", "aif", "aiff")) source else converted.getOrPut(source.absolutePath) {
				File.createTempFile("motion-sound", ".wav").also { out ->
					out.deleteOnExit()
					ProcessBuilder("afconvert", "-f", "WAVE", "-d", "LEI16", source.absolutePath, out.absolutePath).redirectErrorStream(true).start().waitFor()
				}
			}
			val next = AudioSystem.getClip()
			AudioSystem.getAudioInputStream(playable).use { next.open(it) }
			next.microsecondPosition = (fromSeconds.coerceAtLeast(0f) * 1_000_000).toLong().coerceAtMost(next.microsecondLength)
			next.start()
			clip = next
		}.onFailure { System.err.println("Motion sound: ${it.message}") }
	}

	@Synchronized
	fun stop() {
		clip?.let { runCatching { it.stop(); it.close() } }
		clip = null
	}
}
