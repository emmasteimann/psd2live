package io.github.psd2live.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared Rectangle is java.awt.Rectangle's arithmetic: every operation, overflow and negative sizes included. */
class RectangleParityTest {
	private val random = Random(17)
	private fun value(): Int = when (random.nextInt(6)) {
		0 -> random.nextInt(-50, 50); 1 -> random.nextInt(0, 4000); 2 -> Int.MAX_VALUE - random.nextInt(100)
		3 -> Int.MIN_VALUE + random.nextInt(100); 4 -> 0; else -> random.nextInt()
	}
	private fun pair(): Pair<java.awt.Rectangle, Rectangle> { val x = value(); val y = value(); val w = value(); val h = value()
		return java.awt.Rectangle(x, y, w, h) to Rectangle(x, y, w, h) }
	private fun same(a: java.awt.Rectangle, b: Rectangle, what: String) = assertEquals(listOf(a.x, a.y, a.width, a.height), listOf(b.x, b.y, b.width, b.height), what)

	@Test
	fun everyOperationMatchesAwt() {
		repeat(50_000) {
			val (a, b) = pair(); val (c, d) = pair()
			same(a.union(c), b.union(d), "union of $a and $c")
			same(a.intersection(c), b.intersection(d), "intersection of $a and $c")
			assertEquals(a.intersects(c), b.intersects(d), "intersects $a $c")
			assertEquals(a.contains(c), b.contains(d), "contains $a $c")
			val px = value(); val py = value()
			assertEquals(a.contains(px, py), b.contains(px, py), "contains point")
			assertEquals(a.isEmpty, b.isEmpty, "isEmpty $a")
			assertEquals(a.hashCode(), b.hashCode(), "hashCode $a")
			assertEquals(a.toString(), b.toString())
			assertEquals(a.maxX, b.maxX); assertEquals(a.minY, b.minY)
			val a2 = java.awt.Rectangle(a); val b2 = Rectangle(b)
			a2.add(c); b2.add(d); same(a2, b2, "add $c to $a")
			a2.add(px, py); b2.add(px, py); same(a2, b2, "add point to $a2")
			assertEquals(a2 == java.awt.Rectangle(a2), b2 == Rectangle(b2))
		}
	}
}
