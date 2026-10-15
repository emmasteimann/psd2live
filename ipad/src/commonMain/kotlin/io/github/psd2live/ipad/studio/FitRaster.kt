package io.github.psd2live.ipad.studio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pixels for Fit by hand: the cage warp (the web's `drawWarped`) and the soft brush (`softStroke`). */
object FitRaster {
	/**
	 * The piece [img] ([w] x [h], straight ARGB) warped from the [src] grid onto the [dst] grid (n×n cells), drawn into
	 * an [ow] x [oh] image of the canvas region ([rx], [ry]) .. ([rx] + [rw], [ry] + [rh]). Each triangle is drawn with
	 * the affine map from its source to where its handles are, grown 0.8 px so seams don't show; samples are bilinear,
	 * or the nearest pixel when [nearest] (zoomed right in). The region the whole canvas at 1:1 is the bake.
	 */
	fun warp(img: IntArray, w: Int, h: Int, src: List<Pt>, dst: List<Pt>, n: Int,
		rx: Double, ry: Double, rw: Double, rh: Double, ow: Int, oh: Int, nearest: Boolean = false): IntArray {
		val out = IntArray(ow * oh)
		if (src.size != dst.size || src.size != (n + 1) * (n + 1)) return out
		val kx = ow / rw; val ky = oh / rh
		for (t in FitGeometry.gridTriangles(n, n)) {
			// Output pixel -> piece pixel: the affine from the triangle on screen back to where it came from.
			val d = t.map { Pt((dst[it].x - rx) * kx, (dst[it].y - ry) * ky) }
			val s = t.map { src[it] }
			val m = FitGeometry.triangleAffine(d[0], d[1], d[2], s[0], s[1], s[2]) ?: continue
			val c = FitGeometry.centroid(d)
			val grow = 0.8 * kx
			val g = d.map { p -> val dx = p.x - c.x; val dy = p.y - c.y; val len = hypot(dx, dy).takeIf { it > 0 } ?: 1.0; Pt(p.x + dx / len * grow, p.y + dy / len * grow) }
			val x0 = max(0, floor(g.minOf { it.x }).toInt()); val x1 = min(ow - 1, ceil(g.maxOf { it.x }).toInt())
			val y0 = max(0, floor(g.minOf { it.y }).toInt()); val y1 = min(oh - 1, ceil(g.maxOf { it.y }).toInt())
			if (x0 > x1 || y0 > y1) continue
			// Edge functions of the grown triangle, signed so the inside is positive.
			val area = (g[1].x - g[0].x) * (g[2].y - g[0].y) - (g[2].x - g[0].x) * (g[1].y - g[0].y)
			if (abs(area) < 1e-9) continue
			val sg = if (area > 0) 1.0 else -1.0
			fun edge(a: Pt, b: Pt, px: Double, py: Double) = sg * ((b.x - a.x) * (py - a.y) - (b.y - a.y) * (px - a.x))
			for (y in y0..y1) {
				val py = y + 0.5
				for (x in x0..x1) {
					val px = x + 0.5
					if (edge(g[0], g[1], px, py) < 0 || edge(g[1], g[2], px, py) < 0 || edge(g[2], g[0], px, py) < 0) continue
					val sx = m[0] * px + m[2] * py + m[4]; val sy = m[1] * px + m[3] * py + m[5]
					val p = if (nearest) at(img, w, h, floor(sx).toInt(), floor(sy).toInt()) else bilinear(img, w, h, sx - 0.5, sy - 0.5)
					if ((p ushr 24) == 0) continue
					val o = y * ow + x
					out[o] = StudioRender.blend(out[o], p)
				}
			}
		}
		return out
	}

	private fun at(img: IntArray, w: Int, h: Int, x: Int, y: Int) = if (x in 0 until w && y in 0 until h) img[y * w + x] else 0

	/** Bilinear in premultiplied space (no dark fringes at the edge), back to straight alpha. */
	private fun bilinear(img: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
		val x0 = floor(x).toInt(); val y0 = floor(y).toInt(); val fx = x - x0; val fy = y - y0
		var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
		for (k in 0 until 4) {
			val xx = x0 + (k and 1); val yy = y0 + (k shr 1)
			val wt = (if (k and 1 == 1) fx else 1 - fx) * (if (k shr 1 == 1) fy else 1 - fy)
			if (wt == 0.0) continue
			val p = at(img, w, h, xx, yy); val pa = (p ushr 24) * wt
			a += pa; r += ((p ushr 16) and 0xFF) * pa; g += ((p ushr 8) and 0xFF) * pa; b += (p and 0xFF) * pa
		}
		if (a < 0.5) return 0
		return (a.toInt().coerceIn(0, 255) shl 24) or ((r / a).toInt().coerceIn(0, 255) shl 16) or ((g / a).toInt().coerceIn(0, 255) shl 8) or (b / a).toInt().coerceIn(0, 255)
	}

	/**
	 * A brush stroke from [a] to [b] into [alpha] (0-255 per pixel, [w] wide): [size] wide, its edge fading over
	 * [feather] (0 hard, 1 soft to the middle). Painting builds coverage up (source-over); [erase] takes it away
	 * (destination-out). Returns the touched area as x0, y0, x1, y1, or null.
	 */
	fun stroke(alpha: ByteArray, w: Int, h: Int, a: Pt, b: Pt, size: Double, feather: Double, erase: Boolean): IntArray? {
		val r = size / 2
		val inner = r * (1 - min(1.0, feather))
		val x0 = max(0, floor(min(a.x, b.x) - r - 1).toInt()); val x1 = min(w - 1, ceil(max(a.x, b.x) + r + 1).toInt())
		val y0 = max(0, floor(min(a.y, b.y) - r - 1).toInt()); val y1 = min(h - 1, ceil(max(a.y, b.y) + r + 1).toInt())
		if (x0 > x1 || y0 > y1) return null
		val vx = b.x - a.x; val vy = b.y - a.y; val len2 = vx * vx + vy * vy
		for (y in y0..y1) for (x in x0..x1) {
			val px = x + 0.5 - a.x; val py = y + 0.5 - a.y
			val t = if (len2 == 0.0) 0.0 else ((px * vx + py * vy) / len2).coerceIn(0.0, 1.0)
			val d = hypot(px - t * vx, py - t * vy)
			val cov = if (feather <= 0) (r - d + 0.5).coerceIn(0.0, 1.0) else if (d <= inner) 1.0 else if (d >= r) 0.0 else (r - d) / (r - inner)
			if (cov <= 0) continue
			val i = y * w + x; val old = alpha[i].toInt() and 0xFF
			val next = if (erase) old * (1 - cov) else old + cov * (255 - old)
			alpha[i] = next.toInt().coerceIn(0, 255).toByte()
		}
		return intArrayOf(x0, y0, x1, y1)
	}

	/** [alpha] (0-255) as straight ARGB in [rgb], for showing or saving a mask. */
	fun tinted(alpha: ByteArray, rgb: Int, opacity: Double = 1.0): IntArray = IntArray(alpha.size) { i ->
		val a = ((alpha[i].toInt() and 0xFF) * opacity).toInt()
		if (a == 0) 0 else (a shl 24) or (rgb and 0xFFFFFF)
	}

}
