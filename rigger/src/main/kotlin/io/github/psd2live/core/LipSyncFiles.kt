package io.github.psd2live.core

import java.nio.file.Files
import java.nio.file.Path

// The desktop's side of lip sync: reading audio files (macOS afconvert for what is not a WAV). Analysis is shared.

/** [file] as mono audio: a PCM WAV is read as it is; anything else macOS can read is converted first (afconvert). */
fun LipSync.read(file: Path): LipSync.Audio {
	runCatching { return readWav(Files.readAllBytes(file)) }
	val wav = Files.createTempFile("lipsync", ".wav")
	try {
		val process = ProcessBuilder("afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", file.toString(), wav.toString())
			.redirectErrorStream(true).start()
		val output = process.inputStream.readAllBytes().decodeToString()
		require(process.waitFor() == 0) { "Couldn't read ${file.fileName} as audio: ${output.trim().take(200)}" }
		return readWav(Files.readAllBytes(wav))
	} finally { Files.deleteIfExists(wav) }
}
