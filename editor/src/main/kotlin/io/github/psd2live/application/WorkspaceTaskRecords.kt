package io.github.psd2live.application

import io.github.psd2live.project.WorkspaceTaskSnapshot


/**
 * The Agent task log older builds kept per project. Nothing records tasks any more; a project that has them keeps
 * them, validated on load and saved back unchanged.
 */
class WorkspaceTaskRecords {
	private var tasks: List<WorkspaceTaskSnapshot> = emptyList()

	fun restore(snapshots: List<WorkspaceTaskSnapshot>) = io.github.psd2live.core.synchronized(this) {
		require(tasks.isEmpty()) { "Task manager is already initialized" }
		require(snapshots.map { it.id }.toSet().size == snapshots.size) { "Persisted task IDs must be unique" }
		for (snapshot in snapshots) {
			require(snapshot.id.isNotBlank() && snapshot.objective.isNotBlank()) { "Persisted task identity is incomplete" }
			require(snapshot.plan.isNotEmpty()) { "Persisted task plan must not be empty" }
			require(snapshot.progress.isFinite() && snapshot.progress in 0f..1f) { "Persisted task progress is invalid" }
			snapshot.currentStep?.let { require(it in snapshot.plan.indices) { "Persisted current task step is invalid" } }
			@OptIn(kotlin.time.ExperimentalTime::class) run { kotlin.time.Instant.parse(snapshot.createdAt); kotlin.time.Instant.parse(snapshot.updatedAt) }
			require(snapshot.events.map { it.sequence }.toSet().size == snapshot.events.size) { "Persisted task event sequences must be unique" }
		}
		tasks = snapshots.map { it.copy(events = it.events.sortedBy { event -> event.sequence }) }
	}

	fun list(): List<WorkspaceTaskSnapshot> = io.github.psd2live.core.synchronized(this) { tasks }
}
