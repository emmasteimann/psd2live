package io.github.psd2live.ui.state

import androidx.compose.ui.window.WindowExceptionHandler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Keeps the full exception even when an installed desktop app has no visible stderr console. */
class UiFailureReports(
    val path: Path = Path.of(System.getProperty("user.home"), ".psd2live", "logs", "ui-error.log"),
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
