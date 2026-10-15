package io.github.psd2live.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which bones are selected: one set, with the most recently picked as the primary the inspector shows.
 * The primary is always a member of [ids], or null when nothing is selected, so the two cannot disagree.
 */
class BoneSelection {
	var ids by mutableStateOf<Set<String>>(emptySet())
		private set
	var primary by mutableStateOf<String?>(null)
		private set

	/** Selects only [id], or nothing. */
	fun set(id: String?) {
		ids = setOfNotNull(id)
		primary = id
	}

	/** Toggles [id] in or out of the selection; the primary falls back to the last remaining bone. */
	fun toggle(id: String) {
		val next = if (id in ids) ids - id else ids + id
		primary = id.takeIf { it in next } ?: next.lastOrNull()
		ids = next
	}

	/** Selects [next] (added to the selection when [additive]); the primary is the last of them. */
	fun setAll(next: Set<String>, additive: Boolean = false) {
		val merged = if (additive) ids + next else next
		ids = merged
		primary = merged.lastOrNull()
	}
}
