package io.github.psd2live.ui

import androidx.compose.ui.input.pointer.PointerIcon

// What the shared editor UI needs from the platform (Layerloom iPad plan, phase 5): AWT's cursors and clipboard on the
// desktop, Compose's pointers and UIPasteboard on the iPad (EditorPlatformIos.kt).

/** The editor's pointer shapes. */
expect object EditorPointers {
	val default: PointerIcon
	val hand: PointerIcon
	val move: PointerIcon
	val crosshair: PointerIcon
	val text: PointerIcon
	val resizeHorizontal: PointerIcon
	val resizeVertical: PointerIcon
}

/** The system clipboard's text. */
expect object EditorClipboard {
	fun setText(text: String)
	fun text(): String?
}
