package io.github.psd2live.core

import kotlinx.serialization.Serializable

/** Local, optional nunif runtime. Paths are explicit: never silently select/download a model. */
@Serializable
data class TextureUpscaleConfig(
    val scale: Int = 1,
    val python: String = "python",
    val nunifDirectory: String = "",
    val modelDirectory: String = "",
    val tileSize: Int = 256,
    val noiseLevel: Int = 1,
    val neuralAlpha: Boolean = true,
) {
    init {
        require(scale in listOf(1, 2, 4)) { "Texture scale must be 1, 2 or 4" }
        require(tileSize in 64..512) { "Upscale tile size must be 64..512" }
        require(noiseLevel in -1..3) { "Noise level must be -1..3" }
    }

    companion object {
        /** The interpreter command to try by name: macOS and most Linux distributions have no `python`. */
        val defaultPython: String get() = platformDefaultPython()

        fun detectAvailablePython(): String = platformDetectPython()

        /** Explicit UI shortcut for the conventional optional runtime installation. */
        fun localRuntime(): TextureUpscaleConfig? = platformLocalTextureRuntime()
    }
}

// The upscaler is a desktop job (a local Python runtime); the iPad upscales in the studio instead.
internal expect fun platformDefaultPython(): String
internal expect fun platformDetectPython(): String
internal expect fun platformLocalTextureRuntime(): TextureUpscaleConfig?
