package io.github.psd2live.application

/** Raster work has no UI ownership; hosts can observe progress while cancellation checks stay shared. */
interface WorkspaceRasterWork {
    fun checkpoint()
    fun progress(fraction: Float, message: String)

    object Direct : WorkspaceRasterWork {
        override fun checkpoint() {}
        override fun progress(fraction: Float, message: String) {}
    }
}
