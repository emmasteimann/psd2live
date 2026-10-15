package io.github.psd2live.i18n

// Layerloom patch: here rather than with the key bindings (ui.state), so the translations - which the rigger uses -
// do not reach into the editor's UI code.

private val MAC_KEY_WORDS = listOf(Regex("\\bCtrl\\+") to "⌘", Regex("\\bAlt\\+") to "⌥", Regex("\\bShift\\+") to "⇧",
    Regex("\\bCtrl\\b") to "⌘", Regex("\\bAlt\\b") to "⌥", Regex("\\bShift\\b") to "⇧")
private val KEY_MARKUP = Regex("\\{(key|kbd):[^}]*\\}")

/**
 * Layerloom patch: prose that names modifier keys ("Ctrl+Click walks the hierarchy", "Alt subtract"), as this
 * platform writes them: on macOS (and the iPad) with ⌘ ⌥ ⇧. `{key:…}` / `{kbd:…}` markup is left for its own resolver.
 */
fun platformKeyWords(text: String): String {
    if (!usesMacKeyWords || !(text.contains("Ctrl") || text.contains("Alt") || text.contains("Shift"))) return text
    val out = StringBuilder()
    var last = 0
    fun words(part: String) = MAC_KEY_WORDS.fold(part) { acc, (pattern, symbol) -> pattern.replace(acc, symbol) }
    for (m in KEY_MARKUP.findAll(text)) { out.append(words(text.substring(last, m.range.first))).append(m.value); last = m.range.last + 1 }
    return out.append(words(text.substring(last))).toString()
}
