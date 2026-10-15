package io.github.psd2live.targets.cubism

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared plainDecimal writes exponent numbers exactly as java.math.BigDecimal's toPlainString did. */
class PlainDecimalTest {
	@Test
	fun `matches BigDecimal toPlainString`() {
		val samples = listOf("1.5E-7", "1.0E10", "2.50E-3", "-3.25E-5", "9.999E2", "1E-1", "1.0E-1", "-1.0E0", "4.2E+3", "7.0E-12", "1.2345E-4")
		val random = Random(7)
		val generated = List(2000) {
			val value = random.nextDouble(-1e3, 1e3) * Math.pow(10.0, random.nextInt(-12, 12).toDouble())
			value.toString().takeIf { 'E' in it } ?: "${value}E${random.nextInt(-9, 9)}"
		}
		for (text in samples + generated) assertEquals(java.math.BigDecimal(text).toPlainString(), plainDecimal(text), text)
	}
}
