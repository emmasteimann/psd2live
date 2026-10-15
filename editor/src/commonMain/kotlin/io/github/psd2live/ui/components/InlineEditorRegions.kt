package io.github.psd2live.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Set by a text field during the initial pointer pass when a press hits that field.
 * The dialog reads it on the final pass and clears focus only when no field claimed the press.
 */
class InlineEditorRegions {
    var pressedInside: Boolean = false
}

val LocalInlineEditorRegions = staticCompositionLocalOf<InlineEditorRegions?> { null }
