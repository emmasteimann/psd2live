package io.github.psd2live.ipad.studio

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Fit by hand's geometry, the web studio's `fit.js` line for line: a warp cage over the piece's box (each cell two
// triangles, each drawn with the affine map from where it was to where its handles are), the four-corner distort,
// and the outline snap onto the drawing's lines.

data class Pt(val x: Double, val y: Double)

/** A box x0..x1, y0..y1 (x1, y1 exclusive for pixel boxes). */
data class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
	val w get() = x1 - x0
	val h get() = y1 - y0
}

object FitGeometry {
	/** The box of pixels with alpha above [min] in [argb] (full canvas), or null. */
	fun alphaBox(argb: IntArray, w: Int, h: Int, min: Int = 8): Box? {
		var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
		for (y in 0 until h) { val b = y * w; for (x in 0 until w) if ((argb[b + x] ushr 24) > min) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y } }
		return if (x1 < 0) null else Box(x0.toDouble(), y0.toDouble(), (x1 + 1).toDouble(), (y1 + 1).toDouble())
	}

	/** (cols+1)×(rows+1) points over a box, row by row. */
	fun gridPoints(box: Box, cols: Int, rows: Int): List<Pt> = buildList {
		for (j in 0..rows) for (i in 0..cols) add(Pt(box.x0 + box.w * i / cols, box.y0 + box.h * j / rows))
	}

	/** The same warp on an n×n cage: a fresh grid's points carried through the old cage's triangles. */
	fun resampleCage(box: Box, oldN: Int, oldDst: List<Pt>, n: Int): List<Pt> {
		val w = if (box.w == 0.0) 1.0 else box.w; val h = if (box.h == 0.0) 1.0 else box.h
		fun at(i: Int, j: Int) = oldDst[j * (oldN + 1) + i]
		return gridPoints(box, n, n).map { p ->
			val u = (p.x - box.x0) / w * oldN; val v = (p.y - box.y0) / h * oldN
			val i = min(oldN - 1, max(0, floor(u).toInt())); val j = min(oldN - 1, max(0, floor(v).toInt()))
			val fu = u - i; val fv = v - j
			val p00 = at(i, j); val p10 = at(i + 1, j); val p01 = at(i, j + 1); val p11 = at(i + 1, j + 1)
			if (fu + fv <= 1) Pt(p00.x + fu * (p10.x - p00.x) + fv * (p01.x - p00.x), p00.y + fu * (p10.y - p00.y) + fv * (p01.y - p00.y))
			else Pt(p11.x + (1 - fu) * (p01.x - p11.x) + (1 - fv) * (p10.x - p11.x), p11.y + (1 - fu) * (p01.y - p11.y) + (1 - fv) * (p10.y - p11.y))
		}
	}

	/** The cage's four corner indices, clockwise from top-left, for an n×n cage. */
	fun cageCorners(n: Int) = intArrayOf(0, n, (n + 1) * (n + 1) - 1, n * (n + 1))

	/** The perspective map (3×3 row-major, last 1) taking quad [a] onto quad [b], or null if degenerate. */
	fun homography(a: List<Pt>, b: List<Pt>): DoubleArray? {
		val m = Array(8) { DoubleArray(8) }; val r = DoubleArray(8)
		for (k in 0 until 4) {
			val (x, y) = a[k]; val (u, v) = b[k]
			m[2 * k] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y); r[2 * k] = u
			m[2 * k + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y); r[2 * k + 1] = v
		}
		for (c in 0 until 8) {
			var piv = c
			for (i in c + 1 until 8) if (abs(m[i][c]) > abs(m[piv][c])) piv = i
			if (abs(m[piv][c]) < 1e-10) return null
			val t = m[c]; m[c] = m[piv]; m[piv] = t; val tr = r[c]; r[c] = r[piv]; r[piv] = tr
			for (i in 0 until 8) {
				if (i == c) continue
				val f = m[i][c] / m[c][c]
				for (j in c until 8) m[i][j] -= f * m[c][j]
				r[i] -= f * r[c]
			}
		}
		return DoubleArray(9) { if (it == 8) 1.0 else r[it] / m[it][it] }
	}

	fun applyHomography(m: DoubleArray, p: Pt): Pt {
		val w = m[6] * p.x + m[7] * p.y + 1
		return Pt((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
	}

	/** True if a quad still goes round one way with no fold or pinch. */
	fun convexQuad(q: List<Pt>): Boolean {
		var sign = 0
		for (i in 0 until 4) {
			val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
			val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
			if (abs(cross) < 1e-6) return false
			val s = if (cross > 0) 1 else -1
			if (sign != 0 && s != sign) return false
			sign = s
		}
		return true
	}

	/** The two triangles of every cell, as index triples. */
	fun gridTriangles(cols: Int, rows: Int): List<IntArray> = buildList {
		fun at(i: Int, j: Int) = j * (cols + 1) + i
		for (j in 0 until rows) for (i in 0 until cols) {
			add(intArrayOf(at(i, j), at(i + 1, j), at(i, j + 1)))
			add(intArrayOf(at(i + 1, j), at(i + 1, j + 1), at(i, j + 1)))
		}
	}

	/** The affine [a, b, c, d, e, f] (x' = a x + c y + e, y' = b x + d y + f) taking s0 s1 s2 onto d0 d1 d2, or null. */
	fun triangleAffine(s0: Pt, s1: Pt, s2: Pt, d0: Pt, d1: Pt, d2: Pt): DoubleArray? {
		val det = (s1.x - s0.x) * (s2.y - s0.y) - (s2.x - s0.x) * (s1.y - s0.y)
		if (abs(det) < 1e-9) return null
		val ux = s1.x - s0.x; val uy = s1.y - s0.y; val vx = s2.x - s0.x; val vy = s2.y - s0.y
		val pu = d1.x - d0.x; val pv = d2.x - d0.x; val qu = d1.y - d0.y; val qv = d2.y - d0.y
		val a = (pu * vy - pv * uy) / det; val c = (pv * ux - pu * vx) / det
		val b = (qu * vy - qv * uy) / det; val d = (qv * ux - qu * vx) / det
		return doubleArrayOf(a, b, c, d, d0.x - a * s0.x - c * s0.y, d0.y - b * s0.x - d * s0.y)
	}

	/** Points moved by ([dx], [dy]), scaled and turned by [degrees] about [centre]. */
	fun transformPoints(points: List<Pt>, dx: Double = 0.0, dy: Double = 0.0, scale: Double = 1.0, degrees: Double = 0.0, centre: Pt): List<Pt> {
		val r = degrees * kotlin.math.PI / 180; val c = cos(r) * scale; val s = sin(r) * scale
		return points.map { p -> val x = p.x - centre.x; val y = p.y - centre.y; Pt(centre.x + c * x - s * y + dx, centre.y + s * x + c * y + dy) }
	}

	fun centroid(points: List<Pt>): Pt = Pt(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)

	fun bounds(points: List<Pt>): Box = Box(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })

	/** Index of the point nearest [p] within [radius], or -1. */
	fun nearestHandle(points: List<Pt>, p: Pt, radius: Double): Int {
		var best = -1; var bestD = radius
		points.forEachIndexed { i, q -> val d = hypot(q.x - p.x, q.y - p.y); if (d <= bestD) { best = i; bestD = d } }
		return best
	}

	// ---- Mark it, then snap ----

	private fun grey(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
		val p = argb[i]; val a = (p ushr 24) / 255f
		(0.299f * ((p ushr 16) and 0xFF) + 0.587f * ((p ushr 8) and 0xFF) + 0.114f * (p and 0xFF)) * a + 255f * (1 - a)
	}

	/** Edge mask (1 = line): Sobel on the drawing flattened onto white. */
	fun edgeMask(argb: IntArray, w: Int, h: Int, threshold: Float = 60f): ByteArray {
		val g = grey(argb); val out = ByteArray(w * h)
		for (y in 1 until h - 1) for (x in 1 until w - 1) {
			val i = y * w + x
			val gx = g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1] - g[i - w - 1] - 2 * g[i - 1] - g[i + w - 1]
			val gy = g[i + w - 1] + 2 * g[i + w] + g[i + w + 1] - g[i - w - 1] - 2 * g[i - w] - g[i - w + 1]
			if (hypot(gx, gy) > threshold) out[i] = 1
		}
		return out
	}

	/** Distance (px, chamfer 3-4 / 3) to the nearest edge, capped at [cap]. */
	fun distanceMap(edges: ByteArray, w: Int, h: Int, cap: Float = 30f): FloatArray {
		val inf = 1e9f; val d = FloatArray(w * h) { if (edges[it].toInt() != 0) 0f else inf }
		for (y in 0 until h) for (x in 0 until w) {
			val i = y * w + x
			if (x > 0) d[i] = min(d[i], d[i - 1] + 3)
			if (y > 0) { d[i] = min(d[i], d[i - w] + 3); if (x > 0) d[i] = min(d[i], d[i - w - 1] + 4); if (x < w - 1) d[i] = min(d[i], d[i - w + 1] + 4) }
		}
		for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
			val i = y * w + x
			if (x < w - 1) d[i] = min(d[i], d[i + 1] + 3)
			if (y < h - 1) { d[i] = min(d[i], d[i + w] + 3); if (x < w - 1) d[i] = min(d[i], d[i + w + 1] + 4); if (x > 0) d[i] = min(d[i], d[i + w - 1] + 4) }
		}
		for (i in d.indices) d[i] = min(cap, d[i] / 3)
		return d
	}

	/** Up to [max] points on the outer edge of the opaque pixels (alpha > 128). */
	fun outlinePoints(argb: IntArray, w: Int, h: Int, max: Int = 600): List<Pt> {
		fun on(x: Int, y: Int) = x >= 0 && y >= 0 && x < w && y < h && (argb[y * w + x] ushr 24) > 128
		val pts = ArrayList<Pt>()
		for (y in 0 until h) for (x in 0 until w) if (on(x, y) && (!on(x - 1, y) || !on(x + 1, y) || !on(x, y - 1) || !on(x, y + 1))) pts += Pt(x.toDouble(), y.toDouble())
		val step = max(1, pts.size / max)
		return pts.filterIndexed { i, _ -> i % step == 0 }
	}

	class Change(val dx: Double, val dy: Double, val degrees: Double, val scale: Double)
	class Snap(val change: Change, val before: Double, val after: Double)

	/** The move / turn / scale that best lays [pts] on the drawing's lines (pattern search; scale held to 0.8-1.25). */
	fun snapToLines(pts: List<Pt>, dist: FloatArray, w: Int, h: Int, centre: Pt): Snap {
		if (pts.isEmpty()) return Snap(Change(0.0, 0.0, 0.0, 1.0), 0.0, 0.0)
		fun cost(p: DoubleArray): Double {
			if (p[3] < 0.8 || p[3] > 1.25) return 1e9
			val moved = transformPoints(pts, p[0], p[1], p[3], p[2], centre)
			var s = 0.0
			for (q in moved) { val x = q.x.roundToInt(); val y = q.y.roundToInt(); s += if (x < 0 || y < 0 || x >= w || y >= h) 30.0 else dist[y * w + x].toDouble() }
			return s / moved.size
		}
		val before = cost(doubleArrayOf(0.0, 0.0, 0.0, 1.0))
		var best: DoubleArray? = null; var bestC = 0.0
		for ((sx, sy) in listOf(0 to 0, -15 to 0, 15 to 0, 0 to -15, 0 to 15)) {
			var p = doubleArrayOf(sx.toDouble(), sy.toDouble(), 0.0, 1.0); var c = cost(p)
			val steps = doubleArrayOf(8.0, 8.0, 2.0, 0.04)
			for (round in 0 until 60) {
				var improved = false
				for (k in 0 until 4) for (sgn in intArrayOf(1, -1)) {
					val q = p.copyOf(); q[k] += sgn * steps[k]; val cq = cost(q)
					if (cq < c) { p = q; c = cq; improved = true }
				}
				if (!improved) { for (k in 0 until 4) steps[k] /= 2; if (steps[0] < 0.25) break }
			}
			if (best == null || c < bestC) { best = p; bestC = c }
		}
		val b = best!!
		return Snap(Change(b[0], b[1], b[2], b[3]), before, bestC)
	}

	/** Line art for "lines only": thin edges (non-maximum suppression), the stronger ones (80th percentile, at least 70). */
	fun lineArt(argb: IntArray, w: Int, h: Int): ByteArray {
		val g = grey(argb); val mag = FloatArray(w * h); val dir = ByteArray(w * h)
		for (y in 1 until h - 1) for (x in 1 until w - 1) {
			val i = y * w + x
			val gx = g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1] - g[i - w - 1] - 2 * g[i - 1] - g[i + w - 1]
			val gy = g[i + w - 1] + 2 * g[i + w] + g[i + w + 1] - g[i - w - 1] - 2 * g[i - w] - g[i - w + 1]
			mag[i] = hypot(gx, gy)
			val ang = ((atan2(gy, gx) * 180 / kotlin.math.PI.toFloat()) + 180) % 180
			dir[i] = (if (ang < 22.5f || ang >= 157.5f) 0 else if (ang < 67.5f) 1 else if (ang < 112.5f) 2 else 3).toByte()
		}
		val strong = ArrayList<Float>()
		var i0 = 0; while (i0 < mag.size) { if (mag[i0] > 20) strong += mag[i0]; i0 += 7 }
		strong.sort()
		val thr = max(70f, if (strong.isNotEmpty()) strong[(strong.size * 0.8).toInt()] else 70f)
		val off = arrayOf(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1))
		val out = ByteArray(w * h)
		for (y in 1 until h - 1) for (x in 1 until w - 1) {
			val i = y * w + x; val m = mag[i]
			if (m < thr) continue
			val (dx, dy) = off[dir[i].toInt()].let { it[0] to it[1] }
			if (m >= mag[i + dy * w + dx] && m >= mag[i - dy * w - dx]) out[i] = 1
		}
		return out
	}

	/** Magic wand: pixels connected to ([x], [y]) within [tolerance] per channel of it, among opaque ones (alpha ≥ 16). */
	fun wandSelect(argb: IntArray, w: Int, h: Int, x: Int, y: Int, tolerance: Int = 28): ByteArray {
		val out = ByteArray(w * h)
		if (x < 0 || y < 0 || x >= w || y >= h) return out
		val s = argb[y * w + x]
		if ((s ushr 24) < 16) return out
		val r0 = (s ushr 16) and 0xFF; val g0 = (s ushr 8) and 0xFF; val b0 = s and 0xFF
		fun ok(i: Int): Boolean { val p = argb[i]; return (p ushr 24) >= 16 && abs(((p ushr 16) and 0xFF) - r0) <= tolerance && abs(((p ushr 8) and 0xFF) - g0) <= tolerance && abs((p and 0xFF) - b0) <= tolerance }
		val stack = IntArray(w * h); var top = 0
		stack[top++] = y * w + x; out[y * w + x] = 1
		while (top > 0) {
			val i = stack[--top]; val px = i % w; val py = i / w
			if (px > 0 && out[i - 1].toInt() == 0 && ok(i - 1)) { out[i - 1] = 1; stack[top++] = i - 1 }
			if (px < w - 1 && out[i + 1].toInt() == 0 && ok(i + 1)) { out[i + 1] = 1; stack[top++] = i + 1 }
			if (py > 0 && out[i - w].toInt() == 0 && ok(i - w)) { out[i - w] = 1; stack[top++] = i - w }
			if (py < h - 1 && out[i + w].toInt() == 0 && ok(i + w)) { out[i + w] = 1; stack[top++] = i + w }
		}
		return out
	}

	class Specks(val remove: ByteArray, val count: Int, val px: Int, val rings: List<Pt>)

	/**
	 * Stray islands (8-connected, alpha > 24) smaller than [size] px other than the biggest, and faint haze (alpha ≤ 40)
	 * away from the solid piece ([haze]) or next to an island - the web's `findSpecks`.
	 */
	fun findSpecks(piece: IntArray, w: Int, h: Int, size: Int, haze: Boolean): Specks {
		val n = w * h; val lab = IntArray(n); val areas = arrayListOf(0); val centres = arrayListOf<Pt?>(null)
		val stack = IntArray(n)
		var next = 0
		for (i in 0 until n) {
			if (lab[i] != 0 || (piece[i] ushr 24) <= 24) continue
			val id = ++next; var count = 0; var sx = 0.0; var sy = 0.0; var top = 0
			stack[top++] = i; lab[i] = id
			while (top > 0) {
				val j = stack[--top]; count++
				val x = j % w; val y = j / w; sx += x; sy += y
				for (dy in -1..1) for (dx in -1..1) {
					val xx = x + dx; val yy = y + dy
					if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
					val k = yy * w + xx
					if (lab[k] != 0 || (piece[k] ushr 24) <= 24) continue
					lab[k] = id; stack[top++] = k
				}
			}
			areas += count; centres += Pt(sx / count, sy / count)
		}
		var biggest = 0; for (id in 1 until areas.size) if (biggest == 0 || areas[id] > areas[biggest]) biggest = id
		val small = BooleanArray(areas.size) { it > 0 && it != biggest && areas[it] < size }
		val island = ByteArray(n); val solid = ByteArray(n)
		for (i in 0 until n) { if (small[lab[i]]) island[i] = 1 else if ((piece[i] ushr 24) > 128) solid[i] = 1 }
		fun near(m: ByteArray, r: Int): ByteArray {
			val s = IntArray((w + 1) * (h + 1))
			for (y in 0 until h) { var row = 0; for (x in 0 until w) { row += m[y * w + x]; s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + row } }
			val out = ByteArray(n)
			for (y in 0 until h) for (x in 0 until w) {
				val x0 = max(0, x - r); val y0 = max(0, y - r); val x1 = min(w, x + r + 1); val y1 = min(h, y + r + 1)
				if (s[y1 * (w + 1) + x1] - s[y0 * (w + 1) + x1] - s[y1 * (w + 1) + x0] + s[y0 * (w + 1) + x0] > 0) out[y * w + x] = 1
			}
			return out
		}
		val keep = near(solid, 3); val byIsland = near(island, 2)
		val remove = ByteArray(n); var px = 0
		for (i in 0 until n) {
			val al = piece[i] ushr 24
			if (island[i].toInt() != 0 || (al in 1..40 && keep[i].toInt() == 0 && (haze || byIsland[i].toInt() != 0))) { remove[i] = 1; px++ }
		}
		return Specks(remove, small.count { it }, px, centres.filterIndexed { id, _ -> small[id] }.filterNotNull())
	}
}
