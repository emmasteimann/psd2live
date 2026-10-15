package io.github.psd2live.ipad

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataUsingEncoding
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigation
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

/** The scheme the bundled studio is served under, so it has an origin of its own (storage, module scripts). */
private const val SCHEME = "layerloom"
private const val COPY_PAGE = "_copy-projects"

/**
 * Serves the studio's built files (the app bundle's `studio/` folder, the layerloom repo's `dist/client`) under
 * `layerloom://studio/...`. A path without a file is the app itself (index.html), as the hosted worker answers.
 */
@OptIn(ExperimentalForeignApi::class)
private class StudioFiles : NSObject(), WKURLSchemeHandlerProtocol {
	private val root: String? = NSBundle.mainBundle.resourcePath?.let { "$it/studio" }

	@ObjCSignatureOverride
	override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
		val url = startURLSchemeTask.request.URL ?: return
		val path = url.path.orEmpty().trimStart('/').ifEmpty { "index.html" }
		if (path == COPY_PAGE) {
			// An empty page in the studio's origin, so the native studio can read the saved projects without running the app.
			val headers = mapOf<Any?, Any?>("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store")
			startURLSchemeTask.didReceiveResponse(NSHTTPURLResponse(uRL = url, statusCode = 200, HTTPVersion = "HTTP/1.1", headerFields = headers)!!)
			startURLSchemeTask.didReceiveData(@Suppress("CAST_NEVER_SUCCEEDS") ("<!doctype html><title>copy</title>" as platform.Foundation.NSString)
				.dataUsingEncoding(platform.Foundation.NSUTF8StringEncoding)!!)
			startURLSchemeTask.didFinish()
			return
		}
		val safe = !path.split('/').any { it == ".." }
		var file = if (safe && root != null) "$root/$path" else null
		var data = file?.let { NSData.dataWithContentsOfFile(it) }
		if (data == null && safe && root != null && '.' !in path.substringAfterLast('/')) {
			file = "$root/index.html"
			data = NSData.dataWithContentsOfFile(file)
		}
		if (data == null) {
			val missing = NSHTTPURLResponse(uRL = url, statusCode = 404, HTTPVersion = "HTTP/1.1", headerFields = null)
			startURLSchemeTask.didReceiveResponse(missing!!)
			startURLSchemeTask.didFinish()
			return
		}
		val headers = mapOf<Any?, Any?>("Content-Type" to mimeOf(file!!), "Cache-Control" to "no-store")
		val response = NSHTTPURLResponse(uRL = url, statusCode = 200, HTTPVersion = "HTTP/1.1", headerFields = headers)
		startURLSchemeTask.didReceiveResponse(response!!)
		startURLSchemeTask.didReceiveData(data)
		startURLSchemeTask.didFinish()
	}

	@ObjCSignatureOverride
	override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {}

	private fun mimeOf(file: String): String = when (file.substringAfterLast('.').lowercase()) {
		"html" -> "text/html; charset=utf-8"
		"js", "mjs" -> "text/javascript; charset=utf-8"
		"css" -> "text/css; charset=utf-8"
		"json", "webmanifest" -> "application/json"
		"png" -> "image/png"
		"jpg", "jpeg" -> "image/jpeg"
		"webp" -> "image/webp"
		"svg" -> "image/svg+xml"
		"wasm" -> "application/wasm"
		"onnx", "bin" -> "application/octet-stream"
		"woff2" -> "font/woff2"
		else -> "application/octet-stream"
	}
}

/**
 * Brings the studio back when its page goes away. iOS kills a web view's content process to free memory (often while
 * the app is in the background or busy rigging), which leaves the view black until it is reloaded; a load that fails
 * is retried too.
 */
@OptIn(ExperimentalForeignApi::class)
private class StudioKeeper : NSObject(), WKNavigationDelegateProtocol {
	/** When the page last died, to tell one memory squeeze from a page that dies every time it loads. */
	private val deaths = ArrayDeque<Double>()

	override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
		val now = platform.Foundation.NSDate().timeIntervalSince1970
		deaths.addLast(now)
		while (deaths.isNotEmpty() && now - deaths.first() > 120.0) deaths.removeFirst()
		// Reloading a page that keeps running out of memory would just reset the screen forever.
		if (deaths.size >= 3) { deaths.clear(); stalled(webView) } else load(webView)
	}

	private fun stalled(webView: WKWebView) {
		webView.loadHTMLString("""
			<meta name="viewport" content="width=device-width"><body style="background:#1b1b24;color:#ddd;font:20px -apple-system;
			display:flex;flex-direction:column;align-items:center;justify-content:center;height:90vh;text-align:center">
			<p>The studio page keeps running out of memory on this iPad.<br>Your saved project is safe.</p>
			<a href="$SCHEME://studio/" style="color:#b9a3ff;font-size:22px">Try again</a></body>
		""".trimIndent(), baseURL = null)
	}

	@ObjCSignatureOverride
	override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) { retry(webView, withError) }

	@ObjCSignatureOverride
	override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) { retry(webView, withError) }

	private var retries = 0

	override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) { retries = 0 }

	private fun retry(webView: WKWebView, error: NSError) {
		// A load cancelled by a newer one is not a failure, and a page that keeps failing stops after a few tries.
		if (error.code == -999L || retries++ >= 5) return
		platform.darwin.dispatch_after(platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 500_000_000L),
			platform.darwin.dispatch_get_main_queue()) { load(webView) }
	}

	fun load(webView: WKWebView) { webView.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString("$SCHEME://studio/")!!)) }
}

/** One studio for the app's life: switching to the Rig tab and back keeps it as it was instead of reloading it. */
@OptIn(ExperimentalForeignApi::class)
private object Studio {
	val handler = StudioFiles()
	private val keeper = StudioKeeper()
	val view: WKWebView by lazy {
		val configuration = WKWebViewConfiguration().apply {
			setURLSchemeHandler(handler, forURLScheme = SCHEME)
			// Saved projects live in the app's own persistent store.
			websiteDataStore = WKWebsiteDataStore.defaultDataStore()
		}
		WKWebView(frame = kotlinx.cinterop.cValue { }, configuration = configuration).apply {
			setInspectable(true)
			navigationDelegate = keeper
			// The app's own dark, not black, while the page loads.
			setOpaque(false)
			backgroundColor = platform.UIKit.UIColor(red = 0.106, green = 0.106, blue = 0.141, alpha = 1.0)
			keeper.load(this)
		}
	}

	/** Called whenever the tab shows: a page that died while hidden (no URL left) loads again. */
	fun ensureLoaded() { if (view.URL == null) keeper.load(view) }
}

@OptIn(ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun StudioView(modifier: Modifier) {
	UIKitView(
		factory = { Studio.view.also { it.removeFromSuperview(); Studio.ensureLoaded() } },
		modifier = modifier,
		// The studio takes every touch at once: Compose's default holds touches 150 ms to decide whether it scrolls,
		// which delayed taps and would make Pencil strokes lag. Nothing of Compose's scrolls over the studio.
		properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
	)
}

/**
 * Reads the web studio's saved projects (IndexedDB `layerloom-studio`, store `projects`) out of the web view's storage
 * for the native studio: a hidden web view opens an empty page in the studio's origin and returns each slot as JSON.
 */
@OptIn(ExperimentalForeignApi::class)
actual object WebStudioCopy {
	private var view: WKWebView? = null
	private var delegate: NSObject? = null

	actual fun read(onDone: (slots: Map<String, String>, error: String?) -> Unit) {
		val configuration = WKWebViewConfiguration().apply {
			setURLSchemeHandler(Studio.handler, forURLScheme = SCHEME)
			websiteDataStore = WKWebsiteDataStore.defaultDataStore()
		}
		val web = WKWebView(frame = kotlinx.cinterop.cValue { }, configuration = configuration)
		val slots = listOf("current", "recovery", "previous")
		val found = mutableMapOf<String, String>()
		fun finish(error: String?) { view = null; delegate = null; onDone(found, error) }
		fun next(index: Int) {
			if (index == slots.size) { finish(null); return }
			val script = """
				const db = await new Promise((ok, fail) => { const r = indexedDB.open("layerloom-studio", 1);
					r.onupgradeneeded = () => r.result.createObjectStore("projects"); r.onsuccess = () => ok(r.result); r.onerror = () => fail(r.error); });
				const value = await new Promise((ok, fail) => { const t = db.transaction("projects"); const q = t.objectStore("projects").get(slot);
					q.onsuccess = () => ok(q.result); q.onerror = () => fail(q.error); });
				db.close();
				return value ? JSON.stringify(value) : "";
			""".trimIndent()
			web.callAsyncJavaScript(script, arguments = mapOf<Any?, Any?>("slot" to slots[index]), inFrame = null,
				inContentWorld = platform.WebKit.WKContentWorld.pageWorld) { result, error ->
				if (error != null) { finish(error.localizedDescription); return@callAsyncJavaScript }
				(result as? String)?.takeIf { it.isNotEmpty() }?.let { found[slots[index]] = it }
				next(index + 1)
			}
		}
		val keeper = object : NSObject(), WKNavigationDelegateProtocol {
			override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) { next(0) }
			@ObjCSignatureOverride
			override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) { finish(withError.localizedDescription) }
			@ObjCSignatureOverride
			override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) { finish(withError.localizedDescription) }
		}
		view = web; delegate = keeper
		web.navigationDelegate = keeper
		web.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString("$SCHEME://studio/$COPY_PAGE")!!))
	}
}
