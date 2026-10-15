package io.github.psd2live.format.compile

import platform.Foundation.NSRecursiveLock

public actual class PlatformLock {
	private val lock = NSRecursiveLock()
	public actual fun <T> locked(block: () -> T): T {
		lock.lock()
		try { return block() } finally { lock.unlock() }
	}
}
