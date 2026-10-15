package io.github.psd2live.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs on the desktop (Java's classes) and on the iPad simulator (the rigger's own): the expected values come from
 * Java, so passing on both means the iPad rigs the same - ties in a search pop in the same order, ids hash the same.
 */
class PlatformParityTest {
	@Test
	fun priorityQueueTiesPopInJavasOrder() {
		val random = Random(11)
		val queue = PriorityQueue<Pair<Int, Int>>(compareBy { it.first })
		var order = 0L
		var id = 0
		repeat(4000) { step ->
			if (step % 3 == 2 && queue.isNotEmpty()) order = order * 31 + queue.remove().second
			else queue.add(random.nextInt(0, 20) to id++)
		}
		while (queue.isNotEmpty()) order = order * 31 + queue.poll()!!.second
		assertEquals(EXPECTED_QUEUE_ORDER, order)
	}

	@Test
	fun hashesAndIdsMatchJava() {
		assertEquals(EXPECTED_UUID, nameUuidFromBytes("psd2live:generated-path:a:b".encodeToByteArray()))
		val digest = MessageDigest.getInstance("SHA-256")
		digest.update("layer".encodeToByteArray()); digest.update(0); digest.update("quill".encodeToByteArray(), 1, 3)
		assertEquals(EXPECTED_SHA256, digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
		assertEquals(EXPECTED_EMPTY_SHA1, MessageDigest.getInstance("SHA-1").digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
	}

	@Test
	fun bitSetBytesMatchJava() {
		val set = BitSet(300)
		for (i in listOf(0, 5, 63, 64, 127, 200, 299)) set.set(i)
		set.set(70, 90)
		assertEquals(EXPECTED_BITS, set.toByteArray().joinToString(",") { it.toString() })
		assertEquals(set.hashCode(), bitSetOf(set.toByteArray()).hashCode())
		assertEquals(27, set.cardinality())
		assertEquals(300, set.length())
		assertEquals(70, set.nextSetBit(65))
		assertEquals(1, set.nextClearBit(0))
	}

	@Test
	fun nfkcMatchesJava() {
		assertEquals("Hair Front 2 fi", normalizeNfkc("Ｈａｉｒ Front ② ﬁ"))
	}

	@Test
	fun mathRoundsAsJava() {
		assertEquals(1, Math.round(0.5f)); assertEquals(-2, Math.round(-2.5f)); assertEquals(0L, Math.round(0.49999999999999994))
		assertEquals(0.017453292519943295, Math.toRadians(1.0))
	}

	private companion object {
		const val EXPECTED_QUEUE_ORDER = -468709811187511753L
		const val EXPECTED_UUID = "2a69b28e-4c80-3b52-80ea-d1cbd11752b4"
		const val EXPECTED_SHA256 = "ca5122f36ccf650ffcc16e262a2222e8e8c12b8a45e9b6cc866a7256e8eb3049"
		const val EXPECTED_EMPTY_SHA1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709"
		const val EXPECTED_BITS = "33,0,0,0,0,0,0,-128,-63,-1,-1,3,0,0,0,-128,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,8"
	}
}
