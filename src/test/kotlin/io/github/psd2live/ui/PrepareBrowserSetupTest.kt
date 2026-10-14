package io.github.psd2live.ui

import dev.datlag.kcef.KCEF
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Layerloom patch (ONE-APP-PLAN S3): the Prepare workspace's embedded browser downloads and starts on this machine,
 * into the same folder the app uses. Downloads about 150 MB the first time, so it only runs with
 * PSD2LIVE_CHECK_KCEF=1. It doesn't open a window (that needs the app).
 */
class PrepareBrowserSetupTest {
	@Test
	fun theEmbeddedBrowserInstallsAndStarts() = runBlocking {
		if (System.getenv("PSD2LIVE_CHECK_KCEF") != "1") return@runBlocking
		val home = File(System.getProperty("user.home"), ".psd2live/kcef")
		var failed: Throwable? = null
		var initialized = false
		KCEF.init(builder = {
			installDir(File(home, "bundle"))
			progress {
				onDownloading { println("KCEF download ${it.toInt()}%") }
				onInitialized { initialized = true }
			}
			settings { cachePath = File(home, "cache").absolutePath }
		}, onError = { failed = it }, onRestartRequired = { println("KCEF: restart required") })
		assertNull(failed, "KCEF failed: ${failed?.message}")
		assertTrue(initialized || KCEF.newClientOrNullBlocking() != null, "KCEF didn't initialize")
		KCEF.disposeBlocking()
	}
}
