package io.github.psd2live.core

import kotlin.math.min

/**
 * The pixels one stroke (or one fill, or one shape) replaced, kept as tiles rather than as a copy of
 * the whole layer: a stroke costs the area it actually touched, however large the layer is.
 *
 * [restore] puts the kept pixels back without forgetting them, which is what a live gesture does on
 * every pointer move; [swap] exchanges them with the layer, which undoes the edit - and redoes it when
 * applied a second time.
 */
class PaintPixelPatch {
    private val tiles = LinkedHashMap<Long, IntArray>()

    val isEmpty: Boolean get() = tiles.isEmpty()

    fun hasChanges(image: RigImage): Boolean = tiles.any { (key, pixels) ->
        val x = (key ushr 32).toInt() * TILE
        val y = (key and 0xFFFFFFFFL).toInt() * TILE
        val width = min(TILE, image.width - x); val height = min(TILE, image.height - y)
        !pixels.contentEquals(image.readArgb(x, y, width, height))
    }

    /** Remembers the pixels [rect] is about to lose. Tiles already kept are left as they are: they were
     *  captured before the gesture touched anything in them, which is exactly the state to go back to. */
    fun capture(image: RigImage, rect: Rectangle?) {
        val area = rect?.intersection(Rectangle(0, 0, image.width, image.height)) ?: return
        if (area.isEmpty) return
        for (tileY in area.y / TILE until (area.y + area.height + TILE - 1) / TILE) {
            for (tileX in area.x / TILE until (area.x + area.width + TILE - 1) / TILE) {
                val key = key(tileX, tileY)
                if (tiles.containsKey(key)) continue
                val x = tileX * TILE
                val y = tileY * TILE
                val width = min(TILE, image.width - x)
                val height = min(TILE, image.height - y)
                if (width <= 0 || height <= 0) continue
                tiles[key] = image.readArgb(x, y, width, height)
            }
        }
    }

    /**
     * Puts the kept pixels of [region] back - and only those, so a tile shared with strokes that are
     * still live keeps them.
     *
     * @return What was put back, one rectangle per tile, which is what the preview has to repaint.
     */
    fun restore(image: RigImage, region: Rectangle? = null): List<Rectangle> {
        if (tiles.isEmpty()) return emptyList()
        val area = region?.intersection(Rectangle(0, 0, image.width, image.height))
            ?: Rectangle(0, 0, image.width, image.height)
        if (area.isEmpty) return emptyList()
        val restored = ArrayList<Rectangle>()
        for ((key, pixels) in tiles) {
            val tileX = (key ushr 32).toInt()
            val tileY = (key and 0xFFFFFFFFL).toInt()
            val x = tileX * TILE
            val y = tileY * TILE
            val width = min(TILE, image.width - x)
            val height = min(TILE, image.height - y)
            val overlap = Rectangle(x, y, width, height).intersection(area)
            if (overlap.isEmpty) continue
            val part = IntArray(overlap.width * overlap.height)
            for (row in 0 until overlap.height) {
                val from = (overlap.y - y + row) * width + (overlap.x - x)
                pixels.copyInto(part, row * overlap.width, from, from + overlap.width)
            }
            image.writeArgb(overlap.x, overlap.y, overlap.width, overlap.height, part, 0, overlap.width)
            restored += overlap
        }
        return restored
    }

    /**
     * Exchanges the kept pixels with the layer's current ones, undoing the edit - or redoing it, when
     * the edit has already been undone. Tiles are swapped whole: an edit owns every pixel it captured,
     * so nothing outside it can be holding a newer value for them.
     *
     * @return What changed, one rectangle per tile.
     */
    fun swap(image: RigImage): List<Rectangle> {
        if (tiles.isEmpty()) return emptyList()
        val changed = ArrayList<Rectangle>(tiles.size)
        for ((key, pixels) in tiles) {
            val tileX = (key ushr 32).toInt()
            val tileY = (key and 0xFFFFFFFFL).toInt()
            val x = tileX * TILE
            val y = tileY * TILE
            val width = min(TILE, image.width - x)
            val height = min(TILE, image.height - y)
            if (width <= 0 || height <= 0) continue
            val current = image.readArgb(x, y, width, height)
            image.writeArgb(x, y, width, height, pixels, 0, width)
            tiles[key] = current
            changed += Rectangle(x, y, width, height)
        }
        return changed
    }

    private fun key(tileX: Int, tileY: Int): Long = (tileX.toLong() shl 32) or (tileY.toLong() and 0xFFFFFFFFL)

    private companion object {
        /** Patch tile edge, in pixels. Small enough to follow a stroke's outline, large enough that a
         *  stroke is a few dozen reads rather than a few thousand. */
        const val TILE = 64
    }
}

/**
 * One user stroke or raster action inside the isolated painting session.
 */
data class RasterPaintStrokeRecord(
    val id: String,
    val name: String,
    val patch: PaintPixelPatch,
)

/**
 * Isolated painting pipeline session in the painted layer's raster space ([PaintSpace]).
 *
 * Keeps all drawing strokes, eraser, flood fill, and shapes on an in-memory raster of the layer's own pixel grid
 * without polluting the main project history or restricting the user to a pre-existing mesh boundary.
 *
 * Every write to [workingImage] goes through [edit] (or is announced with [willWrite] first): the
 * session remembers the pixels an edit replaces, which is what lets a gesture be given back, a stroke
 * be undone, and neither cost a copy of the whole layer.
 */
class PaintRasterSession(
    val layerId: String,
    val layerName: String,
    /**
     * The raster every stroke lands on: the layer's pixel grid over the canvas, independent of the atlas.
     *
     * Writes have to go through [edit], or be announced with [willWrite] first: the session can only
     * undo, redo or give back an edit whose pixels it watched being replaced.
     */
    val workingImage: RigImage,
    /** The raster the session started from, restored by [discard]. */
    val originalImageCopy: RigImage,
) {
    val docWidth: Int get() = workingImage.width
    val docHeight: Int get() = workingImage.height

    var isDirty = false
    val strokeRecords = mutableListOf<RasterPaintStrokeRecord>()
    var currentStrokeIndex = -1

    /** Strokes drawn so far; record 0 is the baseline snapshot, not a stroke. */
    val strokeCount: Int get() = strokeRecords.size - 1

    var onChanged: (List<Rectangle>) -> Unit = {}

    /**
     * Every area an edit of this session has written to, ever: with the layer's own area, all a commit has to
     * read for the layer's new pixels. Undoing never shrinks it.
     */
    var touched: Rectangle? = null
        private set
    private fun touch(rect: Rectangle?) {
        val area = rect?.intersection(Rectangle(0, 0, docWidth, docHeight))?.takeUnless { it.isEmpty } ?: return
        touched = touched?.union(area) ?: Rectangle(area)
    }
    private var liveStroke: RasterPaintEngine.Stroke? = null
    private var pending = PaintPixelPatch()
    init {
        strokeRecords += RasterPaintStrokeRecord("init", "initial", PaintPixelPatch())
        currentStrokeIndex = 0
    }
    fun refreshPreview() { onChanged(emptyList()) }
    private fun markDirty(rect: Rectangle?) { onChanged(listOf(rect ?: Rectangle(0, 0, docWidth, docHeight))) }
    private fun markDirty(rects: List<Rectangle>) { onChanged(rects) }

    /** The stroke in progress's coverage. A session paints many strokes through one buffer. */
    fun stroke(): RasterPaintEngine.Stroke =
        liveStroke ?: RasterPaintEngine.Stroke(docWidth, docHeight).also { liveStroke = it }

    /** Starts a stroke: the previous one's coverage is forgotten and the buffer is ready to be stamped. */
    fun beginStroke() {
        stroke().reset()
    }

    /**
     * Lands the pixels a stroke segment has just claimed: they are put back to what the stroke started
     * from, and the stroke's whole coverage is laid over them at [opacity]. Doing it in this order is
     * what makes a stroke that crosses itself land once, and land the same however the drag was cut up.
     */
    fun landSegment(claimed: Rectangle, color: Int, opacity: Float, erase: Boolean) {
        touch(claimed)
        pending.capture(workingImage, claimed)
        pending.restore(workingImage, claimed)
        stroke().land(workingImage, color, opacity, erase, claimed)
        markDirty(claimed)
    }

    /** Remembers the pixels [rect] is about to lose. See [edit] for the usual way in. */
    fun willWrite(rect: Rectangle?) {
        touch(rect)
        pending.capture(workingImage, rect)
        markDirty(rect)
    }

    /**
     * Runs one raster edit over [rect], remembering the pixels it replaces so it can be taken back,
     * undone and redone as one action.
     */
    fun edit(rect: Rectangle?, block: (RigImage) -> Unit) {
        willWrite(rect)
        block(workingImage)
    }

    /** Abandons the gesture in progress: its pixels go back, and nothing is recorded. */
    fun abandonStroke() {
        markDirty(pending.restore(workingImage))
        pending = PaintPixelPatch()
        liveStroke?.reset()
    }

    /**
     * Seals the gesture in progress into the history as one action. A gesture that changed nothing - a
     * click that landed outside the layer - adds no entry, so undo never steps over a no-op.
     */
    fun recordStroke(name: String) {
        if (pending.isEmpty) return
        if (!pending.hasChanges(workingImage)) { pending = PaintPixelPatch(); return }
        while (strokeRecords.size > currentStrokeIndex + 1) {
            strokeRecords.removeAt(strokeRecords.lastIndex)
        }
        strokeRecords.add(
            RasterPaintStrokeRecord(
                id = "stroke_${strokeRecords.size}",
                name = name,
                patch = pending,
            )
        )
        pending = PaintPixelPatch()
        currentStrokeIndex = strokeRecords.lastIndex
        isDirty = currentStrokeIndex > 0
        refreshPreview()
    }

    fun canUndo(): Boolean = currentStrokeIndex > 0
    fun canRedo(): Boolean = currentStrokeIndex < strokeRecords.lastIndex

    fun undo(): RigImage? {
        if (!canUndo()) return null
        markDirty(strokeRecords[currentStrokeIndex].patch.swap(workingImage))
        currentStrokeIndex--
        isDirty = currentStrokeIndex > 0
        refreshPreview()
        return workingImage
    }

    fun redo(): RigImage? {
        if (!canRedo()) return null
        currentStrokeIndex++
        markDirty(strokeRecords[currentStrokeIndex].patch.swap(workingImage))
        isDirty = true
        refreshPreview()
        return workingImage
    }

    fun jumpToStroke(index: Int): RigImage? {
        if (index !in strokeRecords.indices) return null
        while (currentStrokeIndex > index) {
            markDirty(strokeRecords[currentStrokeIndex].patch.swap(workingImage))
            currentStrokeIndex--
        }
        while (currentStrokeIndex < index) {
            currentStrokeIndex++
            markDirty(strokeRecords[currentStrokeIndex].patch.swap(workingImage))
        }
        isDirty = currentStrokeIndex > 0
        refreshPreview()
        return workingImage
    }

    fun discard(): RigImage {
        pending = PaintPixelPatch()
        liveStroke?.reset()
        restoreImage(originalImageCopy)
        currentStrokeIndex = 0
        while (strokeRecords.size > 1) {
            strokeRecords.removeAt(strokeRecords.lastIndex)
        }
        isDirty = false
        markDirty(null)
        refreshPreview()
        return workingImage
    }

    private fun restoreImage(source: RigImage) {
        workingImage.copyPixelsFrom(source)
    }

}
