package io.github.psd2live.ipad.studio

import io.github.psd2live.core.PaintRasterSession
import io.github.psd2live.core.RasterPaintEngine
import io.github.psd2live.core.Rectangle
import io.github.psd2live.core.RigImage
import io.github.psd2live.core.newRigImage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** The mask colour the web studio paints (`#bca1ff`), opaque. */
const val MASK_ARGB = 0xFFBCA1FF.toInt()

/**
 * The selected part's mask being painted: a full-canvas image with the desktop paint engine's stroke and tile-patch
 * undo, the same engine the Rig tab paints with. Strokes are in canvas pixels. Use it from one thread.
 */
class MaskEdit(val partId: String, val width: Int, val height: Int, maskUrl: String?) {
	val image: RigImage = newRigImage(width, height)
	val pixels: IntArray get() = image.pixels
	private val session: PaintRasterSession
	private var stroking = false

	init {
		if (maskUrl != null) {
			val layer = StudioImages.layer(maskUrl, width, height)
			val px = image.pixels
			for (row in 0 until layer.height) layer.argb.copyInto(px, (layer.y + row) * width + layer.x, row * layer.width, row * layer.width + layer.width)
		}
		val baseline = newRigImage(width, height); image.pixels.copyInto(baseline.pixels)
		session = PaintRasterSession("mask", "Mask", image, baseline)
	}

	val canUndo get() = !stroking && session.canUndo()
	val canRedo get() = !stroking && session.canRedo()
	val isEmpty: Boolean get() = image.pixels.none { (it ushr 24) != 0 }

	/** One drag segment. [radius] in canvas pixels; [hardness] 1 is a hard edge, 0 fades from the centre. */
	fun segment(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, hardness: Float, erase: Boolean) {
		if (!stroking) { session.beginStroke(); stroking = true }
		val tip = RasterPaintEngine.Tip(radius, hardness)
		session.stroke().addSegment(x0, y0, x1, y1, tip)?.let { session.landSegment(it, MASK_ARGB, 1f, erase) }
	}

	fun endStroke(name: String) { if (stroking) { session.recordStroke(name); stroking = false } }
	fun cancelStroke() { if (stroking) { session.abandonStroke(); stroking = false } }
	fun undo() = session.undo()
	fun redo() = session.redo()

	/** Fills (or clears) the polygon through [points] (canvas pixels): even-odd scanlines at pixel centres. */
	fun polygon(points: List<Pair<Float, Float>>, erase: Boolean) {
		if (points.size < 3) return
		val minY = points.minOf { it.second }.toInt().coerceIn(0, height - 1)
		val maxY = kotlin.math.ceil(points.maxOf { it.second }).toInt().coerceIn(0, height - 1)
		val minX = points.minOf { it.first }.toInt().coerceIn(0, width - 1)
		val maxX = kotlin.math.ceil(points.maxOf { it.first }).toInt().coerceIn(0, width - 1)
		session.willWrite(Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1))
		val px = image.pixels
		val xs = ArrayList<Float>()
		for (y in minY..maxY) {
			val cy = y + 0.5f
			xs.clear()
			for (i in points.indices) {
				val (ax, ay) = points[i]; val (bx, by) = points[(i + 1) % points.size]
				if ((ay <= cy && by > cy) || (by <= cy && ay > cy)) xs += ax + (cy - ay) / (by - ay) * (bx - ax)
			}
			xs.sort()
			var k = 0
			while (k + 1 < xs.size) {
				val from = kotlin.math.ceil(xs[k] - 0.5f).toInt().coerceAtLeast(0); val to = kotlin.math.floor(xs[k + 1] - 0.5f).toInt().coerceAtMost(width - 1)
				for (x in from..to) px[y * width + x] = if (erase) 0 else MASK_ARGB
				k += 2
			}
		}
		session.recordStroke("Polygon")
	}

	/**
	 * The edge brush: paints the pixels of [art] (the drawing) connected to ([x], [y]) within [radius] whose colour is
	 * near the one under the brush - the web's `edgeRegion`, a bounded fill that follows the line art's edge.
	 */
	fun edge(art: Layer, x: Float, y: Float, radius: Float, tolerance: Int, erase: Boolean) {
		val cx = x.toInt(); val cy = y.toInt()
		if (cx !in 0 until width || cy !in 0 until height) return
		val r = kotlin.math.ceil(radius).toInt().coerceIn(1, 128)
		val left = maxOf(0, cx - r); val top = maxOf(0, cy - r); val right = minOf(width - 1, cx + r); val bottom = minOf(height - 1, cy + r)
		val seed = art.at(cx, cy)
		if ((seed ushr 24) < 16) return
		if (!stroking) { session.beginStroke(); stroking = true }
		session.willWrite(Rectangle(left, top, right - left + 1, bottom - top + 1))
		val span = right - left + 1
		val seen = BooleanArray(span * (bottom - top + 1))
		val queue = IntArray(seen.size); var head = 0; var tail = 0
		queue[tail++] = cy * width + cx; seen[(cy - top) * span + cx - left] = true
		val seedLimit = 3 * tolerance * tolerance
		val step = maxOf(8.0, tolerance * 0.65); val stepLimit = 3 * step * step
		val px = image.pixels
		while (head < tail) {
			val i = queue[head++]; val qx = i % width; val qy = i / width
			px[i] = if (erase) 0 else MASK_ARGB
			val here = art.at(qx, qy)
			for (d in 0 until 4) {
				val nx = qx + intArrayOf(-1, 1, 0, 0)[d]; val ny = qy + intArrayOf(0, 0, -1, 1)[d]
				if (nx < left || nx > right || ny < top || ny > bottom || (nx - cx) * (nx - cx) + (ny - cy) * (ny - cy) > r * r) continue
				val local = (ny - top) * span + nx - left
				if (seen[local]) continue
				seen[local] = true
				val c = art.at(nx, ny)
				if ((c ushr 24) < 16) continue
				if (dist(c, seed) <= seedLimit && dist(c, here) <= stepLimit) queue[tail++] = ny * width + nx
			}
		}
	}

	private fun dist(a: Int, b: Int): Int {
		val dr = ((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF); val dg = ((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF); val db = (a and 0xFF) - (b and 0xFF)
		return dr * dr + dg * dg + db * db
	}

	fun clear() { session.willWrite(Rectangle(0, 0, width, height)); image.pixels.fill(0); session.recordStroke("Clear mask") }

	/** The mask as the web stores it: a full-canvas PNG data URL, or null when nothing is painted. */
	fun dataUrl(): String? = Snapshot(this).dataUrl()

	/** The mask's pixels copied at one moment, to encode on another thread while painting goes on. */
	class Snapshot(edit: MaskEdit) {
		private val width = edit.width; private val height = edit.height
		private val pixels = edit.image.pixels.copyOf()
		fun dataUrl(): String? = if (pixels.none { (it ushr 24) != 0 }) null else pngDataUrl(encodePng(width, height, pixels))
	}
}

/** Edits the native studio makes to a project, each the web studio's own (`App.jsx`), returning the new project. */
object StudioEdits {
	fun setMask(project: StudioProject, partId: String, mask: String?): StudioProject =
		project.updatePart(partId, mapOf("mask" to (mask?.let { JsonPrimitive(it) } ?: JsonNull), "candidate" to JsonNull), "Painted mask")

	/**
	 * "Extract": the drawing's pixels under the part's mask (as it is in [project]) become the part's art, and leave
	 * "Remaining artwork". Returns the edit to apply, so it lands on whatever the project is by the time it's done.
	 */
	fun extract(project: StudioProject, partId: String): (StudioProject) -> StudioProject {
		val part = project.part(partId) ?: return { it }
		val mask = part.mask ?: throw IllegalStateException("Paint a mask over the part first.")
		val feather = part.feather
		val art = pngDataUrl(encodePng(project.width, project.height, StudioRender.cutout(project, mask, feather)))
		return { latest ->
			if (latest.part(partId) == null) latest else latest.updatePart(partId, mapOf(
				"cutout" to JsonPrimitive(art), "imported" to JsonPrimitive(false), "extractionMask" to JsonPrimitive(mask), "visible" to JsonPrimitive(true),
			), if (feather > 0) "Extracted source pixels as a separate layer (soft edge ${feather}px)" else "Extracted source pixels as a separate layer")
		}
	}

	fun rename(project: StudioProject, partId: String, name: String) = project.updatePart(partId, mapOf("name" to JsonPrimitive(name)), "Renamed to $name")
	fun group(project: StudioProject, partId: String, group: String) = project.updatePart(partId, mapOf("group" to JsonPrimitive(group)), "Group $group")
	fun visible(project: StudioProject, partId: String, visible: Boolean) =
		project.updatePart(partId, mapOf("visible" to JsonPrimitive(visible)), if (visible) "Shown" else "Hidden")
	fun feather(project: StudioProject, partId: String, feather: Int) = project.updatePart(partId, mapOf("feather" to JsonPrimitive(feather)), "Soft edge ${feather}px")

	fun showRemaining(project: StudioProject, show: Boolean) = project.with(mapOf("showRemaining" to JsonPrimitive(show)), if (show) "Showed the remaining artwork" else "Hid the remaining artwork")

	fun addPart(project: StudioProject): Pair<StudioProject, String> {
		val part = StudioPart.blank("New part ${project.parts.size + 1}", "Custom")
		return project.withParts(project.parts + part) to part.id
	}

	/** Part [id] one step toward the front (+1) or back (-1) - parts run back to front. */
	fun move(project: StudioProject, id: String, direction: Int): StudioProject {
		val parts = project.parts.toMutableList(); val i = parts.indexOfFirst { it.id == id }; val j = i + direction
		if (i < 0 || j !in parts.indices) return project
		parts[i] = parts[j].also { parts[j] = parts[i] }
		return project.withParts(parts, if (direction > 0) "Moved forward" else "Moved backward", id)
	}

	/** Drag in the list: part [id] just in front of (or behind) [targetId]. */
	fun moveTo(project: StudioProject, id: String, targetId: String, inFront: Boolean): StudioProject {
		if (id == targetId) return project
		val moving = project.part(id) ?: return project
		val rest = project.parts.filter { it.id != id }
		val at = rest.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: return project
		val index = at + if (inFront) 1 else 0
		return project.withParts(rest.subList(0, index) + moving + rest.subList(index, rest.size), "Reordered layers", id)
	}

	fun delete(project: StudioProject, id: String): StudioProject {
		if (project.parts.size <= 1) return project
		val name = project.part(id)?.name ?: return project
		return project.withParts(project.parts.filter { it.id != id }, "Deleted $name")
	}

	val componentTemplates = mapOf(
		"Eyes" to listOf("Left Sclera", "Left Iris", "Left Pupil", "Left Upper Lid", "Left Lower Lid", "Left Lashes", "Left Highlight",
			"Right Sclera", "Right Iris", "Right Pupil", "Right Upper Lid", "Right Lower Lid", "Right Lashes", "Right Highlight").map { it to "Eyes" },
		"Mouth" to listOf("Upper Lip", "Lower Lip", "Mouth Cavity", "Upper Teeth", "Lower Teeth", "Tongue").map { it to "Face" },
		"Body" to listOf("Neck", "Torso", "Left Upper Arm", "Left Forearm", "Left Hand", "Right Upper Arm", "Right Forearm", "Right Hand",
			"Left Foot", "Right Foot").map { it to "Body" },
	)

	/** The web's `addComponents`: the slot set's parts not already in the project, on top. */
	fun addComponents(project: StudioProject, kind: String): StudioProject {
		val names = project.parts.map { it.name.lowercase() }.toSet()
		val added = componentTemplates[kind].orEmpty().filter { it.first.lowercase() !in names }.map { (n, g) -> StudioPart.blank(n, g) }
		return if (added.isEmpty()) project else project.withParts(project.parts + added, "Added $kind slots")
	}

	/** The topmost visible part with art at canvas pixel ([x], [y]) - tap to pick a piece. */
	fun partAt(project: StudioProject, x: Int, y: Int): String? {
		for (l in StudioRender.layers(project).asReversed()) if (l.partId != null && l.visible && (l.layer.at(x, y) ushr 24) > 64) return l.partId
		return null
	}

	/** [project] with [field] (`checkpointAt` / `replacedAt`, as the web marks its slots) set to now. */
	fun stamped(project: StudioProject, field: String): StudioProject = StudioProject(kotlinx.serialization.json.JsonObject(project.json + (field to JsonPrimitive(nowIso()))))
}
