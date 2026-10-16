package io.github.psd2live.ipad.studio

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** SAM 2.1 Tiny as Core ML: a 1024 × 1024 picture is read once; points then give three candidate masks at once. */
expect object SmartMaskModel {
	/** Reads the picture ([argb], 1024 × 1024, opaque). Blocking. */
	fun encode(argb: IntArray)
	/** Three 256 × 256 mask logit maps (> 0 = inside) and their scores, for [points] (x, y pairs in the 1024 picture) labelled 1 keep / 0 exclude. Blocking. */
	fun predict(points: FloatArray, labels: FloatArray): Pair<FloatArray, FloatArray>
	/** Loads the compiled models from [dir] instead of the app bundle (tests). */
	fun useModelsAt(dir: String)
	var info: String
}

/** Thrown when the model answers but every mask is empty. */
class EmptyMasks : IllegalStateException("The smart mask came back empty. Try a point a little further inside the part, or set a Region around it.")

/**
 * The smart mask (the web's `browser-segment.js` / `SegmentPanel.jsx`): tap points to keep or exclude, optionally a
 * region for small parts; SAM proposes three masks with its confidence. Nothing is uploaded. The picture is read once
 * per region, so more points answer at once.
 */
object StudioSmartMask {
	private const val SIDE = 1024; private const val LOW = 256
	private var key: String? = null
	var lastInfo = ""
	/** The model (a test puts a stand-in here). */
	var encodeFn: (IntArray) -> Unit = { SmartMaskModel.encode(it) }
	var predictFn: (FloatArray, FloatArray) -> Pair<FloatArray, FloatArray> = { p, l -> SmartMaskModel.predict(p, l) }

	class Candidate(val mask: IntArray, val score: Float, val pixels: Int)

	/** [region] is x, y, w, h on the canvas (null: the whole drawing); [points] are canvas x, y, label. */
	fun suggest(project: StudioProject, points: List<Triple<Double, Double, Int>>, region: IntArray?): List<Candidate> {
		val w = project.width; val h = project.height
		val (rx, ry, rw, rh) = (region ?: intArrayOf(0, 0, w, h)).let { listOf(it[0], it[1], max(1, it[2]), max(1, it[3])) }
		val k = "${project.source.length}:${project.source.hashCode()}:$rx,$ry,$rw,$rh"
		if (key != k) {
			val src = StudioImages.layer(project.source, w, h).full()
			val img = StudioAI.resample(src, w, h, rx, ry, rw, rh, SIDE, SIDE)
			for (i in img.indices) img[i] = StudioRender.blend(-1, img[i]) // onto white: the model wants an opaque picture
			encodeFn(img); key = k
		}
		val pts = FloatArray(points.size * 2); val labels = FloatArray(points.size)
		points.forEachIndexed { i, (x, y, l) -> pts[2 * i] = ((x - rx) / rw * SIDE).toFloat(); pts[2 * i + 1] = ((y - ry) / rh * SIDE).toFloat(); labels[i] = l.toFloat() }
		val (logits, scores) = predictFn(pts, labels)
		lastInfo = "logits >0: ${logits.count { it > 0 }} of ${logits.size}"
		return (0 until 3).map { c ->
			val out = IntArray(w * h); var count = 0
			val base = c * LOW * LOW
			// The logits sampled bilinearly at each canvas pixel of the region: smooth edges, not 4×-blocky ones.
			for (y in 0 until rh) {
				val v = (y + 0.5) * LOW / rh - 0.5; val y0 = floor(v).toInt().coerceIn(0, LOW - 1); val y1 = min(LOW - 1, y0 + 1); val fy = (v - y0).coerceIn(0.0, 1.0)
				for (x in 0 until rw) {
					val u = (x + 0.5) * LOW / rw - 0.5; val x0 = floor(u).toInt().coerceIn(0, LOW - 1); val x1 = min(LOW - 1, x0 + 1); val fx = (u - x0).coerceIn(0.0, 1.0)
					val a = logits[base + y0 * LOW + x0] * (1 - fx) + logits[base + y0 * LOW + x1] * fx
					val b = logits[base + y1 * LOW + x0] * (1 - fx) + logits[base + y1 * LOW + x1] * fx
					if (a * (1 - fy) + b * fy > 0) { val cx = rx + x; val cy = ry + y; if (cx < w && cy < h) { out[cy * w + cx] = MASK_ARGB; count++ } }
				}
			}
			Candidate(out, scores[c], count)
		}.sortedByDescending { it.score }.also { all -> if (all.all { it.pixels == 0 }) throw EmptyMasks() }
	}

	/** [mask] added to the part's current mask (union), as a PNG data URL. */
	fun union(project: StudioProject, current: String?, mask: IntArray): IntArray {
		if (current == null) return mask
		val out = StudioImages.layer(current, project.width, project.height).full()
		for (i in out.indices) if ((mask[i] ushr 24) != 0) out[i] = MASK_ARGB
		return out
	}

	@Suppress("unused") private fun r(v: Double) = v.roundToInt()
}
