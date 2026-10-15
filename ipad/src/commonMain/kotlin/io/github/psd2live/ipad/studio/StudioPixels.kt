package io.github.psd2live.ipad.studio

import androidx.compose.ui.graphics.ImageBitmap
import io.github.psd2live.core.RigImage

/** Straight-alpha 0xAARRGGBB pixels, row by row. */
class Pixels(val width: Int, val height: Int, val argb: IntArray)

/** A PNG, JPEG or WebP decoded to straight alpha, or null when it isn't an image. */
expect fun decodeImage(bytes: ByteArray): Pixels?

/** [argb] (straight alpha, [width] x [height]) as PNG bytes. */
expect fun encodePng(width: Int, height: Int, argb: IntArray): ByteArray

/** A block of [argb] ([stride] wide) starting at ([x], [y]), [width] x [height], as a Compose image, nearest pixel scaled to [outWidth] x [outHeight]. */
expect fun argbRegionBitmap(argb: IntArray, stride: Int, rows: Int, x: Float, y: Float, width: Float, height: Float, outWidth: Int, outHeight: Int): ImageBitmap

/** A paint-engine image over [argb] itself (no copy). */
expect fun rigImageOver(width: Int, height: Int, argb: IntArray): RigImage

/** The pixels of a paint-engine image (no copy). */
expect val RigImage.pixels: IntArray
