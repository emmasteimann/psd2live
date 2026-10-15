package io.github.psd2live.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The shared Math and BitSet stand in for java.lang's and java.util's in the rigger, so they must agree bit for bit. */
class JavaLangParityTest {
	private val random = Random(7)
	private val edgeDoubles = doubleArrayOf(0.0, -0.0, 0.5, -0.5, 1.5, -1.5, 2.5, -2.5, 0.49999999999999994, -0.49999999999999994,
		Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE,
		9.007199254740993E15, 4.503599627370497E15, 1e19, -1e19, 1e-300)
	private val edgeFloats = floatArrayOf(0f, -0f, 0.5f, -0.5f, 1.5f, -2.5f, 0.49999997f, -0.49999997f, Float.NaN,
		Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, 8388609f, 3e9f, -3e9f, 1e-30f)

	private fun doubles() = edgeDoubles.asList() + List(20000) {
		when (it % 3) { 0 -> random.nextDouble(-1e4, 1e4); 1 -> Double.fromBits(random.nextLong()); else -> random.nextInt(-1000, 1000) + 0.5 }
	}

	private fun floats() = edgeFloats.asList() + List(20000) {
		when (it % 3) { 0 -> random.nextDouble(-1e4, 1e4).toFloat(); 1 -> Float.fromBits(random.nextInt()); else -> random.nextInt(-1000, 1000) + 0.5f }
	}

	@Test
	fun roundAnglesAndExponentsMatchJava() {
		for (d in doubles()) {
			assertEquals(java.lang.Math.round(d), Math.round(d), "round($d)")
			assertEquals(java.lang.Math.toRadians(d).toRawBits(), Math.toRadians(d).toRawBits(), "toRadians($d)")
			assertEquals(java.lang.Math.toDegrees(d).toRawBits(), Math.toDegrees(d).toRawBits(), "toDegrees($d)")
			assertEquals(java.lang.Math.getExponent(d), Math.getExponent(d), "getExponent($d)")
			assertEquals(java.lang.Math.nextUp(d).toRawBits(), Math.nextUp(d).toRawBits(), "nextUp($d)")
			assertEquals(java.lang.Math.copySign(d, -d).toRawBits(), Math.copySign(d, -d).toRawBits(), "copySign($d)")
		}
		for (f in floats()) {
			assertEquals(java.lang.Math.round(f), Math.round(f), "round($f)")
			assertEquals(java.lang.Math.getExponent(f), Math.getExponent(f), "getExponent($f)")
			assertEquals(java.lang.Math.nextUp(f).toRawBits(), Math.nextUp(f).toRawBits(), "nextUp($f)")
		}
		for (n in -1100..1100) {
			assertEquals(java.lang.Math.scalb(1.0, n).toRawBits(), Math.scalb(1.0, n).toRawBits(), "scalb(1, $n)")
			assertEquals(java.lang.Math.scalb(1.5f, n.coerceIn(-150, 150)).toRawBits(), Math.scalb(1.5f, n.coerceIn(-150, 150)).toRawBits(), "scalb(1.5f, $n)")
		}
	}

	@Test
	fun exactArithmeticMatchesJava() {
		repeat(20000) {
			val a = random.nextLong(); val b = if (it % 2 == 0) random.nextLong(-4_000_000_000, 4_000_000_000) else random.nextLong()
			assertEquals(runCatching { java.lang.Math.multiplyExact(a, b) }.getOrNull(), runCatching { Math.multiplyExact(a, b) }.getOrNull())
			val x = random.nextInt(); val y = random.nextInt(-70000, 70000)
			assertEquals(runCatching { java.lang.Math.multiplyExact(x, y) }.getOrNull(), runCatching { Math.multiplyExact(x, y) }.getOrNull())
			if (y != 0) {
				assertEquals(java.lang.Math.floorDiv(x, y), Math.floorDiv(x, y))
				assertEquals(java.lang.Math.floorMod(x, y), Math.floorMod(x, y))
			}
		}
		assertEquals(null, runCatching { Math.multiplyExact(Long.MIN_VALUE, -1L) }.getOrNull())
	}

	@Test
	fun atlasPngChecksumsMatchZip() {
		repeat(50) {
			val data = random.nextBytes(random.nextInt(0, 20000))
			assertEquals(java.util.zip.CRC32().apply { update(data) }.value, AtlasPagePng.crc32(data))
			assertEquals(java.util.zip.Adler32().apply { update(data) }.value.toInt(), AtlasPagePng.adler32(data))
		}
		val head = "IDAT".encodeToByteArray(); val body = random.nextBytes(999)
		assertEquals(java.util.zip.CRC32().apply { update(head); update(body) }.value, AtlasPagePng.crc32(body, AtlasPagePng.crc32(head)))
	}

	@Test
	fun bitSetBytesRoundTrip() {
		val set = BitSet(300)
		for (i in listOf(0, 5, 63, 64, 127, 200, 299)) set.set(i)
		set.set(70, 90)
		assertContentEquals(set.toByteArray(), bitSetOf(set.toByteArray()).toByteArray())
		assertEquals(set, bitSetOf(set.toByteArray()))
		assertEquals(set, set.copy())
	}
}
