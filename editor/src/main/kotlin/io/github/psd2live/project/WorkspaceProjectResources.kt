package io.github.psd2live.project

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Registry of explicitly adopted archive directories; never scans or deletes arbitrary temp directories. */
class WorkspaceProjectResources(private val root: Path) {
    private val file = root.resolve("project-resources.json")
    private val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
    private val process = ProcessHandle.current()
    private val owner = "${process.pid()}|${process.info().startInstant().orElse(null)}"

    fun isManagedDirectory(path: Path): Boolean = path.isAbsolute && path.normalize().parent == temporaryRoot &&
        path.fileName.toString().startsWith("psd2live-project-")

    @Synchronized
    fun adopt(path: Path) {
        require(isManagedDirectory(path) && Files.isDirectory(path)) { "Only owned archive extraction directories can be registered" }
        val records = read().filterNot { it.getValue("path").jsonPrimitive.content == path.toString() }
        write(records + buildJsonObject { put("path", path.toString()); put("owner", owner) })
    }

    @Synchronized
    fun release(path: Path) {
        require(isManagedDirectory(path)) { "Archive resource is outside the managed temporary directory" }
        ProjectArchive.deleteTemporaryDirectory(path)
        write(read().filterNot { it.getValue("path").jsonPrimitive.content == path.toString() })
    }

    @Synchronized
    fun reclaimOrphans(recoveryRoot: Path?) {
        val kept = read().filter { record ->
            val path = Path.of(record.getValue("path").jsonPrimitive.content).normalize()
            val resourceOwner = record.getValue("owner").jsonPrimitive.content
            val pid = resourceOwner.substringBefore('|').toLongOrNull()?.takeIf { it > 0 }
            val alive = pid?.let { ProcessHandle.of(it).orElse(null) }?.let {
                it.isAlive && "$pid|${it.info().startInstant().orElse(null)}" == resourceOwner
            } == true
            if (!isManagedDirectory(path) || alive || recoveryRoot?.startsWith(path) == true) true
            else {
                ProjectArchive.deleteTemporaryDirectory(path)
                false
            }
        }
        if (Files.isRegularFile(file)) write(kept)
    }

    private fun read(): List<JsonObject> = if (!Files.isRegularFile(file)) emptyList()
        else ProjectArchive.readJson(file).getValue("directories").jsonArray.map { it.jsonObject }

    private fun write(records: List<JsonObject>) {
        Files.createDirectories(root)
        val temporary = Files.createTempFile(root, "resources-", ".tmp")
        try {
            Files.writeString(temporary, buildJsonObject {
                put("version", 1); put("directories", JsonArray(records))
            }.toString())
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
