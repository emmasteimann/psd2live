package io.github.psd2live.scenario

import io.github.psd2live.project.WorkspaceRevisions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The desktop's journal checkpoint cache is a faster way to the same revision: the iPad, without it, hashes the whole
 * canonical text (WorkspaceRevisions.reference), and both must name every document the same.
 */
class RevisionCheckpointTest {
	@Test
	fun checkpointsGiveTheReferenceRevision() {
		val document = Characters.figure().let { it.copy(settings = it.settings) }
		assertEquals(WorkspaceRevisions.reference(document), WorkspaceRevisions.of(document))
		var journal = emptyList<JsonObject>()
		repeat(5) { i ->
			journal = journal + JsonObject(mapOf("op" to JsonPrimitive("test"), "n" to JsonPrimitive(i)))
			val next = document.copy(rigEdits = document.rigEdits.copy(authoringJournal = journal))
			assertEquals(WorkspaceRevisions.reference(next), WorkspaceRevisions.of(next), "after $i entries")
			assertEquals(WorkspaceRevisions.reference(next), WorkspaceRevisions.of(next), "again, from the cache")
		}
	}
}
