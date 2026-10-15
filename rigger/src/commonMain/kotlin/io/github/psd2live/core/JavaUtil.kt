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
}

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
	}
}

/** Lower-case hex of [bytes] (java.util.HexFormat.of().formatHex). */
fun hex(bytes: ByteArray): String = bytes.toByteString().hex()

/** java.util.Base64's basic encoder. */
fun base64Encode(bytes: ByteArray): String = kotlin.io.encoding.Base64.encode(bytes)

/** java.util.Base64's basic decoder: padding optional, anything outside the alphabet rejected. */
fun base64Decode(text: String): ByteArray =
	kotlin.io.encoding.Base64.withPadding(kotlin.io.encoding.Base64.PaddingOption.PRESENT_OPTIONAL).decode(text)
