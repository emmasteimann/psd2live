@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.psd2live.application
import io.github.psd2live.project.Files
import io.github.psd2live.project.Path
import io.github.psd2live.project.fileName
import io.github.psd2live.project.isAbsolute
import io.github.psd2live.core.toSortedMap
import io.github.psd2live.core.warn
import io.github.psd2live.core.besidePsd

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.format.art.SourceArt
import org.umamo.format.psd.PsdReader

/** New source projects use one isolated read/rebuild/install boundary in both adapters. */
class WorkspaceSourceImporter(
    private val runtime: WorkspaceRuntime<RigPreviewModel>,
    private val build: suspend (WorkspaceDocument) -> RigPreviewModel = { WorkspacePreviewBuilder().build(it) },
    private val readPsd: suspend (Path) -> SourceArt = { path -> runInterruptible(Dispatchers.IO) {
        val bytes = Files.readAllBytes(path)
        require(PsdReader.matches(bytes)) { "Invalid PSD file" }
        PsdReader.read(bytes)
    } },
    private val readArtwork: suspend (JsonObject) -> Pair<WorkspaceSourceArt, Map<String, LayerClassificationOverride>> = {
        runInterruptible(Dispatchers.IO) { sourceArtwork(it) }
    },
    private val newProjectId: () -> String = { kotlin.uuid.Uuid.random().toString() },
    private val initialWorkspaceIds: () -> List<String> = { emptyList() },
) {
    suspend fun importPsd(path: Path, projectId: String?, state: String, discardUnsaved: Boolean = false,
                         initialConfig: PipelineConfig = PipelineConfig(),
                         sourceAuxiliary: JsonObject = JsonObject(emptyMap()),
                         /** Where the PSD came from, when [path] is the store's own copy: its rig job lies beside the original. */
                         jobFrom: Path = path,
                         project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        // Layerloom patch: a rig job beside the PSD (NAME.layerloom.json) builds the rig Layerloom asked for: its build
        // inputs go into the project settings, its toggles become layer overrides, and its skeleton and swings
        // become rig edits after the first build (LayerloomJob).
        val job = runCatching { LayerloomJob.besidePsd(jobFrom)?.let(LayerloomJob::read) }
            .onFailure { warn("Warning: Layerloom job beside ${path.fileName}: ${it.message}") }.getOrNull()
            ?.takeUnless { it.isEmpty }
        return install(projectId, state, discardUnsaved, job?.configure(initialConfig) ?: initialConfig, "Imported PSD ${path.fileName}",
            prepare = {
                require(path.isAbsolute && Files.isRegularFile(path) && path.fileName.toString().endsWith(".psd", true)) { "Provide an absolute local PSD path" }
                val source = readPsd(path)
                source to (job?.let { LayerloomJob.toggleOverrides(source, it.toggles) } ?: emptyMap())
            }, project = project, sourceAuxiliary = sourceAuxiliary, rigEdits = RigEditOverlay(expressions = job?.expressions.orEmpty()))
    }

    /**
     * Layerloom patch (ONE-APP-PLAN S3b): the open project's artwork replaced by the PSD at [path], keeping its edits in
     * the same project as one history step. Layers are matched by id (a Layerloom PSD's ids follow its parts through
     * renames and reorders); per-layer settings for layers that are gone are dropped, and new layers get the generated
     * rig. A rig job beside [jobFrom] re-applies its build options (glue, anchors, turn depth, toggles); its one-time
     * setup (skeleton, swings) runs only if the project has none yet.
     */
    suspend fun updateArt(path: Path, state: String, author: MutationAuthor, jobFrom: Path = path,
                          sourceAuxiliary: JsonObject = JsonObject(emptyMap()),
                          project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        val before = runtime.state.value
        val current = requireNotNull(before.capture) { "Update art needs an open project" }
        WorkspaceExecution(current.projectId, state, author).check(before.capture?.projectId, before.state)
        require(path.isAbsolute && Files.isRegularFile(path) && path.fileName.toString().endsWith(".psd", true)) { "Provide an absolute local PSD path" }
        suspend fun progress(value: Float, message: String) {
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[WorkspaceJobContext]?.progress(value, message)
        }
        progress(0.1f, "Reading the new artwork")
        val source = readPsd(path)
        val job = runCatching { LayerloomJob.besidePsd(jobFrom)?.let(LayerloomJob::read) }.getOrNull()?.takeUnless { it.isEmpty }
        val (document, config) = artUpdated(current.document, source, job)
        progress(0.4f, "Rebuilding the rig with your edits")
        // Built against the current model: the generated rig changed under the journal, so the builder merges the
        // user's rig onto the new generation and checkpoints it (RigRegenerationCheckpoint) rather than replaying
        // old entries - some of which address meshes the new art no longer has - on it.
        val builder = WorkspacePreviewBuilder()
        var built = builder.build(document, current.model)
        var rigEdits = withGoneLayersDeleted(built, source, built.config.rigEdits)
        if (rigEdits !== built.config.rigEdits) { built = builder.build(document.copy(rigEdits = rigEdits), built); rigEdits = built.config.rigEdits }
        if ((config.autoSkeleton != null && rigEdits.skeleton == null) || (config.layerSwings.isNotEmpty() && rigEdits.swingEdits.isEmpty())) {
            built = LayerloomJob.setUp(built, config.copy(rigEdits = rigEdits)) { next -> build(document.copy(rigEdits = next.rigEdits)) }
            rigEdits = built.config.rigEdits
        }
        val arrangement = AtlasLayout.frozen(built.atlas)
        val preview = built.copy(config = built.config.copy(atlasArrangement = arrangement), atlas = built.atlas.copy(arranged = true))
        val prepared = document.copy(rigEdits = rigEdits,
            settings = AtlasArrangementCodec.with(WorkspaceSettingsCodec.encode(WorkspaceSettingsPolicy.restoreRaw(preview.config, config)), arrangement))
        progress(0.9f, "Committing the new artwork")
        val summary = "Updated art from ${path.fileName}"
        val result = runtime.commitPrepared(current.projectId, state, summary, author, prepared, preview,
            auxiliary = JsonObject(current.auxiliary + sourceAuxiliary)) { _, next, model -> project(next, model, current.projectId) }
        currentCoroutineContext()[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(result.mutation(summary).sourceResult()))
        return result
    }

    companion object {
        /**
         * Layerloom patch (S3b): [rigEdits] with a `layer_delete` record for each layer of [source]'s predecessor that
         * [model] still draws although [source] no longer has it. The regeneration merge keeps a mesh the user had
         * edited even when its layer is gone (RETIRED_KEPT); a layer deleted in Layerloom takes its meshes with it.
         */
        fun withGoneLayersDeleted(model: RigPreviewModel, source: SourceArt, rigEdits: RigEditOverlay): RigEditOverlay {
            val ids = source.layers.mapTo(HashSet()) { it.id.raw }
            val gone = model.rig.layerIdByDrawableId.entries.filter { it.value.substringBefore("::") !in ids }
                .groupBy({ it.value.substringBefore("::") }, { it.key })
            if (gone.isEmpty()) return rigEdits
            return rigEdits.copy(authoringJournal = rigEdits.authoringJournal + gone.toSortedMap().map { (layer, meshes) -> LayerDeletionJournal.encode(layer, meshes) })
        }

        /**
         * Layerloom patch (S3b): [old] with its artwork replaced by [source], keeping its edits: per-layer settings follow
         * layers by id and are dropped for layers that are gone; [job]'s build options are re-applied. The document and
         * the raw settings it was given.
         */
        fun artUpdated(old: WorkspaceDocument, source: SourceArt, job: LayerloomJob?): Pair<WorkspaceDocument, PipelineConfig> {
            val ids = source.layers.mapTo(HashSet()) { it.id.raw }
            val keep = { id: String -> id in ids }
            val raw = old.rawConfig()
            val config = job?.configure(raw)?.copy(rigEdits = raw.rigEdits) ?: raw
            val toggles = job?.let { LayerloomJob.toggleOverrides(source, it.toggles) }.orEmpty()
            return old.copy(source = source,
                layerVisibility = old.layerVisibility.filterKeys(keep), deletedLayerIds = old.deletedLayerIds.filterTo(HashSet(), keep),
                layerOverrides = old.layerOverrides.filterKeys(keep) + toggles, parentOverrides = old.parentOverrides.filterKeys(keep),
                meshOverrides = old.meshOverrides.filterKeys(keep), textureOverrides = old.textureOverrides.filterKeys(keep),
                generationSource = null, meshSource = null, settings = WorkspaceSettingsCodec.encode(config)) to config
        }
    }

    suspend fun createArtwork(arguments: JsonObject, projectId: String?, state: String, discardUnsaved: Boolean = false,
                              initialConfig: PipelineConfig = PipelineConfig(),
                              project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> =
        install(projectId, state, discardUnsaved, initialConfig, "Created source artwork", { readArtwork(arguments) }, project)

    private suspend fun install(projectId: String?, state: String, discardUnsaved: Boolean, initialConfig: PipelineConfig,
                                summary: String, prepare: suspend () -> Pair<SourceArt, Map<String, LayerClassificationOverride>>,
                                project: (WorkspaceDocument, RigPreviewModel, String) -> Unit,
                                sourceAuxiliary: JsonObject = JsonObject(emptyMap()),
                                /** Layerloom patch: rig edits a new source project starts with (a rig job's expressions). */
                                rigEdits: RigEditOverlay = RigEditOverlay.Empty): WorkspaceCommit<RigPreviewModel> {
        val before = runtime.state.value
        currentCoroutineContext().ensureActive()
        // Capture before source I/O. Another edit or reload cannot be hidden by taking a newer token.
        WorkspaceExecution(projectId, state, MutationAuthor.AGENT).check(before.capture?.projectId, before.state)
        if (before.capture?.dirty == true && !discardUnsaved) throw WorkspaceUnsavedChanges()
        suspend fun progress(value: Float, message: String) {
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[WorkspaceJobContext]?.progress(value, message)
        }
        progress(0.1f, "Reading source artwork")
        val (source, overrides) = prepare()
        progress(0.4f, "Preparing new source document")
        val config = initialConfig.copy(layerVisibility = emptyMap(), deletedLayerIds = emptySet(),
            layerOverrides = overrides, parentOverrides = emptyMap(), drawOrderOverrides = emptyMap(), meshOverrides = emptyMap(),
            rigEdits = rigEdits, generationSource = null, meshSource = null, hairSimulationFront = false, hairSimulationBack = false)
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), rigEdits,
            WorkspaceSettingsCodec.encode(config))
        progress(0.6f, "Rebuilding new source project")
        val built = if (config.autoSkeleton == null && config.layerSwings.isEmpty()) build(document)
            else LayerloomJob.setUp(build(document), config) { next -> build(document.copy(rigEdits = next.rigEdits)) }
        // A new project keeps its first layout: the atlas is arranged on request, not on every edit. The stored
        // arrangement is that layout exactly, so the model it builds is this one.
        val arrangement = AtlasLayout.frozen(built.atlas)
        val preview = built.copy(config = built.config.copy(atlasArrangement = arrangement), atlas = built.atlas.copy(arranged = true))
        // The preview was generated from effective settings; the document keeps the raw ones it was given.
        val prepared = document.copy(rigEdits = preview.config.rigEdits,
            settings = AtlasArrangementCodec.with(WorkspaceSettingsCodec.encode(WorkspaceSettingsPolicy.restoreRaw(preview.config, config)), arrangement))
        progress(0.9f, "Installing new source project")
        val id = newProjectId()
        val installed = runtime.install(state, id, prepared, preview,
            auxiliary = JsonObject(sourceAuxiliary + WorkspaceAuxiliaryCodec.encode(WorkspaceAuxiliaryData()) +
                PreviewSessions.initial(preview.rig.puppet.parameters, initialWorkspaceIds()) +
                ("assetCatalog" to WorkspaceAssetCatalog().encode())), discardUnsaved = discardUnsaved, dirty = true) {
            project(prepared, preview, id)
        }
        val result = WorkspaceCommit(installed, true)
        currentCoroutineContext()[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(result.mutation(summary).sourceResult()))
        return result
    }
}
