package io.github.psd2live.format.compile

/** A lock for shared code: java.util.concurrent's on the JVM, Foundation's on the iPad. Re-entrant. */
public expect class PlatformLock() {
	public fun <T> locked(block: () -> T): T
}
