package io.github.psd2live.application

internal actual fun String.codePointLength(): Int = codePointCount(0, length)

internal actual fun schemaNumberKey(content: String): Any? = content.toBigDecimalOrNull()?.stripTrailingZeros()

internal actual fun logWarning(source: String, message: String, failure: Throwable) {
	System.getLogger(source).log(System.Logger.Level.WARNING, message, failure)
}
