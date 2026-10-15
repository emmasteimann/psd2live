package io.github.psd2live.ui
import io.github.psd2live.core.*

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.psd2live.core.RasterPaintEngine
import io.github.psd2live.core.RasterPaintShape

/** Compose color and shortcut adapters; all raster behavior lives in the shared engine. */
object LayerPaintEngine {
    class Tip(radius: Float, hardness: Float = 1f, antialias: Boolean = true, minRadius: Float = 0.5f) :
        RasterPaintEngine.Tip(radius, hardness, antialias, minRadius)

    class Stroke(width: Int, height: Int) : RasterPaintEngine.Stroke(width, height) {
        fun land(target: RigImage, color: Color, opacity: Float, erase: Boolean, region: Rectangle) =
            super.land(target, color.toArgb(), opacity, erase, region, {})
    }

    fun floodFill(image: RigImage, startX: Int, startY: Int, fillColor: Color, tolerance: Int,
                  clipRect: Rectangle? = null, before: (Rectangle) -> Unit = {}): Rectangle? =
        RasterPaintEngine.floodFill(image, startX, startY, fillColor.toArgb(), tolerance, clipRect, before)

    fun shapeArea(x0: Int, y0: Int, x1: Int, y1: Int, strokeWidth: Float, clipRect: Rectangle? = null): Rectangle =
        RasterPaintEngine.shapeArea(x0, y0, x1, y1, strokeWidth, clipRect)

    fun drawShape(image: RigImage, x0: Int, y0: Int, x1: Int, y1: Int, shape: PaintShape,
                  color: Color, opacity: Float, strokeWidth: Float, filled: Boolean, clipRect: Rectangle? = null): Rectangle? =
        RasterPaintEngine.drawShape(image, x0, y0, x1, y1, RasterPaintShape.valueOf(shape.name), color.toArgb(),
            opacity, strokeWidth, filled, clipRect)

    fun clear(image: RigImage) = RasterPaintEngine.clear(image)
}
