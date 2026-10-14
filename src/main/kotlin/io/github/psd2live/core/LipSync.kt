package io.github.psd2live.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Layerloom patch (ONE-APP-PLAN S4.9): lip sync from a recording, baked into a motion's mouth curves.
 *
 * Per frame of the motion: loudness (RMS in dB, gated above the recording's own noise floor, normalised to its loud
 * parts, opening fast and closing slower) drives mouth open; brightness (the share of energy in the signal's change
 * from sample to sample, which rises with high frequencies) drives mouth form, so bright "i/e" sounds smile and dark
 * "o/u" sounds round. Keys are thinned where the curve stays straight.
 */
object LipSync {
	/** Mono samples in -1..1 and their rate. */
	class Audio(val samples: FloatArray, val rate: Int) {
		val duration: Float get() = samples.size.toFloat() / rate
	}

	/** Mouth open (0..1) and form (-1..1) per frame at [fps], from 0. */
	class Result(val fps: Float, val open: FloatArray, val form: FloatArray) {
		val duration: Float get() = open.size / fps
	}

	/** [file] as mono audio: a PCM WAV is read as it is; anything else macOS can read is converted first (afconvert). */
	fun read(file: Path): Audio {
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

	/** A RIFF WAV: 16-bit or 24-bit PCM, or 32-bit float, mixed down to mono. */
	fun readWav(bytes: ByteArray): Audio {
		val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		require(bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Not a WAV file" }
		var format = 0; var channels = 0; var rate = 0; var bits = 0
		var at = 12
		while (at + 8 <= bytes.size) {
			val id = String(bytes, at, 4); val size = b.getInt(at + 4); val body = at + 8
			when (id) {
				"fmt " -> { format = b.getShort(body).toInt() and 0xffff; channels = b.getShort(body + 2).toInt(); rate = b.getInt(body + 4); bits = b.getShort(body + 14).toInt()
					if (format == 0xFFFE && size >= 26) format = b.getShort(body + 24).toInt() and 0xffff }
				"data" -> {
					require(channels > 0 && rate > 0) { "WAV has no format" }
					val width = bits / 8
					val frames = min(size, bytes.size - body) / (width * channels)
					val out = FloatArray(frames)
					for (f in 0 until frames) {
						var sum = 0f
						for (c in 0 until channels) {
							val p = body + (f * channels + c) * width
							sum += when {
								format == 3 && bits == 32 -> b.getFloat(p)
								format == 1 && bits == 16 -> b.getShort(p) / 32768f
								format == 1 && bits == 24 -> ((bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8) or (bytes[p + 2].toInt() shl 16)) / 8388608f
								format == 1 && bits == 32 -> b.getInt(p) / 2147483648f
								else -> throw IllegalArgumentException("Unsupported WAV encoding: format $format, $bits bits")
							}
						}
						out[f] = sum / channels
					}
					return Audio(out, rate)
				}
			}
			at = body + size + (size and 1)
		}
		throw IllegalArgumentException("WAV has no audio data")
	}

	fun analyze(audio: Audio, fps: Float): Result {
		val n = max(1, ceil(audio.duration * fps).toInt())
		val hop = audio.rate / fps
		val window = (hop * 2).toInt().coerceAtLeast(32)
		val db = FloatArray(n); val bright = FloatArray(n)
		for (i in 0 until n) {
			val center = (i * hop).toInt()
			val from = (center - window / 2).coerceAtLeast(1); val to = (center + window / 2).coerceAtMost(audio.samples.size)
			var energy = 0.0; var change = 0.0
			for (k in from until to) {
				val x = audio.samples[k]; val d = x - audio.samples[k - 1]
				energy += x * x; change += d * d
			}
			val count = max(1, to - from)
			val rms = sqrt(energy / count)
			db[i] = (20 * log10(rms + 1e-9)).toFloat()
			bright[i] = if (energy > 1e-12) (change / (energy * 2)).toFloat() else 0f
		}
		// The recording's own floor and loud level: robust percentiles, not the extremes.
		val sorted = db.sorted()
		val peak = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
		// Digital silence would drag the floor so low that quiet tails count as talking; 40 dB under the loud level is the most.
		val floor = max(sorted[(sorted.size * 0.10).toInt().coerceAtMost(sorted.size - 1)] + 6f, peak - 40f)
		val loud = max(peak, floor + 12f)
		val open = FloatArray(n)
		var level = 0f
		for (i in 0 until n) {
			val target = ((db[i] - floor) / (loud - floor)).coerceIn(0f, 1f).let { Math.pow(it.toDouble(), 0.8).toFloat() }
			// Opens fast, closes slower, as a mouth does.
			level += (target - level) * (if (target > level) 0.65f else 0.35f)
			open[i] = level
		}
		val voiced = (0 until n).filter { open[it] > 0.15f }
		val mean = if (voiced.isEmpty()) 0f else voiced.map { bright[it] }.average().toFloat()
		val spread = if (voiced.size < 2) 1f else sqrt(voiced.map { (bright[it] - mean).let { d -> d * d } }.average()).toFloat().coerceAtLeast(1e-4f)
		val form = FloatArray(n)
		var f = 0f
		for (i in 0 until n) {
			val target = if (open[i] > 0.15f) tanh(((bright[i] - mean) / spread) * 0.8f) else 0f
			f += (target - f) * 0.4f
			form[i] = f
		}
		return Result(fps, open, form)
	}

	/** Keys for [values] (one per frame), thinned where the curve stays within [tolerance] of a straight line. */
	fun keys(values: FloatArray, fps: Float, tolerance: Float, scale: (Float) -> Float): List<MotionKey> {
		if (values.isEmpty()) return emptyList()
		val keep = BooleanArray(values.size); keep[0] = true; keep[values.size - 1] = true
		fun simplify(a: Int, b: Int) {
			if (b - a < 2) return
			var worst = -1; var worstError = tolerance
			for (i in a + 1 until b) {
				val expected = values[a] + (values[b] - values[a]) * (i - a) / (b - a)
				val error = abs(values[i] - expected)
				if (error > worstError) { worstError = error; worst = i }
			}
			if (worst >= 0) { keep[worst] = true; simplify(a, worst); simplify(worst, b) }
		}
		simplify(0, values.size - 1)
		return values.indices.filter { keep[it] }.map { MotionKey(it / fps, scale(values[it]), MotionInterpolation.LINEAR) }
	}

	/**
	 * [clip] with its [openParameter] and [formParameter] curves replaced by [result] (form only when given), its
	 * duration grown to fit and its sound set to [sound]. Values land in each parameter's range.
	 */
	fun bake(clip: MotionClip, result: Result, openParameter: org.umamo.runtime.model.Parameter,
	         formParameter: org.umamo.runtime.model.Parameter?, sound: String?): MotionClip {
		val duration = max(clip.duration, ceil(result.duration * clip.fps) / clip.fps)
		val open = MotionCurve(openParameter.id.raw, keys(result.open, result.fps, 0.04f) { v ->
			(openParameter.min + v * (openParameter.max - openParameter.min)).coerceIn(openParameter.min, openParameter.max) })
		val form = formParameter?.let { p ->
			val half = min(p.max - p.default, p.default - p.min).coerceAtLeast(0f)
			MotionCurve(p.id.raw, keys(result.form, result.fps, 0.06f) { v -> (p.default + v * half * 0.8f).coerceIn(p.min, p.max) })
		}
		val replaced = setOfNotNull(open.parameterId, form?.parameterId)
		return clip.copy(duration = duration, sound = sound,
			curves = clip.curves.filter { it.parameterId !in replaced } + listOfNotNull(open, form))
	}
}
