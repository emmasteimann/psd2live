package io.github.psd2live.project

import io.github.psd2live.core.SoftReference
import io.github.psd2live.core.identityHash
import io.github.psd2live.core.synchronized
import io.github.psd2live.core.RigEditOverlay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.security.MessageDigest

// The desktop's revision cache: digest states cloned along recent journals, and the overlay text split by reflection.
// WorkspaceRevisions (shared) falls back to hashing the whole text where this is absent.

internal actual fun journalCheckpointRevision(document: WorkspaceDocument): String? = JournalCheckpoints.of(document)

/**
 * The canonical text is `prefix + entry0 + ", " + entry1 + ... + suffix + tail`: the overlay's data-class text
 * with the journal list spliced out. Digest states after each entry are kept for a few recent chains.
 */
internal object JournalCheckpoints {
	private const val CHAINS = 4

	private class Chain(
		val settings: String,
		val before: String,
		val base: MessageDigest,
		val entries: List<JsonObject>,
		val states: List<MessageDigest>,
	)

	private val chains = ArrayDeque<Chain>()

	fun of(document: WorkspaceDocument): String? {
		val parts = OverlayParts.of(document.rigEdits) ?: return null
		val settings = document.settings.toString()
		val journal = document.rigEdits.authoringJournal
		val found = synchronized(chains) {
			chains.firstOrNull { it.settings == settings && (it.before === parts.before || it.before == parts.before) }
		}
		var shared = 0
		val states = ArrayList<MessageDigest>(journal.size)
		val base: MessageDigest
		if (found != null) {
			base = found.base
			val limit = minOf(found.entries.size, journal.size)
			while (shared < limit && found.entries[shared] === journal[shared]) shared++
			for (i in 0 until shared) states.add(found.states[i])
		} else {
			base = MessageDigest.getInstance("SHA-256")
			base.update("|settings:".encodeToByteArray()); base.update(settings.encodeToByteArray())
			base.update("|rig:".encodeToByteArray()); base.update(parts.before.encodeToByteArray())
		}
		val digest = (if (shared == 0) base else states[shared - 1]).clone() as MessageDigest
		for (i in shared until journal.size) {
			if (i > 0) digest.update(SEPARATOR)
			digest.update(JournalEntryText.bytes(journal[i]))
			states.add(digest.clone() as MessageDigest)
		}
		val chain = Chain(settings, parts.before, base, ArrayList(journal), states)
		synchronized(chains) {
			if (found != null) chains.remove(found)
			chains.addFirst(chain)
			while (chains.size > CHAINS) chains.removeLast()
		}
		digest.update(parts.after.encodeToByteArray())
		digest.update(WorkspaceRevisions.tail(document).encodeToByteArray())
		return "revision-${WorkspaceRevisions.hex(digest.digest())}"
	}

	private val SEPARATOR = ", ".encodeToByteArray()
}

/** The overlay's text before and after its journal entries, cached by the identity of its other fields. */
private object OverlayParts {
	class Parts(val values: Array<Any?>, val before: String, val after: String)

	private const val CACHED = 8
	private val fields: List<Field>? = runCatching {
		val all = RigEditOverlay::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
		require(all.count { it.name == "authoringJournal" } == 1)
		all.filter { it.name != "authoringJournal" }.onEach { it.isAccessible = true }
	}.getOrNull()
	private val recent = ArrayDeque<Parts>()

	private fun same(a: Any?, b: Any?): Boolean = a === b || ((a is Number || a is Boolean || a is Char) && a == b)

	fun of(rig: RigEditOverlay): Parts? {
		val fields = fields ?: return null
		val values = Array(fields.size) { fields[it].get(rig) }
		synchronized(recent) {
			recent.firstOrNull { parts -> values.indices.all { same(parts.values[it], values[it]) } }?.let { return it }
		}
		val sentinel = JsonObject(mapOf("psd2live-journal-${kotlin.uuid.Uuid.random()}" to JsonNull))
		val text = rig.copy(authoringJournal = listOf(sentinel)).toString()
		val marker = "[$sentinel]"
		val at = text.indexOf(marker)
		if (at < 0 || text.indexOf(marker, at + 1) >= 0) return null
		val parts = Parts(values, text.substring(0, at + 1), text.substring(at + marker.length - 1))
		synchronized(recent) {
			recent.addFirst(parts)
			while (recent.size > CACHED) recent.removeLast()
		}
		return parts
	}
}

actual class IdentityWeakCache<K : Any, V : Any> actual constructor() {
	private class Key(referent: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(referent, queue) {
		private val hash = identityHash(referent)
		override fun hashCode(): Int = hash
		override fun equals(other: Any?): Boolean {
			if (other === this) return true
			if (other !is Key || other.hash != hash) return false
			val referent = get()
			return referent != null && referent === other.get()
		}
	}

	private val queue = ReferenceQueue<Any>()
	private val map = HashMap<Key, V>()

	actual fun put(key: K, value: V) {
		synchronized(this) { purge(); map[Key(key, queue)] = value }
	}

	actual fun getOrPut(key: K, compute: () -> V): V {
		synchronized(this) { purge(); map[Key(key, null)]?.let { return it } }
		val value = compute()
		synchronized(this) { map.putIfAbsent(Key(key, queue), value)?.let { return it } }
		return value
	}

	private fun purge() {
		while (true) map.remove(queue.poll() as? Key ?: return)
	}
}
