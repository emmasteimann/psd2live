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
 * The private studio (the Cloudflare worker that makes the paid image edits). Signing in happens on the worker's own
 * page in a web view - the app never sees the password; the session cookie it sets lives in the app's web storage
 * (12 hours). Requests are sent natively with that cookie and the studio's own Origin, as the worker requires.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object PrivateStudio {
	actual val url = "https://layerloom-repair.emmagracesteimann.workers.dev"
	private val store get() = WKWebsiteDataStore.defaultDataStore().httpCookieStore
	private var sheet: UIViewController? = null
	private var keeper: NSObject? = null

	private fun session(onDone: (NSHTTPCookie?) -> Unit) {
		store.getAllCookies { all ->
			val c = all?.mapNotNull { it as? NSHTTPCookie }?.firstOrNull { it.name == COOKIE && (it.expiresDate?.timeIntervalSinceNow ?: 1.0) > 60.0 }
			onDone(c)
		}
	}

	actual fun signedIn(onDone: (Boolean) -> Unit) { session { onDone(it != null) } }

	actual fun signIn(onDone: (Boolean) -> Unit) {
		var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return onDone(false)
		while (top.presentedViewController != null) top = top.presentedViewController!!
		val configuration = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.defaultDataStore() }
		val web = WKWebView(frame = kotlinx.cinterop.cValue { }, configuration = configuration)
		val controller = UIViewController()
		controller.view = web
		controller.title = "Sign in to your private studio"
		var finished = false
		fun finish(ok: Boolean) {
			if (finished) return; finished = true
			sheet?.dismissViewControllerAnimated(true, completion = null); sheet = null; keeper = null
			onDone(ok)
		}
		val nav = object : NSObject(), WKNavigationDelegateProtocol {
			// After the password is accepted the worker sends the browser back to its home page: the cookie is there then.
			override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) { session { if (it != null) dispatch_async(dispatch_get_main_queue()) { finish(true) } } }
		}
		val cancel = CancelTarget { finish(false) }
		keeper = object : NSObject() { val n = nav; val c = cancel }
		web.navigationDelegate = nav
		controller.navigationItem.leftBarButtonItem = UIBarButtonItem(title = "Cancel", style = UIBarButtonItemStyle.UIBarButtonItemStylePlain, target = cancel,
			action = platform.objc.sel_registerName("cancel"))
		val wrap = UINavigationController(rootViewController = controller)
		sheet = wrap
		top.presentViewController(wrap, animated = true, completion = null)
		web.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString("$url/")!!))
	}

	actual fun signOut(onDone: () -> Unit) {
		store.getAllCookies { all ->
			val c = all?.mapNotNull { it as? NSHTTPCookie }?.firstOrNull { it.name == COOKIE }
			if (c == null) dispatch_async(dispatch_get_main_queue()) { onDone() } else store.deleteCookie(c) { dispatch_async(dispatch_get_main_queue()) { onDone() } }
		}
	}

	actual fun post(path: String, contentType: String, body: ByteArray, onDone: (status: Int, text: String) -> Unit) {
		session { cookie ->
			if (cookie == null) { dispatch_async(dispatch_get_main_queue()) { onDone(401, "{\"error\":\"Sign in to your private studio first.\"}") }; return@session }
			val req = NSMutableURLRequest.requestWithURL(NSURL.URLWithString(url + path)!!)
			req.setHTTPMethod("POST")
			req.setValue(contentType, forHTTPHeaderField = "Content-Type")
			req.setValue(url, forHTTPHeaderField = "Origin")
			req.setValue("same-origin", forHTTPHeaderField = "Sec-Fetch-Site")
			req.setValue("$COOKIE=${cookie.value}", forHTTPHeaderField = "Cookie")
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

/** The Cancel button's target (UIKit calls it by selector). */
@OptIn(BetaInteropApi::class)
private class CancelTarget(val onCancel: () -> Unit) : NSObject() {
	@kotlinx.cinterop.ObjCAction fun cancel() { onCancel() }
}
