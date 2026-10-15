package io.github.psd2live.ipad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun StudioView(modifier: Modifier) {
	val handler = remember { StudioFiles() }
	UIKitView(
		factory = {
			val configuration = WKWebViewConfiguration().apply {
				setURLSchemeHandler(handler, forURLScheme = SCHEME)
				// Saved projects live in the app's own persistent store.
				websiteDataStore = WKWebsiteDataStore.defaultDataStore()
			}
			WKWebView(frame = kotlinx.cinterop.cValue { }, configuration = configuration).apply {
				setInspectable(true)
				loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString("$SCHEME://studio/")!!))
			}
		},
		modifier = modifier,
	)
}
