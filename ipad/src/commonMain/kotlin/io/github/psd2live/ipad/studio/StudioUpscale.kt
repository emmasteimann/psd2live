package io.github.psd2live.ipad.studio

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The anime upscaler (Real-ESRGAN anime 6B as Core ML): one 288 × 288 tile in, 4× out, planar RGB 0..1. */
expect object AnimeUpscaler {
	val tile: Int
	val factor: Int
	/** Runs [input] (3 × tile × tile, planar R, G, B) and returns 3 × (tile·factor)². Blocking. */
	fun run(input: FloatArray): FloatArray
	/** Loads the compiled model from [path] instead of the app bundle (tests). */
	fun useModelAt(path: String)
	/** What the last tile looked like (for diagnosing). */
	var lastInfo: String
}

/**
 * Clean up / upscale (the web's `upscale-browser.js`, from the Mac's upscale.py): only the art's box plus a margin
 * goes through the model, colour under transparent pixels is filled from the nearest art first (no dark halos), alpha
 * goes through as grey and loses faint specks, and each tile's 4× output is shrunk to the asked-for scale at once
 * (1 = clean-up at the same size), so memory stays one tile deep.
 */
object StudioUpscale {
	private const val TILE = 256; private const val PAD = 16; private const val MARGIN = 24

	fun fillUnder(argb: IntArray, w: Int, h: Int) {
		val n = w * h; val seen = BooleanArray(n); val queue = IntArray(n); var head = 0; var tail = 0
		for (i in 0 until n) if ((argb[i] ushr 24) > 127) { seen[i] = true; queue[tail++] = i }
		if (tail == 0 || tail == n) return
		while (head < tail) {
			val i = queue[head++]; val x = i % w; val y = i / w
			for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
				if (j < 0 || seen[j]) continue; seen[j] = true
				argb[j] = (argb[j] and 0xFF000000.toInt()) or (argb[i] and 0xFFFFFF); queue[tail++] = j
			}
		}
	}

	/** [argb] ([W] × [H]) cleaned up / upscaled by [scale]; [progress] hears tiles done of total. Blocking. */
	fun upscale(argb: IntArray, W: Int, H: Int, scale: Double = 1.0, progress: (Int, Int) -> Unit = { _, _ -> }): IntArray {
		val outW = (W * scale).roundToInt(); val outH = (H * scale).roundToInt()
		val out = IntArray(outW * outH)
		val box = FitGeometry.alphaBox(argb, W, H, 0) ?: return out
		val x0 = max(0, box.x0.toInt() - MARGIN); val y0 = max(0, box.y0.toInt() - MARGIN); val x1 = min(W, box.x1.toInt() + MARGIN); val y1 = min(H, box.y1.toInt() + MARGIN)
		val w = x1 - x0; val h = y1 - y0
		val crop = IntArray(w * h) { argb[(y0 + it / w) * W + x0 + it % w] }
		fillUnder(crop, w, h)
		val T = AnimeUpscaler.tile; val k = AnimeUpscaler.factor; val plane = T * T
		val tiles = ceil(w / TILE.toDouble()).toInt() * ceil(h / TILE.toDouble()).toInt(); var done = 0
		var ty = 0
		while (ty < h) { var tx = 0
			while (tx < w) {
				val px0 = max(0, tx - PAD); val py0 = max(0, ty - PAD); val px1 = min(w, tx + TILE + PAD); val py1 = min(h, ty + TILE + PAD)
				fun input(alpha: Boolean) = FloatArray(3 * plane).also { a ->
					for (y in 0 until T) for (x in 0 until T) {
						val p = crop[min(py1 - 1, py0 + y) * w + min(px1 - 1, px0 + x)]; val d = y * T + x
						if (alpha) { val v = (p ushr 24) / 255f; a[d] = v; a[plane + d] = v; a[2 * plane + d] = v }
						else { a[d] = ((p ushr 16) and 0xFF) / 255f; a[plane + d] = ((p ushr 8) and 0xFF) / 255f; a[2 * plane + d] = (p and 0xFF) / 255f }
					}
				}
				val colour = AnimeUpscaler.run(input(false)); val grey = AnimeUpscaler.run(input(true))
				val ow = T * k; val oplane = ow * ow
				val realW = (px1 - px0) * k; val realH = (py1 - py0) * k
				val big = IntArray(realW * realH)
				for (y in 0 until realH) for (x in 0 until realW) {
					val si = y * ow + x
					var a = (grey[si] + grey[oplane + si] + grey[2 * oplane + si]) / 3f
					if (a < 0.03f) a = 0f // faint specks the model invents in empty space
					fun c(v: Float) = (v * 255 + 0.5f).toInt().coerceIn(0, 255)
					big[y * realW + x] = (c(a) shl 24) or (c(colour[si]) shl 16) or (c(colour[oplane + si]) shl 8) or c(colour[2 * oplane + si])
				}
				// Shrunk with its padding (the filter sees across the seam), then only the tile's own part is kept.
				val sw = max(1, ((px1 - px0) * scale).roundToInt()); val sh = max(1, ((py1 - py0) * scale).roundToInt())
				val small = StudioAI.resample(big, realW, realH, 0, 0, realW, realH, sw, sh)
				val keepX = ((tx - px0) * scale).roundToInt(); val keepY = ((ty - py0) * scale).roundToInt()
				val keepW = ((min(w, tx + TILE) - tx) * scale).roundToInt(); val keepH = ((min(h, ty + TILE) - ty) * scale).roundToInt()
				val dx = ((x0 + tx) * scale).roundToInt(); val dy = ((y0 + ty) * scale).roundToInt()
				for (y in 0 until keepH) for (x in 0 until keepW) {
					val sx = keepX + x; val sy = keepY + y; val ox = dx + x; val oy = dy + y
					if (sx < sw && sy < sh && ox < outW && oy < outH) out[oy * outW + ox] = small[sy * sw + sx]
				}
				progress(++done, tiles)
				tx += TILE }
			ty += TILE }
		return out
	}
}
