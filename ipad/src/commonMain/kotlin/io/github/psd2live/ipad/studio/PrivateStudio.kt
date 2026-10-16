package io.github.psd2live.ipad.studio

/** The private studio worker that makes the paid image edits: sign-in and requests (see the iOS side). */
expect object PrivateStudio {
	val url: String
	fun signedIn(onDone: (Boolean) -> Unit)
	/** Shows the worker's own sign-in page; calls back true once signed in, false if cancelled. */
	fun signIn(onDone: (Boolean) -> Unit)
	fun signOut(onDone: () -> Unit)
	/** POSTs [body] to the worker at [path]; calls back on the main thread with the status (0: unreachable) and text. */
	fun post(path: String, contentType: String, body: ByteArray, onDone: (status: Int, text: String) -> Unit)
}
