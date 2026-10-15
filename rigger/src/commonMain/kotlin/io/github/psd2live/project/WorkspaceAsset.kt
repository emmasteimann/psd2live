package io.github.psd2live.project


data class WorkspacePngAsset(
	val public: WorkspaceImportedPngAsset,
	val rgba: ByteArray,
    val originalPng: ByteArray? = null,
)

fun WorkspacePngAsset.preview(): WorkspaceAssetPreview {
    val png = encodeStoredPng(public.pixelWidth, public.pixelHeight, rgba)
    var transparent = 0
    var translucent = 0
    for (i in 3 until rgba.size step 4) {
        val alpha = rgba[i].toInt() and 255
        if (alpha == 0) transparent++ else if (alpha < 255) translucent++
    }
    return WorkspaceAssetPreview(public, png, transparent, translucent, originalPng)
}
