package io.github.psd2live.i18n

// Translations for shared code (Layerloom iPad plan, phase 2). The rigger names generated parts and reports problems
// through tr, so the iPad needs the real messages: the desktop reads them with ResourceBundle and formats them with
// MessageFormat (I18n.kt); the iPad embeds the same Messages*.properties at build time and reads them with
// [parseProperties] and [formatMessage], which follow Java's rules for what the bundles use.

/** The message [key] in the current language, with [arguments] filled in; "!key!" when there is none. */
expect fun tr(key: String, vararg arguments: Any?): String

/** The current language's tag ("en", "ja", "ko", "zh"): part of cache keys, as generated names are translated. */
expect fun currentLanguageTag(): String

/** Whether prose names modifier keys as ⌘ ⌥ ⇧ ([platformKeyWords]): on macOS and on the iPad. */
internal expect val usesMacKeyWords: Boolean

/** A .properties file's entries, as java.util.Properties reads them (escapes, continuations, comments). */
fun parseProperties(text: String): Map<String, String> {
	val result = LinkedHashMap<String, String>()
	val lines = text.removePrefix("﻿").split("\r\n", "\n", "\r")
	var i = 0
	while (i < lines.size) {
		var line = lines[i++].trimStart(' ', '\t', '\u000c')
		if (line.isEmpty() || line[0] == '#' || line[0] == '!') continue
		// A line ending in an odd number of backslashes continues on the next (whose leading blanks are dropped).
		while (line.takeLastWhile { it == '\\' }.length % 2 == 1 && i <= lines.size) {
			line = line.dropLast(1) + (if (i < lines.size) lines[i].trimStart(' ', '\t', '\u000c') else "")
			i++
		}
		var k = 0
		val key = StringBuilder()
		while (k < line.length) {
			val c = line[k]
			if (c == '\\' && k + 1 < line.length) { key.append(line, k, k + 2); k += 2; continue }
			if (c == '=' || c == ':' || c == ' ' || c == '\t' || c == '\u000c') break
			key.append(c); k++
		}
		while (k < line.length && (line[k] == ' ' || line[k] == '\t' || line[k] == '\u000c')) k++
		if (k < line.length && (line[k] == '=' || line[k] == ':')) k++
		while (k < line.length && (line[k] == ' ' || line[k] == '\t' || line[k] == '\u000c')) k++
		result[unescape(key.toString())] = unescape(line.substring(k))
	}
	return result
}

private fun unescape(text: String): String {
	if ('\\' !in text) return text
	val out = StringBuilder(text.length)
	var i = 0
	while (i < text.length) {
		val c = text[i++]
		if (c != '\\' || i == text.length) { out.append(c); continue }
		when (val e = text[i++]) {
			't' -> out.append('\t'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 'f' -> out.append('\u000c')
			'u' -> { out.append(text.substring(i, i + 4).toInt(16).toChar()); i += 4 }
			else -> out.append(e)
		}
	}
	return out.toString()
}

/**
 * java.text.MessageFormat for the forms the bundles use: `{n}` and `{n,number,#}`, with its quoting (`'` starts and
 * ends literal text, `''` is one apostrophe). Numbers in `{n}` take the default number format of the bundles'
 * languages (grouping by thousands with ',', up to three decimals, half-even); `#` rounds to a whole number.
 */
fun formatMessage(pattern: String, arguments: Array<out Any?>): String {
	val out = StringBuilder()
	var i = 0
	var quoted = false
	while (i < pattern.length) {
		val c = pattern[i]
		when {
			c == '\'' -> {
				if (i + 1 < pattern.length && pattern[i + 1] == '\'') { out.append('\''); i += 2; continue }
				quoted = !quoted; i++
			}
			c == '{' && !quoted -> {
				val end = pattern.indexOf('}', i)
				require(end > i) { "Unmatched braces in the pattern." }
				val parts = pattern.substring(i + 1, end).split(',').map { it.trim() }
				val index = parts[0].toInt()
				val argument = if (index < arguments.size) arguments[index] else null
				out.append(when {
					index >= arguments.size -> "{$index}"
					argument == null -> "null"
					parts.size >= 2 && parts[1] == "number" && argument !is Number -> throw IllegalArgumentException("Cannot format given Object as a Number")
					parts.size >= 3 && parts[1] == "number" && parts[2] == "#" -> wholeNumber(argument as Number)
					argument is Number -> defaultNumber(argument)
					else -> argument.toString()
				})
				i = end + 1
			}
			else -> { out.append(c); i++ }
		}
	}
	return out.toString()
}

private fun isIntegral(n: Number) = n is Int || n is Long || n is Short || n is Byte

private fun grouped(digits: String): String {
	val negative = digits.startsWith('-')
	val body = digits.removePrefix("-")
	val out = StringBuilder()
	for ((k, ch) in body.withIndex()) { if (k > 0 && (body.length - k) % 3 == 0) out.append(','); out.append(ch) }
	return (if (negative) "-" else "") + out
}

private fun wholeNumber(n: Number): String =
	if (isIntegral(n)) n.toLong().toString() else formatFixed(n.toDouble(), 0, grouping = false)

private fun defaultNumber(n: Number): String =
	if (isIntegral(n)) grouped(n.toLong().toString()) else formatFixed(n.toDouble(), 3, grouping = true)

/** [value] rounded half-even to at most [decimals] places (trailing zeros dropped), as DecimalFormat("#,##0.###"). */
private fun formatFixed(value: Double, decimals: Int, grouping: Boolean): String {
	if (value.isNaN()) return "NaN"
	if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
	// The double's exact decimal expansion, so half-even sees the true digits (as DecimalFormat does).
	val exact = exactDecimal(value)
	val negative = exact.startsWith('-')
	val body = exact.removePrefix("-")
	val point = body.indexOf('.').let { if (it < 0) body.length else it }
	val intDigits = body.substring(0, point)
	val fraction = if (point < body.length) body.substring(point + 1) else ""
	var kept = (intDigits + fraction.padEnd(decimals, '0').take(decimals)).toCharArray()
	val rest = fraction.drop(decimals)
	// The JDK's quirk: a value whose shortest digits are a lone 5 just past the last place (0.0005 for three places)
	// counts as an exact tie there (FloatingDecimal reports it exact), so it rounds to even, down.
	val loneFive = shortestDecimal(kotlin.math.abs(value)).trimEnd('0') == "0." + "0".repeat(decimals) + "5"
	val roundUp = when {
		loneFive -> false
		rest.isEmpty() || rest.all { it == '0' } -> false
		rest[0] > '5' -> true
		rest[0] < '5' -> false
		rest.drop(1).any { it != '0' } -> true
		else -> (kept.lastOrNull()?.digitToInt() ?: 0) % 2 == 1
	}
	if (roundUp) {
		var k = kept.size - 1
		while (k >= 0 && kept[k] == '9') { kept[k] = '0'; k-- }
		kept = if (k < 0) charArrayOf('1') + kept else kept.also { it[k] = it[k] + 1 }
	}
	val all = kept.concatToString()
	val whole = all.dropLast(decimals).trimStart('0').ifEmpty { "0" }
	val frac = all.takeLast(decimals).trimEnd('0')
	val text = (if (grouping) grouped(whole) else whole) + (if (frac.isEmpty()) "" else ".$frac")
	// DecimalFormat keeps the sign of a negative number that rounds to zero ("-0").
	return if (negative) "-$text" else text
}

/** Double.toString's digits for [value] as a plain decimal (no exponent). */
private fun shortestDecimal(value: Double): String {
	val s = value.toString()
	val negative = s.startsWith('-')
	val body = s.removePrefix("-")
	val e = body.indexOfFirst { it == 'E' || it == 'e' }
	val mantissa = if (e < 0) body else body.substring(0, e)
	val exponent = if (e < 0) 0 else body.substring(e + 1).toInt()
	val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
	val digits = mantissa.replace(".", "")
	val decimalAt = point + exponent
	val plain = when {
		decimalAt <= 0 -> "0." + "0".repeat(-decimalAt) + digits
		decimalAt >= digits.length -> digits + "0".repeat(decimalAt - digits.length)
		else -> digits.substring(0, decimalAt) + "." + digits.substring(decimalAt)
	}
	return (if (negative) "-" else "") + plain
}

/** The exact decimal value of [value] (every double is a finite binary fraction), without exponent. */
private fun exactDecimal(value: Double): String {
	if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
	val bits = value.toRawBits()
	val negative = bits < 0
	val exponent = ((bits ushr 52) and 0x7ff).toInt()
	var mantissa = bits and 0xfffffffffffffL
	val e2 = if (exponent == 0) -1074 else { mantissa = mantissa or (1L shl 52); exponent - 1075 }
	// value = mantissa × 2^e2, in base-10 digits held as an int array (little-endian, base 1e9).
	var digits = intArrayOf((mantissa % 1_000_000_000).toInt(), (mantissa / 1_000_000_000 % 1_000_000_000).toInt(), (mantissa / 1_000_000_000_000_000_000L).toInt())
	var scale = 0 // decimal places
	if (e2 > 0) repeat(e2) { digits = mul(digits, 2) }
	else repeat(-e2) { digits = mul(digits, 5); scale++ } // m × 2^-k = m × 5^k / 10^k
	val s = digits.reversed().joinToString("") { it.toString().padStart(9, '0') }.trimStart('0').ifEmpty { "0" }
	val padded = s.padStart(scale + 1, '0')
	val text = if (scale == 0) padded else padded.dropLast(scale) + "." + padded.takeLast(scale).trimEnd('0').ifEmpty { "0" }
	return (if (negative) "-" else "") + text
}

private fun mul(digits: IntArray, factor: Int): IntArray {
	var carry = 0L
	val out = IntArray(digits.size + 1)
	for (k in digits.indices) { val v = digits[k].toLong() * factor + carry; out[k] = (v % 1_000_000_000).toInt(); carry = v / 1_000_000_000 }
	out[digits.size] = carry.toInt()
	return if (out.last() == 0) out.copyOf(digits.size) else out
}
