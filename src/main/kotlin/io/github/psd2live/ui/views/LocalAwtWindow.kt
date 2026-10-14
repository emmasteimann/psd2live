package io.github.psd2live.ui.views

import androidx.compose.runtime.staticCompositionLocalOf

/** The AWT window a composable draws in: the preview asks whether it can sample textures shared with it. */
val LocalAwtWindow = staticCompositionLocalOf<java.awt.Window?> { null }

/**
 * True when macOS draws the window's frame: the red-yellow-green buttons sit over the title bar's left end and
 * the menus live in the screen's menu bar, so the title bar leaves both out.
 */
val LocalNativeWindowChrome = staticCompositionLocalOf { false }
