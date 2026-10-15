package io.github.psd2live.format.compile

internal actual fun checkInterrupted() {
	if (Thread.currentThread().isInterrupted) throw InterruptedException()
}
