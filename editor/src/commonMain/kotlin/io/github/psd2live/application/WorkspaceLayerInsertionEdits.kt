package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import org.umamo.runtime.model.*

/** Source additions keep the preceding geometry input, rig frames and object identities. */
object WorkspaceLayerInsertionEdits {
    fun freeze(document: WorkspaceDocument, model: RigPreviewModel) = document.copy(
        rigEdits = RigLayerDeletion.preserve(model, document.config()),
        generationSource = document.generationSource ?: document.source)

    /**
     * The mesh ID each of [ids] gets: the one its mesh already has, else a fresh `ArtMeshImage…` no rig, journal record or
     * other layer holds. A one-layer build alone would name every image the same.
     */
    private fun meshIds(document: WorkspaceDocument, current: RigPreviewModel, ids: Set<String>): Map<String, String> {
        val existing = current.rig.layerIdByDrawableId.entries.filter { it.value in ids }.groupBy({ it.value }, { it.key })
        val taken = HashSet<String>()
        current.rig.puppet.drawables.mapTo(taken) { it.id.raw }
        current.authored.rig.puppet.drawables.mapTo(taken) { it.id.raw }
        document.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP }
            .mapTo(taken) { it.getValue("id").jsonPrimitive.content }
        taken += document.rigEdits.splitDrawableIds.values
        return ids.associateWith { layer ->
            document.rigEdits.splitDrawableIds[layer] ?: existing[layer]?.singleOrNull()
                ?: StableIds.fresh("ArtMeshImage") { it in taken }.also { taken += it }
        }
    }

    /** Generated root geometry is converted through the actual parent's neutral transform. */
    fun materialize(document: WorkspaceDocument, current: RigPreviewModel, ids: Set<String>, parent: String?,
                            checkCancelled: () -> Unit): WorkspaceDocument {
        checkCancelled()
        val source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
            document.source.layers.filter { it.id.raw in ids }, document.source.groups)
        val config = document.config().copy(meshOnly = true, generateDeformers = false, generatePhysics = false,
            parentOverrides = emptyMap(), generationSource = null, meshSource = null, deletedLayerIds = emptySet(),
            rigEdits = RigEditOverlay.Empty.copy(splitDrawableIds = meshIds(document, current, ids)))
        val generated = PSD2LivePipeline().buildPreview(source, config, ProgressListener { _, _ -> checkCancelled() })
        val parentId = parent?.let(::DeformerId)
        val transforms = document.source.layers.filter { it.id.raw in ids }.associate { it.id.raw to it.transform }
        val rig = RasterMeshPlacement.underParents(generated.rig, current.rig.puppet, { parentId },
            { mesh -> generated.rig.layerIdByDrawableId[mesh.raw]?.let(transforms::get) ?: LayerTransform.IDENTITY }, checkCancelled)
        // Built alone, a new layer's meshes are ranked first of one in a part of their own guess - the back of the figure,
        // or under a whole part drawn over them. They join the nearest layer under them in the stack instead: its part,
        // and its draw order (created after the generated meshes, on a tie they draw just above it), where it was put.
        val ordered = document.source.layers.sortedBy { it.order }
        val shown = current.rig.puppet.drawables.groupBy { current.rig.layerIdByDrawableId[it.id.raw] }
        fun under(layerId: String): org.umamo.runtime.model.Drawable? {
            val at = ordered.indexOfFirst { it.id.raw == layerId }
            if (at < 0) return null
            for (i in at + 1 until ordered.size) shown[ordered[i].id.raw]?.let { meshes -> return meshes.maxBy { it.drawOrder } }
            return null
        }
        val neighbour = rig.puppet.drawables.associate { drawable ->
            drawable.id to rig.layerIdByDrawableId[drawable.id.raw]?.takeUnless { config.drawOrderOverrides.containsKey(it) }?.let(::under)
        }
        val placed = rig.copy(puppet = rig.puppet.copy(drawables = rig.puppet.drawables.map { drawable ->
            neighbour[drawable.id]?.let { drawable.copy(drawOrder = it.drawOrder) } ?: drawable
        }))
        fun chain(id: org.umamo.runtime.model.DrawableId): List<org.umamo.runtime.model.Part> {
            val puppet = current.rig.puppet
            val parts = mutableListOf<org.umamo.runtime.model.Part>()
            var part = puppet.parts.singleOrNull { org.umamo.runtime.model.OrgChild.Drawable(id) in it.children }
            while (part != null && parts.none { it.id == part!!.id }) {
                val currentPart = part
                parts.add(0, currentPart)
                part = puppet.parts.singleOrNull { org.umamo.runtime.model.OrgChild.Part(currentPart.id) in it.children }
            }
            return parts
        }
        val records = placed.puppet.drawables.map { drawable ->
            checkCancelled()
            val home = neighbour[drawable.id]?.let { chain(it.id) }?.takeIf { it.isNotEmpty() }
            RasterMeshCreation.encode(placed, drawable.id).let { command ->
                if (home == null) command else JsonObject(command + ("part" to JsonPrimitive(home.last().id.raw)) +
                    ("parts" to JsonArray(home.map { buildJsonObject { put("id", it.id.raw); put("name", it.name) } })))
            }.let { command ->
                if (document.rigEdits.importedCmo3 == null) command else JsonObject(command +
                    ("source_id" to JsonPrimitive(CMO3_PAINT_SOURCE_ID)))
            }
        }
        val byId = records.associateBy { it.getValue("id").jsonPrimitive.content }
        val replaced = mutableSetOf<String>()
        // Replay starts at the checkpoint, so only a creation record after it is rewritten. A mesh the authored rig already
        // holds - its creation before the checkpoint, or generated from the layer and checkpointed since - gets a record
        // that replaces it: a rewrite before the checkpoint would never replay, and a second creation would collide.
        val checkpoint = document.rigEdits.checkpointIndex
        val journal = document.rigEdits.authoringJournal.mapIndexed { index, entry ->
            val id = entry["id"]?.jsonPrimitive?.content
            if (index > checkpoint && entry["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP && id in byId) {
                require(replaced.add(requireNotNull(id))) { "Duplicate mesh creation record" }
                byId.getValue(id).let { if (RasterMeshCreation.replaces(entry)) RasterMeshCreation.replacing(it) else it }
            } else entry
        } + records.filterNot { it.getValue("id").jsonPrimitive.content in replaced }.map { record ->
            val id = record.getValue("id").jsonPrimitive.content
            if (current.authored.rig.puppet.drawables.any { it.id.raw == id }) RasterMeshCreation.replacing(record) else record
        }
        // The generators never mesh a layer a creation record owns ([RigGenerationSource.createdCoverage]); no placeholder is stored.
        return document.copy(rigEdits = document.rigEdits.copy(authoringJournal = journal))
    }
}
