package io.github.psd2live.project

import kotlinx.serialization.json.*

/** Optional v1 auxiliary index. Only these immutable authoring records belong to this capture. */
data class WorkspaceAssetCatalog(val assets: Set<String> = emptySet(), val workflow: Set<String> = emptySet()) {
    fun encode(): JsonObject = buildJsonObject {
        put("version", 1)
        putJsonArray("assets") { assets.sorted().forEach { add(it) } }
        putJsonArray("workflow") { workflow.sorted().forEach { add(it) } }
    }

    companion object {
        fun read(auxiliary: JsonObject): WorkspaceAssetCatalog? = auxiliary["assetCatalog"]?.jsonObject?.let { value ->
            require(value.getValue("version").jsonPrimitive.int == 1) { "Unsupported asset catalog version" }
            fun ids(field: String): Set<String> {
                val ids = value.getValue(field).jsonArray.map { it.jsonPrimitive.content }
                require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "Invalid asset catalog $field" }
                return ids.toSet()
            }
            WorkspaceAssetCatalog(ids("assets"), ids("workflow"))
        }
    }
}

/** Storage used by asset preparation; application candidates collect writes before publication. */
interface WorkspaceAssetRepository {
    fun loadWorkflow(projectId: String, id: String): JsonObject
    fun loadAsset(projectId: String, assetId: String): WorkspacePngAsset?
    fun registrationsForAsset(projectId: String, assetId: String): List<JsonObject>
    fun loadSpatial(projectId: String, viewId: String): WorkspaceViewSpatialMetadata?
    fun persistWorkflow(projectId: String, id: String, value: JsonObject)
    fun persistAsset(projectId: String, asset: WorkspacePngAsset)
    fun persistSpatial(projectId: String, viewId: String, spatial: WorkspaceViewSpatialMetadata)
}

class WorkspaceAssetStage(private val directory: Path, val source: Path,
                                  private val owner: WorkspaceStore, private val projectId: String) : AutoCloseable {
    fun publish(checkCancelled: () -> Unit, beforePublished: () -> Unit) = owner.publishAssetStage(this, projectId, checkCancelled, beforePublished)
    override fun close() = owner.deleteAssetStage(directory, projectId)
}
