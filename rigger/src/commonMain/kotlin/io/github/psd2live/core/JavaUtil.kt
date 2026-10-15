package io.github.psd2live.core

import okio.Buffer
import okio.HashingSink
import okio.blackholeSink
import okio.ByteString.Companion.toByteString

// java.util's pieces the rigger uses, for shared code (Layerloom iPad plan, phase 2). On the JVM the classes are Java's
// own (typealiases in RiggerPlatformJvm.kt), so the desktop behaves exactly as before; the iPad's (RiggerPlatformIos.kt)
// follow Java's semantics where results depend on them - PriorityQueue is Java's heap, so ties pop in the same order.

/** java.util.PriorityQueue: a binary heap ordered by [comparator]. */
expect class PriorityQueue<E>(comparator: Comparator<in E>) : MutableCollection<E> {
	fun poll(): E?
	fun peek(): E?
	/** The head, removed; throws when empty. */
	fun remove(): E
}

/** java.util.WeakHashMap: an entry lives while its key is reachable elsewhere. */
expect class WeakHashMap<K, V>() : MutableMap<K, V>

/** java.util.IdentityHashMap: keys compared by identity. */
expect class IdentityHashMap<K, V>() : MutableMap<K, V>

/** java.util.concurrent.CopyOnWriteArrayList: iteration sees a snapshot. */
expect class CopyOnWriteArrayList<E>() : MutableList<E>

/** java.lang.ref.SoftReference: kept until memory runs short (on the iPad: kept while the holder lives). */
expect class SoftReference<T>(referent: T) {
	fun get(): T?
}

/** java.lang.ref.WeakReference. */
expect class WeakReference<T>(referent: T) {
	fun get(): T?
}

expect class AtomicLong() {
	constructor(initialValue: Long)
	fun get(): Long
	fun set(newValue: Long)
	fun incrementAndGet(): Long
	fun addAndGet(delta: Long): Long
}

expect class AtomicInteger() {
	constructor(initialValue: Int)
	fun get(): Int
	fun set(newValue: Int)
	fun incrementAndGet(): Int
	fun addAndGet(delta: Int): Int
}

expect class AtomicBoolean() {
	constructor(initialValue: Boolean)
	fun get(): Boolean
	fun set(newValue: Boolean)
}

/** java.util.Collections' synchronized wrappers. */
object Collections {
	fun <K, V> synchronizedMap(map: MutableMap<K, V>): MutableMap<K, V> = synchronizedMapOf(map)
	fun <T> synchronizedList(list: MutableList<T>): MutableList<T> = synchronizedListOf(list)
	/** A set backed by [map] (java.util.Collections.newSetFromMap): the map's keys, present while mapped to true. */
	fun <E> newSetFromMap(map: MutableMap<E, Boolean>): MutableSet<E> = setFromMap(map)
	fun <T> unmodifiableList(list: List<T>): List<T> = readOnlyList(list)
}

expect fun <E> setFromMap(map: MutableMap<E, Boolean>): MutableSet<E>
expect fun <T> readOnlyList(list: List<T>): List<T>

expect fun <K, V> synchronizedMapOf(map: MutableMap<K, V>): MutableMap<K, V>
expect fun <T> synchronizedListOf(list: MutableList<T>): MutableList<T>

/** Unicode NFKC normalization (java.text.Normalizer.normalize(s, NFKC)). */
expect fun normalizeNfkc(text: String): String

/** java.util.UUID.nameUUIDFromBytes(bytes).toString(): the version-3 (MD5) UUID of [bytes], lower-case hex with dashes. */
fun nameUuidFromBytes(bytes: ByteArray): String {
	val md5 = bytes.toByteString().md5().toByteArray()
	md5[6] = ((md5[6].toInt() and 0x0f) or 0x30).toByte()
	md5[8] = ((md5[8].toInt() and 0x3f) or 0x80).toByte()
	val hex = md5.toByteString().hex()
	return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

/**
 * java.security.MessageDigest for SHA-256 and SHA-1, on okio's hashes (the same bytes). As Java's, [digest] resets.
 * Files that import java.security.MessageDigest keep Java's; this one serves the rigger's shared code.
 */
class MessageDigest private constructor(private val algorithm: String) {
	private var sink = newSink()
	private val one = Buffer()

	private fun newSink(): HashingSink = when (algorithm) {
		"SHA-256" -> HashingSink.sha256(blackholeSink())
		"SHA-1" -> HashingSink.sha1(blackholeSink())
		else -> throw IllegalArgumentException("$algorithm MessageDigest not available")
	}

	fun update(input: Byte) { one.writeByte(input.toInt()); sink.write(one, 1) }
	fun update(input: ByteArray) = update(input, 0, input.size)
	fun update(input: ByteArray, offset: Int, len: Int) {
		if (len == 0) return
		val b = Buffer().write(input, offset, len)
		sink.write(b, len.toLong())
	}

	fun digest(): ByteArray = sink.hash.toByteArray().also { sink = newSink() }
	fun digest(input: ByteArray): ByteArray { update(input); return digest() }

	companion object {
		fun getInstance(algorithm: String): MessageDigest = MessageDigest(algorithm)

		/** java.security.MessageDigest.isEqual: equal digests, compared in time independent of where they differ. */
		fun isEqual(a: ByteArray?, b: ByteArray?): Boolean {
			if (a === b) return true
			if (a == null || b == null || a.size != b.size) return false
			var diff = 0
			for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
			return diff == 0
		}
	}
}

/** Lower-case hex of [bytes] (java.util.HexFormat.of().formatHex). */
fun hex(bytes: ByteArray): String = bytes.toByteString().hex()

/** java.util.Base64's basic encoder. */
fun base64Encode(bytes: ByteArray): String = kotlin.io.encoding.Base64.encode(bytes)

/** java.util.Base64's basic decoder: padding optional, anything outside the alphabet rejected. */
fun base64Decode(text: String): ByteArray =
	kotlin.io.encoding.Base64.withPadding(kotlin.io.encoding.Base64.PaddingOption.PRESENT_OPTIONAL).decode(text)

/**
 * java.util.LinkedHashMap's access-order constructor and removeEldestEntry hook, for the rigger's small
 * least-recently-used caches (`object : AccessOrderMap<K, V>(4, 0.75f, true) { override fun removeEldestEntry... }`):
 * a read or write moves an entry last when [accessOrder], and a new entry may evict the eldest. Shared code on both
 * platforms; the caches lock around it as they did around LinkedHashMap.
 */
open class AccessOrderMap<K, V> constructor(initialCapacity: Int, @Suppress("UNUSED_PARAMETER") loadFactor: Float, private val accessOrder: Boolean) :
	AbstractMutableMap<K, V>() {
	private val map = LinkedHashMap<K, V>(initialCapacity)

	protected open fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = false

	override val size: Int get() = map.size
	override fun containsKey(key: K): Boolean = map.containsKey(key)

	override fun get(key: K): V? {
		if (!map.containsKey(key)) return null
		@Suppress("UNCHECKED_CAST") val value = map[key] as V
		if (accessOrder) { map.remove(key); map[key] = value }
		return value
	}

	override fun put(key: K, value: V): V? {
		val had = map.containsKey(key)
		// A replaced key keeps its place in insertion order (LinkedHashMap's put does), and moves last in access order.
		val old = if (had && accessOrder) map.remove(key) else null
		val previous = map.put(key, value) ?: old
		if (!had) map.entries.firstOrNull()?.let { if (removeEldestEntry(it)) map.remove(it.key) }
		return previous
	}

	override fun remove(key: K): V? = map.remove(key)
	override fun clear() = map.clear()
	override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = map.entries
}

/** The entries in key order, as java.util.TreeMap iterates them (Kotlin's toSortedMap is the JVM's only). */
fun <K : Comparable<K>, V> Map<out K, V>.toSortedMap(): Map<K, V> {
	val out = LinkedHashMap<K, V>(size)
	for (entry in entries.sortedBy { it.key }) out[entry.key] = entry.value
	return out
}

/**
 * java.util.Map.merge: [value] when [key] has none, else [remapping] of the old and [value]; a null result removes the
 * key. On the JVM the map's own merge is a member and takes precedence; this serves shared code elsewhere.
 */
fun <K, V : Any> MutableMap<K, V>.merge(key: K, value: V, remapping: (V, V) -> V?): V? {
	val old = get(key)
	val new = if (old == null) value else remapping(old, value)
	if (new == null) remove(key) else put(key, new)
	return new
}

/** java.lang.ThreadLocal (the iPad's rigger runs its work one at a time, so there it is one value). */
expect class ThreadLocal<T>() {
	fun get(): T?
	fun set(value: T)
	fun remove()
}

/** java.util.Map.putIfAbsent: [value] under [key] unless it holds one; returns what it held. The JVM map's own wins there. */
fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? = get(key) ?: run { put(key, value); null }

/** A set iterating in natural order, as java.util.TreeSet (Kotlin's sortedSetOf is the JVM's only). */
class SortedMutableSet<T : Comparable<T>> : AbstractMutableSet<T>() {
	private val items = ArrayList<T>()
	override val size: Int get() = items.size
	override fun contains(element: T): Boolean = items.binarySearch(element) >= 0
	override fun add(element: T): Boolean {
		val at = items.binarySearch(element)
		if (at >= 0) return false
		items.add(-at - 1, element)
		return true
	}
	override fun iterator(): MutableIterator<T> = items.iterator()
}

/** sortedSetOf for shared code ([SortedMutableSet]). */
fun <T : Comparable<T>> sortedSetOf(vararg elements: T): SortedMutableSet<T> = SortedMutableSet<T>().apply { addAll(elements) }

/** toSortedSet for shared code ([SortedMutableSet]). */
fun <T : Comparable<T>> Iterable<T>.toSortedSet(): SortedMutableSet<T> = SortedMutableSet<T>().apply { addAll(this@toSortedSet) }

/** [transform] of each element, in order: in parallel when [parallel] (a parallel stream on the desktop). */
fun <T, R> List<T>.parallelMapList(parallel: Boolean, transform: (T) -> R): List<R> = parallelMap(size, parallel) { transform(this[it]) }

/** java.util.Random (a typealias on the desktop; the iPad's is the same 48-bit generator, so seeded motions match). */
expect class JavaRandom(seed: Long) {
	fun nextDouble(): Double
	fun nextFloat(): Float
}

/**
 * A closed polygon's inside, as java.awt.geom.Path2D.Double's contains(x, y) decides it (non-zero winding, the
 * edge rules of java.awt.geom.Curve.pointCrossingsForLine), so points on edges land on the same side everywhere.
 */
class PolygonPath(private val xs: DoubleArray, private val ys: DoubleArray) {
	constructor(points: List<Pair<Double, Double>>) : this(DoubleArray(points.size) { points[it].first }, DoubleArray(points.size) { points[it].second })

	fun contains(x: Double, y: Double): Boolean {
		if (!(x * 0.0 + y * 0.0 == 0.0) || xs.size < 2) return false
		var crossings = 0
		for (i in xs.indices) {
			val j = if (i + 1 == xs.size) 0 else i + 1
			crossings += crossingsForLine(x, y, xs[i], ys[i], xs[j], ys[j])
		}
		return crossings != 0
	}

	private fun crossingsForLine(px: Double, py: Double, x0: Double, y0: Double, x1: Double, y1: Double): Int {
		if (py < y0 && py < y1) return 0
		if (py >= y0 && py >= y1) return 0
		if (px >= x0 && px >= x1) return 0
		if (px < x0 && px < x1) return if (y0 < y1) 1 else -1
		val xIntercept = x0 + (py - y0) * (x1 - x0) / (y1 - y0)
		if (px >= xIntercept) return 0
		return if (y0 < y1) 1 else -1
	}
}
