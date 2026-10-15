package io.github.psd2live.ui

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

actual object EditorPointers {
	private fun of(type: Int) = PointerIcon(Cursor.getPredefinedCursor(type))
	actual val default: PointerIcon = of(Cursor.DEFAULT_CURSOR)
	actual val hand: PointerIcon = of(Cursor.HAND_CURSOR)
	actual val move: PointerIcon = of(Cursor.MOVE_CURSOR)
	actual val crosshair: PointerIcon = of(Cursor.CROSSHAIR_CURSOR)
	actual val text: PointerIcon = of(Cursor.TEXT_CURSOR)
	actual val resizeHorizontal: PointerIcon = of(Cursor.E_RESIZE_CURSOR)
	actual val resizeVertical: PointerIcon = of(Cursor.N_RESIZE_CURSOR)
}

actual object EditorClipboard {
	actual fun setText(text: String) {
		val selection = StringSelection(text)
		Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
	}
	actual fun text(): String? = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
}
