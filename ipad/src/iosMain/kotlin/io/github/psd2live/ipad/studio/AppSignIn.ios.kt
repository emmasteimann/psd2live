package io.github.psd2live.ipad.studio

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.Foundation.NSURLSession
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The app's own sign-in to the private studio: OAuth's authorization code + PKCE against the worker's password page,
 * in Apple's sign-in sheet (ASWebAuthenticationSession, where Safari's saved password fills in). The app never sees the
 * password; it gets a code, trades it with a secret only it holds for a key that lasts 90 days, and keeps the key in
 * the Keychain. Signing out deletes it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object AppSignIn {
	private const val SERVICE = "com.goosible.layerloom.studio"
	private const val ACCOUNT = "app-key"
	private const val SCHEME = "layerloom-auth"
	private var session: ASWebAuthenticationSession? = null
	private var anchor: NSObject? = null

	/** The stored app key while it has more than a day left; null otherwise. */
	fun key(): String? {
		val k = read() ?: return null
		val expires = k.split('.').getOrNull(1)?.toLongOrNull() ?: return null
		return if (expires - NSDate().timeIntervalSince1970.toLong() > 86_400) k else null
	}

	fun forget() = delete()

	fun signIn(base: String, onDone: (Boolean, String?) -> Unit) {
		val verifier = base64url(random(32))
		val challenge = base64url(sha256(verifier.encodeToByteArray()))
		val state = base64url(random(16))
		val start = NSURLComponents(string = "$base/auth/app")!!.apply {
			queryItems = listOf(NSURLQueryItem(name = "challenge", value = challenge), NSURLQueryItem(name = "state", value = state))
		}.URL!!
		val provider = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
			override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
				UIApplication.sharedApplication.keyWindow!!
		}
		fun done(ok: Boolean, why: String?) { session = null; anchor = null; dispatch_async(dispatch_get_main_queue()) { onDone(ok, why) } }
		val s = ASWebAuthenticationSession(uRL = start, callbackURLScheme = SCHEME) { url, error ->
			if (url == null) { done(false, if (error != null) null else "Not signed in."); return@ASWebAuthenticationSession }
			val items = NSURLComponents(uRL = url, resolvingAgainstBaseURL = false)?.queryItems.orEmpty().mapNotNull { it as? NSURLQueryItem }
			val code = items.firstOrNull { it.name == "code" }?.value
			if (code == null || items.firstOrNull { it.name == "state" }?.value != state) { done(false, "That sign-in didn't come back right. Please try again."); return@ASWebAuthenticationSession }
			exchange(base, code, verifier) { k, why -> if (k != null && write(k)) done(true, null) else done(false, why ?: "Couldn't keep the sign-in on this iPad.") }
		}
		s.presentationContextProvider = provider
		s.prefersEphemeralWebBrowserSession = false
		session = s; anchor = provider
		if (!s.start()) done(false, "The sign-in sheet couldn't open.")
	}

	private fun exchange(base: String, code: String, verifier: String, onDone: (String?, String?) -> Unit) {
		val req = NSMutableURLRequest.requestWithURL(NSURL.URLWithString("$base/auth/app/token")!!)
		req.setHTTPMethod("POST")
		req.setValue("application/json", forHTTPHeaderField = "Content-Type")
		val json = """{"code":"$code","verifier":"$verifier"}"""
		req.setHTTPBody((json as NSString).dataUsingEncoding(NSUTF8StringEncoding))
		NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, _ ->
			val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
			val text = data?.let { d -> d.bytes?.readBytes(d.length.toInt())?.decodeToString() }.orEmpty()
			val k = Regex("\"key\"\\s*:\\s*\"(a\\.\\d+\\.[a-f0-9]{64})\"").find(text)?.groupValues?.get(1)
			val why = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
			onDone(if (status == 200) k else null, why ?: if (status == 0) "Your private studio could not be reached." else null)
		}.resume()
	}

	private fun random(n: Int): ByteArray = ByteArray(n).also { b -> b.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) } }

	private fun sha256(bytes: ByteArray): ByteArray = memScoped {
		val out = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
		bytes.usePinned { CC_SHA256(it.addressOf(0), bytes.size.convert(), out) }
		out.readBytes(CC_SHA256_DIGEST_LENGTH)
	}

	private fun base64url(bytes: ByteArray): String {
		val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
		return data.base64EncodedStringWithOptions(0u).replace('+', '-').replace('/', '_').trimEnd('=')
	}

	// The Keychain: one generic password, readable after the first unlock (so the studio works while the iPad is in use).
	private fun query(): platform.CoreFoundation.CFMutableDictionaryRef? {
		val q = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
		CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
		CFDictionaryAddValue(q, kSecAttrService, CFBridgingRetain(SERVICE))
		CFDictionaryAddValue(q, kSecAttrAccount, CFBridgingRetain(ACCOUNT))
		return q
	}

	private fun read(): String? = memScoped {
		val q = query()
		CFDictionaryAddValue(q, kSecReturnData, kCFBooleanTrue)
		CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitOne)
		val out = alloc<CFTypeRefVar>()
		if (SecItemCopyMatching(q, out.ptr) != 0) return null
		val data = CFBridgingRelease(out.value) as? NSData ?: return null
		data.bytes?.readBytes(data.length.toInt())?.decodeToString()
	}

	private fun write(key: String): Boolean {
		delete()
		val q = query()
		val data = (key as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
		CFDictionaryAddValue(q, kSecValueData, CFBridgingRetain(data))
		CFDictionaryAddValue(q, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
		val status = SecItemAdd(q, null)
		if (status != 0) println("Layerloom sign-in: the Keychain refused the key (OSStatus $status)")
		return status == 0
	}

	private fun delete() { SecItemDelete(query()) }
}
