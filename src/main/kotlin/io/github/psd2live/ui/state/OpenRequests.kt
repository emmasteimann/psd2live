package io.github.psd2live.ui.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries

/**
 * Layerloom patch (ONE-APP-PLAN S2): "Open in PSD2Live". Another program (Layerloom's studio) asks the editor to
 * open a PSD by dropping `{"psd": "/abs/path.psd"}` into [directory]. A running editor picks it up within a
 * second, and a starting one picks up what's waiting. The request goes through the editor's own Import PSD
 * ([PSD2LiveViewModel.analyze]): its unsaved-changes prompt, and the Layerloom rig job beside the PSD.
 */
internal object OpenRequests {
	/** Beside the editor's workspace store (`~/.psd2live/open-requests`). */
	fun directory(): Path = io.github.psd2live.project.WorkspaceStore.defaultRoot().toAbsolutePath().parent.resolve("open-requests")

	fun watch(viewModel: PSD2LiveViewModel) {
		val dir = directory()
		Thread({
			while (true) {
				runCatching { next(dir)?.let { psd -> javax.swing.SwingUtilities.invokeLater { open(viewModel, psd) } } }
					.onFailure { System.err.println("Open request: ${it.message}") }
				Thread.sleep(1000)
			}
		}, "psd2live-open-requests").apply { isDaemon = true }.start()
	}

	/** The oldest waiting request's PSD (the request file is removed), or null. */
	private fun next(dir: Path): Path? {
		if (!Files.isDirectory(dir)) return null
		val file = dir.listDirectoryEntries().filter { it.extension == "json" }.minByOrNull { Files.getLastModifiedTime(it) } ?: return null
		val psd = runCatching { (Json.parseToJsonElement(Files.readString(file)) as JsonObject)["psd"]?.jsonPrimitive?.contentOrNull }.getOrNull()
		Files.deleteIfExists(file)
		return psd?.let(Path::of)?.takeIf { it.isAbsolute && Files.isRegularFile(it) && it.toString().endsWith(".psd", true) }
	}

	private fun open(viewModel: PSD2LiveViewModel, psd: Path) {
		viewModel.setInputPath(psd.toString())
		viewModel.analyze()
		// From the Prepare workspace (Layerloom inside the app), go where the rig is.
		val state = viewModel.state.value
		if (state.activeWorkspace.preset == WorkspacePreset.PREPARE) {
			state.workspaces.firstOrNull { it.preset != WorkspacePreset.PREPARE }?.let { viewModel.setActiveWorkspace(it.id) }
		}
	}
}
