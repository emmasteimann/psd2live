package io.github.psd2live.ui

import androidx.compose.ui.input.pointer.PointerIcon
import platform.UIKit.UIPasteboard

/** Pointers show on the iPad with a trackpad; Compose's standard shapes stand in for the desktop's resize cursors. */
actual object EditorPointers {
	actual val default: PointerIcon = PointerIcon.Default
	actual val hand: PointerIcon = PointerIcon.Hand
	actual val move: PointerIcon = PointerIcon.Hand
	actual val crosshair: PointerIcon = PointerIcon.Crosshair
	actual val text: PointerIcon = PointerIcon.Text
	actual val resizeHorizontal: PointerIcon = PointerIcon.Crosshair
	actual val resizeVertical: PointerIcon = PointerIcon.Crosshair
}

actual object EditorClipboard {
	actual fun setText(text: String) { UIPasteboard.generalPasteboard.string = text }
	actual fun text(): String? = UIPasteboard.generalPasteboard.string
}
