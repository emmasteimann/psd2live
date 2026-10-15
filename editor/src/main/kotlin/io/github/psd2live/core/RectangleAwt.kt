package io.github.psd2live.core

// The desktop's edges between the shared Rectangle and AWT's.

fun Rectangle.toAwt(): java.awt.Rectangle = java.awt.Rectangle(x, y, width, height)

fun java.awt.Rectangle.toShared(): Rectangle = Rectangle(x, y, width, height)
