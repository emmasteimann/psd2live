package io.github.psd2live.ipad.studio

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSinceNow
import platform.UIKit.UIApplication
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIBarButtonItemStyle
import platform.UIKit.UINavigationController
import platform.UIKit.UIViewController
import platform.UIKit.navigationItem
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

private const val COOKIE = "__Host-layerloom-owner"

/**
 * The private studio (the Cloudflare worker that makes the paid image edits). Signing in is the app's own sign-in
 * (AppSignIn: Apple's sign-in sheet on the worker's password page, OAuth code + PKCE) - the app never sees the
 * password and keeps a 90-day key in the Keychain. Requests go natively with that key and the studio's own Origin;
 * a 12-hour web cookie from the older sign-in still works until it runs out.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object PrivateStudio {
	// LAYERLOOM_STUDIO_URL points a simulator test at a local `wrangler dev`; the app itself always uses the real one.
	actual val url = platform.Foundation.NSProcessInfo.processInfo.environment["LAYERLOOM_STUDIO_URL"] as? String
		?: "https://layerloom-repair.emmagracesteimann.workers.dev"
	private val store get() = WKWebsiteDataStore.defaultDataStore().httpCookieStore

	private fun session(onDone: (NSHTTPCookie?) -> Unit) {
		store.getAllCookies { all ->
			val c = all?.mapNotNull { it as? NSHTTPCookie }?.firstOrNull { it.name == COOKIE && (it.expiresDate?.timeIntervalSinceNow ?: 1.0) > 60.0 }
			onDone(c)
		}
	}

	actual fun signedIn(onDone: (Boolean) -> Unit) {
		if (AppSignIn.key() != null) return onDone(true)
		session { c -> dispatch_async(dispatch_get_main_queue()) { onDone(c != null) } }
	}

	actual fun signIn(onDone: (Boolean) -> Unit) = AppSignIn.signIn(url) { ok, _ -> onDone(ok) }

	actual fun signOut(onDone: () -> Unit) {
		AppSignIn.forget()
		store.getAllCookies { all ->
			val c = all?.mapNotNull { it as? NSHTTPCookie }?.firstOrNull { it.name == COOKIE }
			if (c == null) dispatch_async(dispatch_get_main_queue()) { onDone() } else store.deleteCookie(c) { dispatch_async(dispatch_get_main_queue()) { onDone() } }
		}
	}

	actual fun post(path: String, contentType: String, body: ByteArray, onDone: (status: Int, text: String) -> Unit) {
		val appKey = AppSignIn.key()
		session { cookie ->
			if (appKey == null && cookie == null) { dispatch_async(dispatch_get_main_queue()) { onDone(401, "{\"error\":\"Sign in to your private studio first.\"}") }; return@session }
			val req = NSMutableURLRequest.requestWithURL(NSURL.URLWithString(url + path)!!)
			req.setHTTPMethod("POST")
			req.setValue(contentType, forHTTPHeaderField = "Content-Type")
			req.setValue(url, forHTTPHeaderField = "Origin")
			req.setValue("same-origin", forHTTPHeaderField = "Sec-Fetch-Site")
			if (appKey != null) req.setValue("Bearer $appKey", forHTTPHeaderField = "Authorization")
			else req.setValue("$COOKIE=${cookie!!.value}", forHTTPHeaderField = "Cookie")
			req.setTimeoutInterval(320.0)
			req.setHTTPBody(body.toNSData())
			NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, error ->
				val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
				val text = data?.toByteArray()?.decodeToString() ?: ""
				dispatch_async(dispatch_get_main_queue()) {
					if (error != null && status == 0) onDone(0, "{\"error\":\"Your private studio could not be reached (${error.localizedDescription}).\"}") else onDone(status, text)
				}
			}.resume()
		}
	}

	private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
	private fun NSData.toByteArray(): ByteArray { val n = length.toInt(); val out = ByteArray(n); if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }; return out }
}

