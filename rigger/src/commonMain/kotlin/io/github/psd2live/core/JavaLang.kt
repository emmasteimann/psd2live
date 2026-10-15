package io.github.psd2live.core

import kotlin.math.nextUp as kNextUp
import kotlin.math.pow
import kotlin.math.withSign

// java.lang's pieces the rigger was written against, for shared code (Layerloom iPad plan, phase 2). Being in this
// package they take the place of java.lang's in it (other packages import them), on the desktop too, so they must give
// Java's results bit for bit: Math.round is Java's own bit algorithm, toRadians/toDegrees Java's constants.

object Math {
	const val PI: Double = kotlin.math.PI

	private const val DEGREES_TO_RADIANS = 0.017453292519943295
	private const val RADIANS_TO_DEGREES = 57.29577951308232

	fun toRadians(angdeg: Double): Double = angdeg * DEGREES_TO_RADIANS
	fun toDegrees(angrad: Double): Double = angrad * RADIANS_TO_DEGREES

	/** Java's Math.round(float): the closest int, ties toward positive infinity; NaN is 0, out of range saturates. */
	fun round(a: Float): Int {
		val bits = a.toRawBits()
		val shift = (24 - 2 + 127) - ((bits and 0x7F800000) shr 23)
		if (shift and -32 == 0) {
			var r = (bits and 0x007FFFFF) or 0x00800000
			if (bits < 0) r = -r
			return ((r shr shift) + 1) shr 1
		}
		return a.toInt()
	}

	/** Java's Math.round(double). */
	fun round(a: Double): Long {
		val bits = a.toRawBits()
		val shift = (53 - 2 + 1023) - ((bits and 0x7FF0000000000000L) shr 52)
		if (shift and -64L == 0L) {
			var r = (bits and 0x000FFFFFFFFFFFFFL) or 0x0010000000000000L
			if (bits < 0) r = -r
			return ((r shr shift.toInt()) + 1) shr 1
		}
		return a.toLong()
	}

	fun multiplyExact(x: Int, y: Int): Int {
		val r = x.toLong() * y
		if (r.toInt().toLong() != r) throw ArithmeticException("integer overflow")
		return r.toInt()
	}

	fun multiplyExact(x: Long, y: Int): Long = multiplyExact(x, y.toLong())

	fun multiplyExact(x: Long, y: Long): Long {
		val r = x * y
		val ax = kotlin.math.abs(x); val ay = kotlin.math.abs(y)
		if ((ax or ay) ushr 31 != 0L) {
			if ((y != 0L && r / y != x) || (x == Long.MIN_VALUE && y == -1L)) throw ArithmeticException("long overflow")
		}
		return r
	}

	fun floorDiv(x: Int, y: Int): Int = x.floorDiv(y)
	fun floorDiv(x: Long, y: Long): Long = x.floorDiv(y)
	fun floorMod(x: Int, y: Int): Int = x.mod(y)
	fun floorMod(x: Long, y: Long): Long = x.mod(y)

	fun abs(a: Int): Int = kotlin.math.abs(a)
	fun abs(a: Long): Long = kotlin.math.abs(a)
	fun abs(a: Float): Float = kotlin.math.abs(a)
	fun abs(a: Double): Double = kotlin.math.abs(a)

	fun sin(a: Double): Double = kotlin.math.sin(a)
	fun cos(a: Double): Double = kotlin.math.cos(a)
	fun atan(a: Double): Double = kotlin.math.atan(a)
	fun atan2(y: Double, x: Double): Double = kotlin.math.atan2(y, x)
	fun pow(a: Double, b: Double): Double = a.pow(b)
	fun floor(a: Double): Double = kotlin.math.floor(a)
	fun ceil(a: Double): Double = kotlin.math.ceil(a)
	fun copySign(magnitude: Double, sign: Double): Double = magnitude.withSign(sign)
	fun nextUp(f: Float): Float = f.kNextUp()
	fun nextUp(d: Double): Double = d.kNextUp()

	fun getExponent(f: Float): Int = ((f.toRawBits() and 0x7F800000) shr 23) - 127
	fun getExponent(d: Double): Int = (((d.toRawBits() and 0x7FF0000000000000L) shr 52) - 1023).toInt()

	/** f × 2^scaleFactor, exact while the result is a normal float (as Java's, in powers-of-two steps). */
	fun scalb(f: Float, scaleFactor: Int): Float = scalb(f.toDouble(), scaleFactor.coerceIn(-278, 278)).toFloat()

	fun scalb(d: Double, scaleFactor: Int): Double {
		var n = scaleFactor.coerceIn(-2099, 2099)
		var r = d
		// Steps of 2^±512 keep every multiplier a normal double; each multiplication by a power of two is exact.
		val step = if (n < 0) -512 else 512
		while (kotlin.math.abs(n) > 512) { r *= powerOfTwo(step); n -= step }
		return r * powerOfTwo(n)
	}

	private fun powerOfTwo(n: Int): Double = Double.fromBits((n + 1023).toLong() shl 52)
}

/** Runs [block] holding [lock]'s monitor: Kotlin's synchronized on the JVM, a recursive lock on the iPad. */
expect inline fun <R> synchronized(lock: Any, block: () -> R): R
