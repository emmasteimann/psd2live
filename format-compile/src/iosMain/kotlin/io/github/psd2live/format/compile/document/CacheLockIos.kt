package io.github.psd2live.format.compile.document

import platform.Foundation.NSRecursiveLock

internal actual class CacheLock {
	private val lock = NSRecursiveLock()
	actual fun <T> locked(block: () -> T): T {
		lock.lock()
		try { return block() } finally { lock.unlock() }
	}
}
