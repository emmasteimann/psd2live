package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.cef.browser.CefRendering
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Layerloom patch (ONE-APP-PLAN S3): the Prepare workspace, Layerloom's studio inside the editor. The studio is a
 * local web app (its relay on 127.0.0.1:4180, which also runs its Python pipeline and paid relay); this panel starts
 * the relay when it isn't running and shows the studio in an embedded Chromium (KCEF). "Open in PSD2Live" in the
 * studio reaches this same app through its open-request inbox (ui/state/OpenRequests).
 */
@Composable
internal fun PrepareStudioView(modifier: Modifier = Modifier) {
	var status by remember { mutableStateOf(PrepareStudio.status) }
	var component by remember { mutableStateOf(PrepareStudio.component) }
	LaunchedEffect(Unit) {
		if (component == null) component = withContext(Dispatchers.IO) { PrepareStudio.open { status = it } }
	}
	val ui = component
	if (ui != null) SwingPanel(factory = { ui }, modifier = modifier)
	else Box(modifier, contentAlignment = Alignment.Center) {
		Column(Modifier.padding(24.dp)) { Text(status) }
	}
}

/** One studio relay and one browser for the whole app; the panel can be closed and shown again. */
internal object PrepareStudio {
	private const val URL = "http://127.0.0.1:4180/"
	@Volatile var component: java.awt.Component? = null; private set
	@Volatile var status: String = "Starting Layerloom…"; private set
	private var relay: Process? = null

	private fun layerloomDir(): File = File(System.getenv("LAYERLOOM_DIR")
		?: (System.getProperty("user.home") + "/Downloads/neuro-goose/layerloom"))

	private fun reachable(): Boolean = runCatching {
		(URI(URL).toURL().openConnection() as HttpURLConnection).run {
			connectTimeout = 800; readTimeout = 800; responseCode in 200..499
		}
	}.getOrDefault(false)

	private fun node(): String = listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")
		.firstOrNull { File(it).canExecute() } ?: "node"

	private val opening = kotlinx.coroutines.sync.Mutex()

	suspend fun open(report: (String) -> Unit): java.awt.Component? = opening.withLock { openOnce(report) }

	private suspend fun openOnce(report: (String) -> Unit): java.awt.Component? {
		component?.let { return it }
		fun say(text: String) { status = text; report(text) }
		if (!reachable()) {
			val dir = layerloomDir()
			if (!File(dir, "scripts/open-private-studio.mjs").isFile) { say("Layerloom isn't at $dir (set LAYERLOOM_DIR)."); return null }
			say("Starting Layerloom's studio…")
			relay = ProcessBuilder(node(), "scripts/open-private-studio.mjs").directory(dir)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start()
			Runtime.getRuntime().addShutdownHook(Thread { relay?.destroy() })
			repeat(60) { if (!reachable()) delay(500) }
			if (!reachable()) { say("Layerloom's studio didn't start (node scripts/open-private-studio.mjs in $dir)."); return null }
		}
		say("Preparing the embedded browser (the first time downloads it, about 150 MB)…")
		val home = File(System.getProperty("user.home"), ".psd2live/kcef")
		var failed: Throwable? = null
		KCEF.init(builder = {
			installDir(File(home, "bundle"))
			progress {
				onDownloading { say("Downloading the embedded browser… ${it.toInt()}%") }
				onExtracting { say("Unpacking the embedded browser…") }
				onInitialized { say("Opening Layerloom…") }
			}
			settings { cachePath = File(home, "cache").absolutePath }
		}, onError = { failed = it }, onRestartRequired = { say("Restart PSD2Live to finish setting up the embedded browser.") })
		failed?.let { say("The embedded browser didn't start: ${it.message}"); return null }
		val client = KCEF.newClientOrNullBlocking() ?: run { say("The embedded browser didn't start."); return null }
		val browser = client.createBrowser(URL, CefRendering.DEFAULT, false)
		return browser.uiComponent.also { component = it }
	}
}
