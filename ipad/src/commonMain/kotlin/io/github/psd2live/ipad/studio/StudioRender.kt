package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonObject

/**
 * A full-canvas layer held as just the block with pixels: ([x], [y]) is the block's corner on the canvas. The file
 * convention is a full-canvas PNG per image, and most of every one is empty; this keeps memory to what's drawn.
 */
class Layer(val canvasWidth: Int, val canvasHeight: Int, val x: Int, val y: Int, val width: Int, val height: Int, val argb: IntArray) {
	/** The canvas pixel at ([cx], [cy]), transparent outside the block. */
	fun at(cx: Int, cy: Int): Int {
		val lx = cx - x; val ly = cy - y
		return if (lx in 0 until width && ly in 0 until height) argb[ly * width + lx] else 0
	}

	/** The whole canvas, the block placed where it belongs. */
	fun full(): IntArray {
		val out = IntArray(canvasWidth * canvasHeight)
		for (row in 0 until height) argb.copyInto(out, (y + row) * canvasWidth + x, row * width, row * width + width)
		return out
	}

	val isEmpty: Boolean get() = width == 0 || height == 0

	companion object {
		/** [argb] (the whole canvas) trimmed to its pixels. */
		fun trimmed(w: Int, h: Int, argb: IntArray): Layer {
			var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
			for (yy in 0 until h) {
				val base = yy * w
				for (xx in 0 until w) if ((argb[base + xx] ushr 24) != 0) {
					if (xx < x0) x0 = xx; if (xx > x1) x1 = xx; if (yy < y0) y0 = yy; if (yy > y1) y1 = yy
				}
			}
			if (x1 < 0) return Layer(w, h, 0, 0, 0, 0, IntArray(0))
			val lw = x1 - x0 + 1; val lh = y1 - y0 + 1
			val out = IntArray(lw * lh)
			for (row in 0 until lh) argb.copyInto(out, row * lw, (y0 + row) * w + x0, (y0 + row) * w + x0 + lw)
			return Layer(w, h, x0, y0, lw, lh, out)
		}
	}
}

/**
 * Decoded images, by their data URL: decoding a big PNG is the slow part of drawing a project, and most edits change
 * one image. Holds the most recently used, up to about [budgetPixels].
 */
object StudioImages {
	private const val budgetPixels = 160_000_000L
	private class Key(val url: String) {
		private val hash = run {
			// Length plus samples from the start, middle and end (a PNG's last chunk CRC sits near its end): cheap and
			// distinct in practice; equality then compares the whole text.
			val n = url.length
			var h = n
			for (i in listOf(0, n / 2, maxOf(0, n - 96))) for (j in i until minOf(n, i + 96)) h = h * 31 + url[j].code
			h
		}
		override fun hashCode() = hash
		override fun equals(other: Any?) = other is Key && other.hash == hash && (other.url === url || other.url == url)
	}
	private val cache = LinkedHashMap<Key, Layer>()
	private var used = 0L

	/** The image at [url] (a PNG data URL) on a [w] x [h] canvas, trimmed. A missing or broken image is empty. */
	fun layer(url: String?, w: Int, h: Int): Layer {
		if (url.isNullOrEmpty()) return Layer(w, h, 0, 0, 0, 0, IntArray(0))
		val key = Key(url)
		cache.remove(key)?.let { cache[key] = it; return it }
		val decoded = try { decodeImage(dataUrlBytes(url)) } catch (_: Throwable) { null }
		val layer = when {
			decoded == null -> Layer(w, h, 0, 0, 0, 0, IntArray(0))
			decoded.width == w && decoded.height == h -> Layer.trimmed(w, h, decoded.argb)
			else -> Layer.trimmed(w, h, drawnAt(decoded, w, h))
		}
		cache[key] = layer; used += layer.argb.size
		while (used > budgetPixels && cache.size > 1) {
			val eldest = cache.keys.first(); used -= cache.remove(eldest)!!.argb.size
		}
		return layer
	}

	/** An image of another size drawn at the canvas's corner, as a canvas `drawImage(bitmap, 0, 0)` would. */
	private fun drawnAt(p: Pixels, w: Int, h: Int): IntArray {
		val out = IntArray(w * h)
		for (row in 0 until minOf(h, p.height)) p.argb.copyInto(out, row * w, row * p.width, row * p.width + minOf(w, p.width))
		return out
	}

	fun clear() { cache.clear(); used = 0 }
}

/** One layer of the project as it exports: the web's `renderLayers` entries. */
class RenderedLayer(val name: String, val partId: String?, val layer: Layer, val visible: Boolean, val reconstructed: Boolean)

object StudioRender {
	/**
	 * The project's layers bottom to top, the web's `renderLayers`: "Remaining artwork" (the drawing with what parts
	 * took out of it removed), approved hidden-art patches that target no part, then each part with art, its targeted
	 * patches under its own pixels. [overrides] replace a part's art (variant previews).
	 */
	fun layers(project: StudioProject, includeHidden: Boolean = false, overrides: Map<String, String> = emptyMap()): List<RenderedLayer> {
		val w = project.width; val h = project.height
		val out = mutableListOf<RenderedLayer>()
		val showRest = project.showRemaining ?: !project.isGeneratedParts
		if (showRest || includeHidden) out += RenderedLayer("Remaining artwork", null, remaining(project), showRest, false)
		val ids = project.parts.map { it.id }.toSet()
		val targeted = mutableMapOf<String, MutableList<JsonObject>>()
		for (part in project.parts) for ((index, repair) in part.repairs.withIndex()) {
			val target = repair.str("targetPartId")
			if (target != null && target in ids) { targeted.getOrPut(target) { mutableListOf() } += repair; continue }
			out += RenderedLayer("${part.name} — hidden art ${index + 1}", null, StudioImages.layer(repair.str("patch"), w, h), true, true)
		}
		for (part in project.parts) {
			val patches = targeted[part.id].orEmpty()
			val override = overrides[part.id]
			if (part.cutout == null && patches.isEmpty() && override == null) continue
			val art = StudioImages.layer(override ?: part.cutout, w, h)
			val layer = if (override != null || patches.isEmpty()) art else {
				val full = IntArray(w * h)
				for (patch in patches) over(full, w, StudioImages.layer(patch.str("patch"), w, h))
				over(full, w, art)
				Layer.trimmed(w, h, full)
			}
			if (includeHidden || part.visible) out += RenderedLayer(part.name, part.id, layer, part.visible, patches.isNotEmpty())
		}
		return out
	}

	private var remainingKey: List<String?>? = null
	private var remainingLayer: Layer? = null

	/** The drawing minus every extracted part's footprint (kept until the drawing or a footprint changes). */
	fun remaining(project: StudioProject): Layer {
		val masks = project.parts.filter { it.cutout != null }.mapNotNull { it.extractionMask }
		val key = listOf(project.source) + masks
		remainingLayer?.let { if (remainingKey?.size == key.size && remainingKey!!.indices.all { remainingKey!![it] === key[it] || remainingKey!![it] == key[it] }) return it }
		val w = project.width; val h = project.height
		val base = StudioImages.layer(project.source, w, h).full()
		for (url in masks) {
			val m = StudioImages.layer(url, w, h)
			for (row in 0 until m.height) for (col in 0 until m.width) {
				val ma = m.argb[row * m.width + col] ushr 24
				if (ma == 0) continue
				val i = (m.y + row) * w + m.x + col
				val p = base[i]; val a = p ushr 24
				// destination-out: what's left is the base's alpha times (1 - the mask's).
				base[i] = ((a * (255 - ma) + 127) / 255 shl 24) or (p and 0xFFFFFF)
			}
		}
		return Layer.trimmed(w, h, base).also { remainingKey = key; remainingLayer = it }
	}

	/** The visible layers drawn together, the whole canvas - the web's `projectComposite`. */
	fun composite(project: StudioProject, overrides: Map<String, String> = emptyMap()): IntArray {
		val out = IntArray(project.width * project.height)
		for (l in layers(project, false, overrides)) over(out, project.width, l.layer)
		return out
	}

	/** [layer] drawn source-over onto [dst] (the whole canvas, [w] wide), straight alpha as a canvas does. */
	fun over(dst: IntArray, w: Int, layer: Layer) {
		for (row in 0 until layer.height) {
			val src = row * layer.width; val d = (layer.y + row) * w + layer.x
			for (col in 0 until layer.width) dst[d + col] = blend(dst[d + col], layer.argb[src + col])
		}
	}

	fun blend(d: Int, s: Int): Int {
		val sa = s ushr 24
		if (sa == 0) return d
		if (sa == 255) return s
		val da = d ushr 24
		if (da == 0) return s
		val dw = da * (255 - sa) // destination weight, scaled by 255
		val oa255 = sa * 255 + dw
		val r = (((s ushr 16) and 0xFF) * sa * 255 + ((d ushr 16) and 0xFF) * dw) / oa255
		val g = (((s ushr 8) and 0xFF) * sa * 255 + ((d ushr 8) and 0xFF) * dw) / oa255
		val b = ((s and 0xFF) * sa * 255 + (d and 0xFF) * dw) / oa255
		return ((oa255 + 127) / 255 shl 24) or (r shl 16) or (g shl 8) or b
	}

	/**
	 * The drawing's pixels under [mask] (destination-in by the mask's alpha), its edge softened by [feather] source
	 * pixels first - the web's `cutout` with `featherMaskCanvas`. The whole canvas.
	 */
	fun cutout(project: StudioProject, maskUrl: String, feather: Int): IntArray {
		val w = project.width; val h = project.height
		val source = StudioImages.layer(project.source, w, h)
		val mask = StudioImages.layer(maskUrl, w, h)
		val alpha = IntArray(w * h)
		for (row in 0 until mask.height) for (col in 0 until mask.width) alpha[(mask.y + row) * w + mask.x + col] = mask.argb[row * mask.width + col] ushr 24
		if (feather > 0) blurAlpha(alpha, w, h, feather)
		val out = IntArray(w * h)
		for (row in 0 until source.height) for (col in 0 until source.width) {
			val i = (source.y + row) * w + source.x + col
			val ma = alpha[i]; if (ma == 0) continue
			val p = source.argb[row * source.width + col]
			out[i] = (((p ushr 24) * ma + 127) / 255 shl 24) or (p and 0xFFFFFF)
		}
		return out
	}

	/** Blurs [alpha] (0-255 per pixel) in place by about [radius] pixels: three box blurs, the web's `blurAlpha`. */
	fun blurAlpha(alpha: IntArray, width: Int, height: Int, radius: Int) {
		val r = kotlin.math.round(radius / 2.0).toInt()
		if (r <= 0 || width < 1 || height < 1) return
		val work = FloatArray(alpha.size) { alpha[it].toFloat() }
		val temp = FloatArray(alpha.size)
		repeat(3) {
			boxLines(work, temp, width, height, 1, width, r)
			boxLines(temp, work, height, width, width, 1, r)
		}
		for (i in alpha.indices) alpha[i] = kotlin.math.round(work[i]).toInt().coerceIn(0, 255)
	}

	private fun boxLines(from: FloatArray, to: FloatArray, count: Int, lines: Int, step: Int, stride: Int, r: Int) {
		val span = 2 * r + 1
		for (line in 0 until lines) {
			val base = line * stride
			fun at(i: Int) = from[base + i.coerceIn(0, count - 1) * step]
			var sum = 0f
			for (i in -r..r) sum += at(i)
			for (i in 0 until count) {
				to[base + i * step] = sum / span
				sum += at(i + r + 1) - at(i - r)
			}
		}
	}
}
