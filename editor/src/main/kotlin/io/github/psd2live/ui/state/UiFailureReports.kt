package io.github.psd2live.ui.state
import io.github.psd2live.project.Files
import io.github.psd2live.project.Path
import io.github.psd2live.project.parent
import io.github.psd2live.project.pathOf

import androidx.compose.ui.window.WindowExceptionHandler
import java.time.Instant

/** Keeps the full exception even when an installed desktop app has no visible stderr console. */
class UiFailureReports(
    val path: Path = pathOf(System.getProperty("user.home"), ".psd2live", "logs", "ui-error.log"),
) {
    @Synchronized fun record(failure: Throwable, origin: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, buildString {
            appendLine("PSD2Live UI failure · ${Instant.now()} · $origin")
            appendLine("Thread: ${Thread.currentThread().name}")
            appendLine("Java: ${System.getProperty("java.version")} · OS: ${System.getProperty("os.name")}")
            append(failure.stackTraceToString())
        })
    }
}

/** Report before Compose shows its modal error dialog; reporting cannot change the original exception handling. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun reportingWindowExceptionHandler(delegate: WindowExceptionHandler, report: (Throwable) -> Unit): WindowExceptionHandler =
    WindowExceptionHandler { failure ->
        runCatching { report(failure) }.onFailure { it.printStackTrace() }
        delegate.onException(failure)
    }
