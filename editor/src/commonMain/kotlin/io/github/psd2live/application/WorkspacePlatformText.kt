package io.github.psd2live.application

/** The string's length in Unicode code points, as java.lang.String.codePointCount counts them (a lone surrogate is one). */
internal expect fun String.codePointLength(): Int

/** A JSON number's value as a comparison key: equal for equal decimal values (1, 1.0, 1e0), as BigDecimal.stripTrailingZeros compares them; null if it isn't a number. */
internal expect fun schemaNumberKey(content: String): Any?

/** A warning for the host's log (System.Logger on the desktop). */
internal expect fun logWarning(source: String, message: String, failure: Throwable)
