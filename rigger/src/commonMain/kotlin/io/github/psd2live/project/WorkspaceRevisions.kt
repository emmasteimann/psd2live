@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.psd2live.project
import io.github.psd2live.core.MessageDigest
import io.github.psd2live.core.identityHash
import io.github.psd2live.core.putIfAbsent
import io.github.psd2live.core.toSortedMap
import io.github.psd2live.core.Collections
import io.github.psd2live.core.SoftReference
import io.github.psd2live.core.WeakHashMap
import io.github.psd2live.core.WeakReference

import io.github.psd2live.core.synchronized

import io.github.psd2live.core.RigEditOverlay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.umamo.format.art.SourceArt

/**
 * v1 revision identity, shared by GUI and application commands. Raster buffers are immutable.
 *
 * The identity is the SHA-256 of one canonical text ([reference] builds it whole). [of] computes the same hash
 * without rebuilding that text: the rig overlay's text around its authoring journal is cached by the identity of
 * the overlay's other fields, and the digest state after each journal entry is kept, so a document that appends
 * to (or shares a prefix with) a recently hashed journal only hashes its new entries.
 *
 * Fields added after v1 enter the text only when present, so documents without them keep their revision.
 */
object WorkspaceRevisions {
	private val rasterDigests = Collections.synchronizedMap(WeakHashMap<ByteArray, String>())

	fun of(document: WorkspaceDocument): String = journalCheckpointRevision(document) ?: reference(document)

	/** The revision from the whole canonical text; [of] must always agree with it. */
	fun reference(document: WorkspaceDocument): String {
		val canonical = "|settings:" + document.settings + "|rig:" + document.rigEdits + tail(document)
		return "revision-${sha256(canonical.encodeToByteArray())}"
	}

	/** Everything after the rig overlay in the canonical text. */
	internal fun tail(document: WorkspaceDocument): String = buildString {
		document.source.groups.forEach { group ->
			append("|group:").append(group.path).append(':').append(group.name).append(':').append(group.visible)
			append(':').append(group.opacity).append(':').append(group.clipped).append(':').append(group.blend).append(':').append(group.passThrough)
		}
		append("|canvas:").append(document.source.widthPx).append('x').append(document.source.heightPx)
		appendLayers(document.source)
		document.layerVisibility.toSortedMap().forEach { (key, value) -> append("|v:").append(key).append('=').append(value) }
		document.deletedLayerIds.sorted().forEach { append("|d:").append(it) }
		document.layerOverrides.toSortedMap().forEach { (key, value) -> append("|o:").append(key).append('=').append(value) }
		document.parentOverrides.toSortedMap().forEach { (key, value) -> append("|p:").append(key).append('=').append(value) }
		document.meshOverrides.toSortedMap().forEach { (key, value) -> append("|m:").append(key).append('=').append(value) }
		// Absent for documents without texture overrides.
		document.storedTextureOverrides.toSortedMap().forEach { (key, value) ->
			append("|t:").append(key).append('=').append(TextureOverrideCodec.canonical(value))
		}
		document.rigEdits.deletedParameterIds.sorted().forEach { append("|pd:").append(it) }
		document.rigEdits.parameterEdits.forEach { edit -> append("|pe:").append(edit) }
		// Omit the field entirely for v1 documents without a separate generation input.
		listOf("generation" to document.generationSource, "meshSource" to document.meshSource).forEach { (kind, source) ->
			if (source == null) return@forEach
			append("|$kind:").append(source.widthPx).append('x').append(source.heightPx)
			source.groups.forEach { group ->
				append("|group:").append(group.path).append(':').append(group.name).append(':').append(group.visible)
				append(':').append(group.opacity).append(':').append(group.clipped).append(':').append(group.blend).append(':').append(group.passThrough)
			}
			appendLayers(source)
		}
	}

	private fun StringBuilder.appendLayers(source: SourceArt) {
		source.layers.forEachIndexed { index, layer ->
			append("|layer:").append(index).append(':').append(layer.id.raw)
			append(':').append(layer.name).append(':').append(layer.groupPath).append(':').append(layer.kind)
			append(':').append(layer.visible).append(':').append(layer.order).append(':').append(layer.bounds)
			append(':').append(layer.opacity).append(':').append(layer.clipped).append(':').append(layer.blend).append(':').append(layer.channelMask)
			append(':').append(layer.raster.width).append('x').append(layer.raster.height)
			append(':').append(rasterDigest(layer.raster.rgba))
			// Only a float rectangle that differs from the integer bounds is part of the identity.
			layer.storedCanvasRect?.let { rect ->
				append(":rect=").append(rect.left).append(',').append(rect.top).append(',').append(rect.width).append(',').append(rect.height)
			}
			layer.transform.takeUnless { it.isIdentity }?.let { append(":transform=").append(it.toList().joinToString(",")) }
		}
	}

	/** SHA-256 of an immutable raster, cached by array identity. */
	fun rasterDigest(rgba: ByteArray): String = rasterDigests[rgba] ?: sha256(rgba).also { rasterDigests[rgba] = it }

	/** Records [digest] as [rgba]'s SHA-256 when the caller has just verified it (a raster loaded from its digest-named blob). */
	fun seedRasterDigest(rgba: ByteArray, digest: String) { rasterDigests[rgba] = digest }

	fun sha256(value: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(value))
	fun hex(bytes: ByteArray): String = io.github.psd2live.core.hex(bytes)
}

/** Per-entry digests of authoring-journal commands, cached by entry identity (entries are immutable). */
object JournalEntryDigests {
	class Digest(val sha256: String, val length: Int)

	private val cache = IdentityWeakCache<JsonObject, Digest>()

	fun of(entry: JsonObject): Digest = cache.getOrPut(entry) {
		val text = JournalEntryText.bytes(entry)
		Digest(WorkspaceRevisions.sha256(text), utf16Length(text))
	}

	/** The length of the string [utf8] encodes, in UTF-16 units: a four-byte sequence is a surrogate pair. */
	private fun utf16Length(utf8: ByteArray): Int {
		var length = 0
		for (byte in utf8) {
			val b = byte.toInt() and 0xff
			if (b and 0xc0 != 0x80) length += if (b and 0xf8 == 0xf0) 2 else 1
		}
		return length
	}
}

/**
 * The UTF-8 text of authoring-journal commands, cached by entry identity. Writing an entry's numbers out costs
 * several times what hashing the text does, and a revision whose overlay or settings changed hashes the whole
 * journal again. Held softly: the text comes back from the entry when memory runs low.
 */
object JournalEntryText {
	private val cache = IdentityWeakCache<JsonObject, SoftReference<ByteArray>>()

	fun bytes(entry: JsonObject): ByteArray {
		cache.getOrPut(entry) { SoftReference(entry.toString().encodeToByteArray()) }.get()?.let { return it }
		return entry.toString().encodeToByteArray().also { cache.put(entry, SoftReference(it)) }
	}
}

/** A thread-safe map from object identity to a value that does not keep its keys alive. */
expect class IdentityWeakCache<K : Any, V : Any>() {
	fun put(key: K, value: V)
	fun getOrPut(key: K, compute: () -> V): V
}

/** The revision of [document] from the recent journal digest chains (a desktop cache, same text as reference); null without one. */
internal expect fun journalCheckpointRevision(document: WorkspaceDocument): String?
