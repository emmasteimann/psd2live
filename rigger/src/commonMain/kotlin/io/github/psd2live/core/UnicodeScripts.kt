package io.github.psd2live.core

/**
 * Java's Unicode scripts Han, Hiragana, Katakana and Hangul (\\p{IsHan} ...) for regexes in shared code: Kotlin/Native's
 * regex has no script classes. These are each script's BMP ranges, exactly as the JDK's tables give them (Unicode 15,
 * generated from Character.UnicodeScript). BMP only is faithful: Java's one-character lookbehinds - the classifier's
 * only use - never see a supplementary-plane character either (they look back one UTF-16 unit, half a pair).
 * Pinned by a desktop test against the \\p{Is...} patterns.
 */
object UnicodeScripts {
	const val HAN = "\\u2E80-\\u2E99\\u2E9B-\\u2EF3\\u2F00-\\u2FD5\\u3005\\u3007\\u3021-\\u3029\\u3038-\\u303B\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFA6D\\uFA70-\\uFAD9"
	const val HIRAGANA = "\\u3041-\\u3096\\u309D-\\u309F"
	const val KATAKANA = "\\u30A1-\\u30FA\\u30FD-\\u30FF\\u31F0-\\u31FF\\u32D0-\\u32FE\\u3300-\\u3357\\uFF66-\\uFF6F\\uFF71-\\uFF9D"
	const val HANGUL = "\\u1100-\\u11FF\\u302E-\\u302F\\u3131-\\u318E\\u3200-\\u321E\\u3260-\\u327E\\uA960-\\uA97C\\uAC00-\\uD7A3\\uD7B0-\\uD7C6\\uD7CB-\\uD7FB\\uFFA0-\\uFFBE\\uFFC2-\\uFFC7\\uFFCA-\\uFFCF\\uFFD2-\\uFFD7\\uFFDA-\\uFFDC"
}
