package io.github.psd2live.project

import kotlinx.serialization.json.*

/** Immutable import bytes owned by the workspace; external paths are presentation/provenance only. */
data class OwnedProjectSource(val path: Path, val descriptor: JsonObject) {
    val auxiliary: JsonObject get() = buildJsonObject { put(KEY, descriptor) }

    companion object {
        const val KEY = "originalSource"

        fun capture(root: Path, input: Path): OwnedProjectSource {
            val format = input.fileName.toString().substringAfterLast('.').lowercase()
            require(input.isAbsolute && Files.isRegularFile(input) && format in setOf("psd", "cmo3")) {
                "Provide an absolute PSD or CMO3 source path"
            }
            val directory = root.toAbsolutePath().normalize().resolve("sources")
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "import-", ".$format")
            try {
                val hash = copyWithSha256(input, temporary)
                val owned = directory.resolve("$hash.$format")
                if (!Files.isRegularFile(owned)) Files.move(temporary, owned, StandardCopyOption.ATOMIC_MOVE)
                val descriptor = buildJsonObject { put("format", format); put("sha256", hash) }
                return OwnedProjectSource(owned, descriptor)
            } finally { Files.deleteIfExists(temporary) }
        }

        fun resolve(root: Path, auxiliary: JsonObject): Path? {
            val value = auxiliary[KEY] as? JsonObject ?: return null
            val format = value.getValue("format").jsonPrimitive.content
            val hash = value.getValue("sha256").jsonPrimitive.content
            require(format in setOf("psd", "cmo3") && hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid original source descriptor" }
            return root.toAbsolutePath().normalize().resolve("sources/$hash.$format").also {
                require(Files.isRegularFile(it)) { "Owned original source is unavailable: $it" }
            }
        }
    }
}
