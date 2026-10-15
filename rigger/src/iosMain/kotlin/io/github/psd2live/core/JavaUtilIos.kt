@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.psd2live.core

import kotlin.native.identityHashCode
import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCompatibilityMapping

// The iPad's java.util stand-ins (JavaUtil.kt). The rigger's parallel work runs one at a time on the iPad
// (parallelFor), so the concurrent classes guard their state with the one monitor (synchronized) for safety only.

/** java.util.PriorityQueue's own heap (siftUp / siftDown as in OpenJDK), so equal elements pop in Java's order. */
actual class PriorityQueue<E> actual constructor(private val comparator: Comparator<in E>) : AbstractMutableCollection<E>() {
	private var queue = arrayOfNulls<Any?>(11)
	private var count = 0

	override val size: Int get() = count

	@Suppress("UNCHECKED_CAST")
	private fun at(i: Int): E = queue[i] as E

	override fun add(element: E): Boolean {
		if (count >= queue.size) queue = queue.copyOf(if (queue.size < 64) queue.size * 2 + 2 else queue.size + (queue.size shr 1))
		siftUp(count, element)
		count++
		return true
	}

	actual fun peek(): E? = if (count == 0) null else at(0)

	actual fun poll(): E? {
		if (count == 0) return null
		val result = at(0)
		val n = --count
		val x = at(n)
		queue[n] = null
		if (n > 0) siftDown(0, x, n)
		return result
	}

	actual fun remove(): E = if (count == 0) throw NoSuchElementException() else poll() as E

	private fun siftUp(start: Int, x: E) {
		var k = start
		while (k > 0) {
			val parent = (k - 1) ushr 1
			val e = at(parent)
			if (comparator.compare(x, e) >= 0) break
			queue[k] = e
			k = parent
		}
		queue[k] = x
	}

	private fun siftDown(start: Int, x: E, n: Int) {
		var k = start
		val half = n ushr 1
		while (k < half) {
			var child = (k shl 1) + 1
			var c = at(child)
			val right = child + 1
			if (right < n && comparator.compare(c, at(right)) > 0) { child = right; c = at(child) }
			if (comparator.compare(x, c) <= 0) break
			queue[k] = c
			k = child
		}
		queue[k] = x
	}

	private fun removeAt(i: Int) {
		val s = --count
		if (s == i) { queue[i] = null; return }
		val moved = at(s)
		queue[s] = null
		siftDown(i, moved, s)
		if (queue[i] === moved) siftUp(i, moved)
	}

	override fun iterator(): MutableIterator<E> = object : MutableIterator<E> {
		private var cursor = 0
		private var last = -1
		override fun hasNext() = cursor < count
		override fun next(): E { if (cursor >= count) throw NoSuchElementException(); last = cursor; return at(cursor++) }
		override fun remove() { check(last >= 0); removeAt(last); cursor = last; last = -1 }
	}
}

/** A map whose entries go once their key is collected (keys compared by equals, as java.util.WeakHashMap). */
actual class WeakHashMap<K, V> actual constructor() : AbstractMutableMap<K, V>() {
	private class Slot<K, V>(key: K, val hash: Int, var value: V) {
		val ref = kotlin.native.ref.WeakReference(key as Any)
		@Suppress("UNCHECKED_CAST")
		val key: K? get() = ref.get() as K?
	}

	private val buckets = HashMap<Int, MutableList<Slot<K, V>>>()

	private fun purge() {
		val it = buckets.values.iterator()
		while (it.hasNext()) { val list = it.next(); list.removeAll { s -> s.ref.get() == null }; if (list.isEmpty()) it.remove() }
	}

	private fun slot(key: K): Slot<K, V>? = buckets[key.hashCode()]?.firstOrNull { it.key.let { k -> k != null && k == key } }

	override fun get(key: K): V? = slot(key)?.value
	override fun containsKey(key: K): Boolean = slot(key) != null

	override fun put(key: K, value: V): V? {
		purge()
		slot(key)?.let { s -> val old = s.value; s.value = value; return old }
		buckets.getOrPut(key.hashCode()) { mutableListOf() }.add(Slot(key, key.hashCode(), value))
		return null
	}

	override fun remove(key: K): V? {
		val list = buckets[key.hashCode()] ?: return null
		val s = list.firstOrNull { it.key == key } ?: return null
		list.remove(s)
		if (list.isEmpty()) buckets.remove(key.hashCode())
		return s.value
	}

	override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
		get() {
			purge()
			// A snapshot of the live entries (Java's views are live; the rigger only reads or clears them).
			val live = LinkedHashMap<K, V>()
			for (list in buckets.values) for (s in list) { val k = s.key ?: continue; live[k] = s.value }
			return object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
				override val size get() = live.size
				override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()
				override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
					val inner = live.entries.iterator()
					var current: MutableMap.MutableEntry<K, V>? = null
					return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
						override fun hasNext() = inner.hasNext()
						override fun next() = inner.next().also { current = it }
						override fun remove() { inner.remove(); this@WeakHashMap.remove(current!!.key) }
					}
				}
			}
		}

	override fun clear() = buckets.clear()
}

/** Keys compared by identity. */
actual class IdentityHashMap<K, V> actual constructor() : AbstractMutableMap<K, V>() {
	private class Id<K>(val key: K) {
		override fun equals(other: Any?) = other is Id<*> && other.key === key
		override fun hashCode() = key?.identityHashCode() ?: 0
	}

	private val map = LinkedHashMap<Id<K>, V>()

	override fun get(key: K): V? = map[Id(key)]
	override fun containsKey(key: K): Boolean = map.containsKey(Id(key))
	override fun put(key: K, value: V): V? = map.put(Id(key), value)
	override fun remove(key: K): V? = map.remove(Id(key))
	override fun clear() = map.clear()

	override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
		get() = object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
			override val size get() = map.size
			override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()
			override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
				val inner = map.entries.iterator()
				return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
					override fun hasNext() = inner.hasNext()
					override fun next(): MutableMap.MutableEntry<K, V> {
						val e = inner.next()
						return object : MutableMap.MutableEntry<K, V> {
							override val key get() = e.key.key
							override val value get() = e.value
							override fun setValue(newValue: V) = e.setValue(newValue)
						}
					}
					override fun remove() = inner.remove()
				}
			}
		}
}

/** Writes copy the list; iteration walks the snapshot it started on. */
actual class CopyOnWriteArrayList<E> actual constructor() : AbstractMutableList<E>() {
	@kotlin.concurrent.Volatile private var items: List<E> = emptyList()

	override val size: Int get() = items.size
	override fun get(index: Int): E = items[index]
	override fun add(index: Int, element: E) = synchronized(this) { items = items.toMutableList().apply { add(index, element) } }
	override fun removeAt(index: Int): E = synchronized(this) { val l = items.toMutableList(); val e = l.removeAt(index); items = l; e }
	override fun set(index: Int, element: E): E = synchronized(this) { val l = items.toMutableList(); val e = l.set(index, element); items = l; e }
	override fun iterator(): MutableIterator<E> {
		val snapshot = items.iterator()
		return object : MutableIterator<E> {
			override fun hasNext() = snapshot.hasNext()
			override fun next() = snapshot.next()
			override fun remove() = throw UnsupportedOperationException()
		}
	}
}

/** Kept while the holder lives (the iPad has no soft references; caches holding these bound their own size). */
actual class SoftReference<T> actual constructor(private val referent: T) {
	actual fun get(): T? = referent
}

actual class WeakReference<T> actual constructor(referent: T) {
	private val ref = kotlin.native.ref.WeakReference(referent as Any)
	@Suppress("UNCHECKED_CAST")
	actual fun get(): T? = ref.get() as T?
}

actual class AtomicLong actual constructor() {
	private var value = 0L
	actual constructor(initialValue: Long) : this() { value = initialValue }
	actual fun get(): Long = synchronized(this) { value }
	actual fun set(newValue: Long) = synchronized(this) { value = newValue }
	actual fun incrementAndGet(): Long = synchronized(this) { ++value }
	actual fun addAndGet(delta: Long): Long = synchronized(this) { value += delta; value }
}

actual class AtomicInteger actual constructor() {
	private var value = 0
	actual constructor(initialValue: Int) : this() { value = initialValue }
	actual fun get(): Int = synchronized(this) { value }
	actual fun set(newValue: Int) = synchronized(this) { value = newValue }
	actual fun incrementAndGet(): Int = synchronized(this) { ++value }
	actual fun addAndGet(delta: Int): Int = synchronized(this) { value += delta; value }
}

actual class AtomicBoolean actual constructor() {
	private var value = false
	actual constructor(initialValue: Boolean) : this() { value = initialValue }
	actual fun get(): Boolean = synchronized(this) { value }
	actual fun set(newValue: Boolean) = synchronized(this) { value = newValue }
}

private class LockedMap<K, V>(private val inner: MutableMap<K, V>) : MutableMap<K, V> by inner {
	override fun get(key: K): V? = synchronized(this) { inner[key] }
	override fun put(key: K, value: V): V? = synchronized(this) { inner.put(key, value) }
	override fun remove(key: K): V? = synchronized(this) { inner.remove(key) }
	override fun containsKey(key: K): Boolean = synchronized(this) { inner.containsKey(key) }
	override fun clear() = synchronized(this) { inner.clear() }
	override val size: Int get() = synchronized(this) { inner.size }
}

private class LockedList<T>(private val inner: MutableList<T>) : MutableList<T> by inner {
	override fun add(element: T): Boolean = synchronized(this) { inner.add(element) }
	override fun get(index: Int): T = synchronized(this) { inner[index] }
	override fun clear() = synchronized(this) { inner.clear() }
	override val size: Int get() = synchronized(this) { inner.size }
}

actual fun <K, V> synchronizedMapOf(map: MutableMap<K, V>): MutableMap<K, V> = LockedMap(map)
actual fun <T> synchronizedListOf(list: MutableList<T>): MutableList<T> = LockedList(list)

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfkc(text: String): String =
	(text as NSString).precomposedStringWithCompatibilityMapping

actual class ThreadLocal<T> actual constructor() {
	private var value: T? = null
	actual fun get(): T? = value
	actual fun set(value: T) { this.value = value }
	actual fun remove() { value = null }
}

/** java.util.Random's linear congruential generator, as its specification gives it. */
actual class JavaRandom actual constructor(seed: Long) {
	private var seed = (seed xor 0x5DEECE66DL) and ((1L shl 48) - 1)

	private fun next(bits: Int): Int {
		seed = (seed * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
		return (seed ushr (48 - bits)).toInt()
	}

	actual fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) * (1.0 / (1L shl 53))
	actual fun nextFloat(): Float = next(24) / (1 shl 24).toFloat()
}

/** The map's keys as a set, as java.util.Collections.newSetFromMap: adding maps a key to true, removing unmaps it. */
actual fun <E> setFromMap(map: MutableMap<E, Boolean>): MutableSet<E> = object : AbstractMutableSet<E>() {
	override val size: Int get() = map.size
	override fun contains(element: E): Boolean = map.containsKey(element)
	override fun add(element: E): Boolean = map.put(element, true) == null
	override fun remove(element: E): Boolean = map.remove(element) != null
	override fun iterator(): MutableIterator<E> = map.keys.iterator()
}

actual fun <T> readOnlyList(list: List<T>): List<T> = object : AbstractList<T>() {
	override val size: Int get() = list.size
	override fun get(index: Int): T = list[index]
}

actual fun <K : Any, V : Any> concurrentMapOf(): MutableMap<K, V> = synchronizedMapOf(HashMap())

actual class AtomicReference<V> actual constructor(initialValue: V) {
	private var value = initialValue
	actual fun get(): V = synchronized(this) { value }
	actual fun set(newValue: V) = synchronized(this) { value = newValue }
	actual fun compareAndSet(expectedValue: V, newValue: V): Boolean = synchronized(this) { if (value === expectedValue) { value = newValue; true } else false }
	actual fun getAndSet(newValue: V): V = synchronized(this) { val old = value; value = newValue; old }
}


/** A preferences node kept in NSUserDefaults as "prefs:<path>/<key>" strings, as Java stores every value as text. */
private class DefaultsPreferences(private val path: String) : Preferences() {
	private val defaults = platform.Foundation.NSUserDefaults.standardUserDefaults
	private fun k(key: String) = "prefs:$path/$key"
	override fun get(key: String, def: String?): String? = defaults.stringForKey(k(key)) ?: def
	override fun put(key: String, value: String) = defaults.setObject(value, k(key))
	override fun remove(key: String) = defaults.removeObjectForKey(k(key))
	override fun getBoolean(key: String, def: Boolean): Boolean = get(key, null)?.let { it.equals("true", ignoreCase = true) } ?: def
	override fun putBoolean(key: String, value: Boolean) = put(key, value.toString())
	override fun getInt(key: String, def: Int): Int = get(key, null)?.toIntOrNull() ?: def
	override fun putInt(key: String, value: Int) = put(key, value.toString())
	override fun getFloat(key: String, def: Float): Float = get(key, null)?.toFloatOrNull() ?: def
	override fun putFloat(key: String, value: Float) = put(key, value.toString())
	override fun keys(): Array<String> = defaults.dictionaryRepresentation().keys.mapNotNull { it as? String }
		.filter { it.startsWith("prefs:$path/") && '/' !in it.removePrefix("prefs:$path/") }.map { it.removePrefix("prefs:$path/") }.toTypedArray()
	override fun clear() = keys().forEach(::remove)
	override fun flush() {}
	override fun node(pathName: String): Preferences =
		DefaultsPreferences(if (pathName.startsWith("/")) pathName.trimEnd('/') else "${path.trimEnd('/')}/$pathName")
}

actual fun userPreferencesRoot(): Preferences = DefaultsPreferences("")
