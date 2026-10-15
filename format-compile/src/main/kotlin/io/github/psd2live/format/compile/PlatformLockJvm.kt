package io.github.psd2live.format.compile

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

public actual class PlatformLock {
	private val lock = ReentrantLock()
	public actual fun <T> locked(block: () -> T): T = lock.withLock(block)
}
