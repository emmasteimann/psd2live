package io.github.psd2live.application

internal actual fun String.codePointLength(): Int {
	var n = 0; var i = 0
	while (i < length) { i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1; n++ }
	return n
}

/**
 * BigDecimal(content).stripTrailingZeros() as a key: the sign, the significant digits without leading or trailing
 * zeros, and the power of ten of the last one. Zero is one key whatever its sign or scale.
 */
internal actual fun schemaNumberKey(content: String): Any? {
	val m = Regex("^([+-]?)(\\d*)(?:\\.(\\d*))?(?:[eE]([+-]?\\d+))?$").matchEntire(content) ?: return null
	val (sign, whole, fraction, exponent) = m.destructured
	if (whole.isEmpty() && fraction.isEmpty()) return null
	val digits = whole + fraction
	var power = (exponent.ifEmpty { "0" }.toLongOrNull() ?: return null) - fraction.length
	val trimmedStart = digits.trimStart('0')
	if (trimmedStart.isEmpty()) return "0"
	val significant = trimmedStart.trimEnd('0')
	power += trimmedStart.length - significant.length
	return "${if (sign == "-") "-" else ""}${significant}e$power"
}

internal actual fun logWarning(source: String, message: String, failure: Throwable) {
	println("WARNING $source: $message: ${failure.message ?: failure::class.simpleName}")
}
