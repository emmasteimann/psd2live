package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.DrawableId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class WorkspacePartitionCommit(val commit: WorkspaceCommit<RigPreviewModel>, val mutation: WorkspaceMutationResult)

class WorkspacePartitionCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>,
    private val observer: WorkspaceRasterWork = WorkspaceRasterWork.Direct) {
    companion object { val supported = WorkspacePartitionEdits.supported + WorkspaceDepthSplitEdits.supported }
    private val commands = WorkspaceDocumentCommands(runtime, rasterWork = observer)

    suspend fun execute(projectId: String, state: String, operations: List<WorkspaceDocumentOperation>, summary: String,
        author: MutationAuthor,
        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspacePartitionCommit {
        require(operations.isNotEmpty() && operations.all { it.operation in supported })
        val context = currentCoroutineContext()
        val job = context[WorkspacePartitionJobExecution]
        job?.check(operations)
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        context.ensureActive()
        val project: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { capture, document, model ->
            context.ensureActive(); context[WorkspaceJobContext]?.progress(0.95f, "Committing source partition")
            beforeCommit(capture, document, model)
        }
        val result = if (operations.size == 1) commands.executeCandidate(projectId, state, summary, author,
            mutation = { document, model -> WorkspaceDocumentEdits.apply(operations.single(), document, model,
                rasterWork = observer.cancellable(context) { fraction, message ->
                    context[WorkspaceJobContext]?.progress(0.05f + 0.2f * fraction, message)
                }) },
            beforeCommit = project)
        else commands.execute(projectId, state, summary, operations, author, beforeCommit = project)
        val oldIds = before.document.source.layers.mapTo(HashSet()) { it.id.raw }
        val mutation = WorkspaceMutationResult(result.capture.historyHead, result.capture.revision,
            result.capture.document.source.layers.map { it.id.raw }.filterNot { it in oldIds }, summary,
            affectedObjectIds = WorkspaceDocumentCommands.createdObjectIds(before.model.rig.puppet, result.capture.model.rig.puppet),
            applied = result.applied, state = result.capture.state, projectId = result.capture.projectId)
        job?.committed(JsonObject(mutation.sourceResult()))
        return WorkspacePartitionCommit(result, mutation)
    }
}

class WorkspacePartitionJobExecution(private val operation: String, private val completion: WorkspaceJobCompletion) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspacePartitionJobExecution>
    fun check(operations: List<WorkspaceDocumentOperation>) {
        require(operations.size == 1 && operations.single().operation == operation) { "A nested command cannot replace the active source partition" }
    }
    fun committed(result: JsonObject) { completion.committed(WorkspaceOperationOutput(result)) }
}
