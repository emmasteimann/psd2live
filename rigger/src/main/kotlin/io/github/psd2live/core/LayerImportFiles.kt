package io.github.psd2live.core

import java.io.File

// The desktop's side of image import: reading files. Decoding is shared (LayerImport.decodeRasterBytes).

fun LayerImport.isTransparentRasterFile(file: File): Boolean {
	if (!file.isFile) return false
	if (file.extension.lowercase() in TRANSPARENT_EXTENSIONS) return true
	return runCatching { isTransparentRaster(file.inputStream().use { it.readNBytes(64) }, file.name) }.getOrDefault(false)
}

fun LayerImport.transparentRasterFiles(files: List<File>): List<File> = files.filter { isTransparentRasterFile(it) }

fun LayerImport.decodeRasterFile(file: File, checkCancelled: () -> Unit = {}): org.umamo.format.raster.RasterImage {
	checkCancelled()
	require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "Image must be a readable file of at most 64 MiB" }
	val bytes = file.inputStream().use { input ->
		val output = java.io.ByteArrayOutputStream()
		val buffer = ByteArray(8192)
		while (true) {
			checkCancelled()
			val count = input.read(buffer)
			if (count < 0) break
			require(output.size().toLong() + count <= MAX_BYTES) { "Image exceeds 64 MiB" }
			output.write(buffer, 0, count)
		}
		output.toByteArray()
	}
	return decodeRasterBytes(bytes, file.name, checkCancelled)
}

fun LayerImport.displayNameOf(file: File): String = file.nameWithoutExtension.ifBlank { file.name }
