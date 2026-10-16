package io.github.psd2live.ipad.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fill hidden gaps, the web's `GapsPanel`: Auto-split marks (`gapMask`) where a body layer must be painted in behind a
 * part that moves - the robe behind a swinging lantern, under a sleeve. Each is one Reveal edit: the model is shown the
 * picture without whatever covers the gap, and asked for the part continuing behind, inside a soft blob grown from the
 * gap (a lantern-shaped hole invites a lantern). What's painted goes under the part's own art, so only the hole changes.
 */
object StudioGaps {
	private val what = mapOf(
		"topwear" to "the robe / top's fabric continuing behind, with its folds and shading",
		"bottomwear" to "the skirt or trousers continuing behind",
		"neck" to "the neck continuing down behind",
		"neckwear" to "the collar or neckwear continuing behind",
		"legwear" to "the legs continuing behind",
	)

	fun whatFor(part: StudioPart) =
		"${what[part.json.str("seeThroughTag")] ?: "the ${part.name.lowercase()} continuing behind"} -- just that, with no object, accessory, lantern, charm or body part drawn in it"

	/** Parts with a gap still to paint in. */
	fun todo(project: StudioProject) = project.parts.filter { it.json.str("gapMask") != null }

	/** A painted-in part waiting for review: its new art, and the box to look at. */
	class Item(val partId: String, val name: String, val art: String, val bounds: IntArray)

	var pending by mutableStateOf<List<Item>>(emptyList())

	private fun alphaOf(url: String, w: Int, h: Int): IntArray {
		val out = IntArray(w * h); val l = StudioImages.layer(url, w, h)
		for (r in 0 until l.height) for (c in 0 until l.width) out[(l.y + r) * w + l.x + c] = l.argb[r * l.width + c] ushr 24
		return out
	}

	/** One box-blur pass of radius [r] along rows then columns (running sums, so any radius costs the same). */
	private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
		val tmp = FloatArray(src.size); val out = FloatArray(src.size); val n = (2 * r + 1).toFloat()
		for (y in 0 until h) {
			var sum = 0f; val row = y * w
			for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
			for (x in 0 until w) { tmp[row + x] = sum / n; sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)] }
		}
		for (x in 0 until w) {
			var sum = 0f
			for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
			for (y in 0 until h) { out[y * w + x] = sum / n; sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x] }
		}
		return out
	}

	/**
	 * The gap grown into a soft blob, like the web's canvas `blur(3% of the canvas)` drawn three times and cut at 30% of
	 * its own peak (a tiny gap spreads thin); always at least the gap itself. Three box passes approximate the Gaussian.
	 */
	fun blob(gap: IntArray, w: Int, h: Int): ByteArray {
		val sigma = 0.03 * max(w, h)
		val r = max(1, ((sqrt(4.0 * sigma * sigma + 1) - 1) / 2).roundToInt())
		var a = FloatArray(w * h) { gap[it] / 255f }
		repeat(3) { a = boxBlur(a, w, h, r) }
		var peak = 0f
		for (i in a.indices) { val v = 1 - (1 - a[i]).let { it * it * it }; a[i] = v * 255; if (a[i] > peak) peak = a[i] }
		val cut = max(1f, min(20f, peak * 0.3f))
		return ByteArray(w * h) { if (a[it] > cut || gap[it] >= 128) 1 else 0 }
	}

	/** Paints in [partId]'s gap: one image edit. */
	suspend fun paint(project: StudioProject, partId: String): Item {
		val w = project.width; val h = project.height
		val part = project.part(partId) ?: throw IllegalStateException("That part is gone.")
		val gap = alphaOf(part.json.str("gapMask") ?: throw IllegalStateException("${part.name} has no gap to fill."), w, h)
		// The model is shown the picture without whatever covers the gap (the lantern, an arm): it sees a hole in the
		// robe to fill, not a lantern to repaint.
		val at = project.parts.indexOfFirst { it.id == partId }
		val inFront = StudioRender.layers(project).filter { l -> l.partId != null && project.parts.indexOfFirst { it.id == l.partId } > at }
		val hide = inFront.filter { l -> val L = l.layer; var hit = false
			for (r in 0 until L.height) { for (c in 0 until L.width) if ((L.argb[r * L.width + c] ushr 24) > 64 && gap[(L.y + r) * w + L.x + c] > 64) { hit = true; break }; if (hit) break }
			hit }.mapNotNull { it.partId }.toSet()
		val region = blob(gap, w, h)
		val maskUrl = pngDataUrl(encodePng(w, h, IntArray(w * h) { if (region[it].toInt() != 0) MASK_ARGB else 0 }))
		val context = project.withParts(project.parts.map { p ->
			when { p.id in hide -> p.with(mapOf("visible" to JsonPrimitive(false))); p.id == partId -> p.with(mapOf("mask" to JsonPrimitive(maskUrl))); else -> p }
		})
		val art = StudioAI.peelReveal(context, partId, whatFor(part))
		var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
		for (y in 0 until h) for (x in 0 until w) if (region[y * w + x].toInt() != 0) { x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) }
		return Item(partId, part.name, art, intArrayOf(x0, y0, x1, y1))
	}

	/** How it looks: the part with [art] (null = as it is now) and everything in front of it hidden, so the painted-in area shows. */
	fun alone(project: StudioProject, partId: String, art: String?): IntArray {
		val at = project.parts.indexOfFirst { it.id == partId }
		return StudioRender.composite(project.withParts(project.parts.mapIndexed { i, p ->
			when { p.id == partId && art != null -> p.with(mapOf("cutout" to JsonPrimitive(art))); i > at -> p.with(mapOf("visible" to JsonPrimitive(false))); else -> p }
		}))
	}

	/** Keeps them: each part's art replaced as a Reveal (Undo last peel puts it back) and its gap mark cleared. */
	fun apply(project: StudioProject, items: List<Item>): StudioProject {
		var next = project
		for (it in items) {
			val part = next.part(it.partId) ?: continue
			next = StudioAI.applyReveal(next, it.partId, it.art, whatFor(part))
			next = next.updatePart(it.partId, mapOf("gapMask" to null), "Painted in the hidden gap behind ${part.name}")
		}
		return next
	}
}
