package io.github.psd2live.core
import io.github.psd2live.project.Files
import io.github.psd2live.project.Path
import io.github.psd2live.project.StandardCopyOption
import io.github.psd2live.project.resolve
import io.github.psd2live.project.parent
import io.github.psd2live.project.toAbsolutePath
import io.github.psd2live.project.normalize
import io.github.psd2live.project.startsWith


/**
 * Replaces an export directory's previous result with a staged one.
 *
 * The new files and the loss report move in together. A failure restores every file this publish changed
 * and leaves files the previous report did not list alone. Frames the previous report listed and this
 * export no longer writes are removed, so a shorter sequence does not keep the old tail.
 */
object ExportPublication {
	fun commit(
		directory: Path, stage: Path, relativeFiles: List<String>, reportName: String, reportJson: String,
		move: (Path, Path) -> Unit = { source, target -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) },
	) {
		val root = directory.toAbsolutePath().normalize()
		val staged = stage.toAbsolutePath().normalize()
		Files.createDirectories(root)
		val report = staged.resolve(reportName).normalize()
		require(report.startsWith(staged)) { "Export report escapes the staging directory" }
		Files.writeString(report, reportJson)
		val next = (relativeFiles + reportName).distinct()
		val stale = previousFiles(root.resolve(reportName)).filter { it !in next.toSet() }
		val moves = next.map { relative ->
			val source = staged.resolve(relative).normalize()
			val target = root.resolve(relative).normalize()
			require(!relative.startsWith('/') && !relative.contains('\\') && source.startsWith(staged)) { "Staged export escapes: $relative" }
			require(target.startsWith(root)) { "Export path escapes the output: $relative" }
			require(Files.isRegularFile(source)) { "Missing staged export file: $relative" }
			source to target
		}
		val removals = stale.map { relative ->
			val target = root.resolve(relative).normalize()
			require(target.startsWith(root)) { "Stale export path escapes: $relative" }
			target
		}
		replace(moves, removals, move)
	}

	/** The `files` array of a report this publisher wrote, or empty when there is no readable report. */
	fun previousFiles(report: Path): List<String> {
		if (!Files.isRegularFile(report)) return emptyList()
		val text = runCatching { Files.readString(report) }.getOrNull() ?: return emptyList()
		val marker = "\"files\":["
		val start = text.indexOf(marker)
		if (start < 0) return emptyList()
		val end = text.indexOf(']', start + marker.length)
		if (end < 0) return emptyList()
		return Regex("\"((?:\\\\.|[^\"\\\\])*)\"").findAll(text.substring(start + marker.length, end)).map { match ->
			match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
		}.toList()
	}

	private fun replace(moves: List<Pair<Path, Path>>, removals: List<Path>, move: (Path, Path) -> Unit) {
		val backups = linkedMapOf<Path, Path?>()
		val created = mutableListOf<Path>()
		val deleted = mutableListOf<Path>()
		val keepBackups = HashSet<Path>()
		try {
			(moves.map { it.second } + removals).distinct().forEach { target ->
				if (target in backups) return@forEach
				Files.createDirectories(target.parent)
				val backup = if (Files.exists(target)) {
					require(Files.isRegularFile(target)) { "An export destination is not a file: $target" }
					Files.createTempFile(target.parent, ".psd2live-backup-", ".tmp").also {
						Files.copy(target, it, StandardCopyOption.REPLACE_EXISTING)
					}
				} else null
				backups[target] = backup
			}
			moves.forEach { (source, target) ->
				if (backups[target] == null) created.add(target)
				move(source, target)
			}
			removals.forEach { target ->
				if (Files.deleteIfExists(target)) deleted.add(target)
			}
		} catch (failure: Exception) {
			deleted.asReversed().forEach { target ->
				val backup = backups[target] ?: return@forEach
				runCatching { Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING) }
					.exceptionOrNull()?.let { restore -> keepBackups.add(backup); failure.addSuppressed(restore) }
			}
			created.asReversed().forEach { target ->
				runCatching { Files.deleteIfExists(target) }.exceptionOrNull()?.let(failure::addSuppressed)
			}
			moves.asReversed().forEach { (_, target) ->
				if (target in created || target in deleted) return@forEach
				val backup = backups[target] ?: return@forEach
				runCatching { Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING) }
					.exceptionOrNull()?.let { restore -> keepBackups.add(backup); failure.addSuppressed(restore) }
			}
			throw failure
		} finally {
			backups.values.filterNotNull().filter { it !in keepBackups }.forEach { runCatching { Files.deleteIfExists(it) } }
		}
	}
}
