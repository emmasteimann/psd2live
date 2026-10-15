package io.github.psd2live.format.compile.document

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual class CacheLock {
	private val lock = ReentrantLock()
	actual fun <T> locked(block: () -> T): T = lock.withLock(block)
}
