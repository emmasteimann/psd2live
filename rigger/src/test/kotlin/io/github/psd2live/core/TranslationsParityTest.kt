package io.github.psd2live.core

import io.github.psd2live.i18n.formatMessage
import io.github.psd2live.i18n.parseProperties
import java.io.File
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The iPad reads and formats the desktop's bundles with shared code: it must give what Properties and MessageFormat give. */
class TranslationsParityTest {
	private val bundles = File("src/main/resources/i18n").listFiles { f -> f.name.endsWith(".properties") }!!.sorted()
	private val locales = mapOf("Messages.properties" to Locale.ENGLISH, "Messages_ja.properties" to Locale.JAPANESE,
		"Messages_ko.properties" to Locale.KOREAN, "Messages_zh_CN.properties" to Locale.SIMPLIFIED_CHINESE)

	@Test
	fun propertiesReadAsJavaReadsThem() {
		for (file in bundles) {
			val java = Properties().apply { file.reader(Charsets.UTF_8).use(::load) }
			val ours = parseProperties(file.readText(Charsets.UTF_8))
			assertEquals(java.size, ours.size, file.name)
			for ((k, v) in java) assertEquals(v, ours[k], "${file.name}: $k")
		}
	}

	@Test
	fun everyMessageFormatsAsMessageFormat() {
		val random = Random(3)
		val samples = listOf<Any?>(0, 7, 1234, -98765, 1234567L, 0.5, 2.5, 1.0005, 12345.6785, -0.0004, 3.14159f, 99.9995, 1e-7, 1e15,
			"quill.psd", "ArtMesh 3", null)
		var checked = 0
		for (file in bundles) {
			val locale = locales.getValue(file.name)
			for ((key, pattern) in parseProperties(file.readText(Charsets.UTF_8))) {
				if ('{' !in pattern) continue
				repeat(6) {
					val args = Array(4) { samples[random.nextInt(samples.size)] }
					val java = runCatching { MessageFormat(pattern, locale).format(args) }
					val ours = runCatching { formatMessage(pattern, args) }
					// A pattern MessageFormat rejects ({key:...} markup) must be rejected here too.
					if (java.isFailure) assertEquals(true, ours.exceptionOrNull() is IllegalArgumentException, "${file.name}: $key should fail")
					else assertEquals(java.getOrThrow(), ours.getOrThrow(), "${file.name}: $key with ${args.toList()}")
					checked++
				}
			}
		}
		check(checked > 1000) { "only $checked formatted" }
	}

	@Test
	fun numbersRoundAsDecimalFormat() {
		val random = Random(5)
		for (d in listOf(0.0005, -0.0005, 0.5, 1.5, 2.5, -0.5, 0.0015, 0.00051, 99.9995, 81.0185)) {
			assertEquals(MessageFormat("{0} {0,number,#}", Locale.ENGLISH).format(arrayOf(d)), formatMessage("{0} {0,number,#}", arrayOf(d)), "$d")
		}
		repeat(100000) {
			val d = when (it % 5) { 4 -> random.nextInt(-3000, 3000) / 10000.0 + 0.00005; 0 -> random.nextDouble(-1e6, 1e6); 1 -> random.nextInt(-100000, 100000) / 1000.0 + 0.0005; 2 -> random.nextDouble(); else -> random.nextInt() / 8.0 }
			assertEquals(MessageFormat("{0} {0,number,#}", Locale.ENGLISH).format(arrayOf(d)), formatMessage("{0} {0,number,#}", arrayOf(d)), "$d")
		}
	}
}
