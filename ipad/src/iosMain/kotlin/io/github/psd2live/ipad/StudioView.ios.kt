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
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigation
import platform.Foundation.NSError
import platform.darwin.NSObject

/** The scheme the bundled studio is served under, so it has an origin of its own (storage, module scripts). */
private const val SCHEME = "layerloom"

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
	override fun webViewWebContentProcessDidTerminate(webView: WKWebView) { load(webView) }

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
	private val handler = StudioFiles()
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
