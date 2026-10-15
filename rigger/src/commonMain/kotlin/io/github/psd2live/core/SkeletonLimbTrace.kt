package io.github.psd2live.core

import kotlin.math.hypot

/**
 * A limb's meshes as one graph: vertices in canvas pixels joined along triangle edges, the meshes of one limb joined
 * at their closest vertices, and points with no triangles joined to their nearest neighbours instead. Distances
 * over it are measured *through* the art, so an arm folded back on itself still reads as one continuous arm.
 */
class LimbGraph(parts: List<Pair<FloatArray, IntArray>>) {
	/** The meshes' own vertices, then the points sampled along their edges. */
	val points: FloatArray

	/** How many of [points] are the meshes' own vertices (the first ones). */
	val base: Int
	val count: Int
	private val adjacency: Array<IntArray>
	private val baseAdjacency: Array<IntArray>

	init {
		val own = concat(parts.map { it.first })
		base = own.size / 2
		val offsets = parts.runningFold(0) { acc, part -> acc + part.first.size / 2 }
		fun ownDistance(a: Int, b: Int) = hypot((own[a * 2] - own[b * 2]).toDouble(), (own[a * 2 + 1] - own[b * 2 + 1]).toDouble())
		val edges = Array(base) { HashSet<Int>() }
		fun link(a: Int, b: Int) { if (a != b) { edges[a] += b; edges[b] += a } }
		// Edges that bound a triangle can be sampled; the nearest-neighbour edges of a mesh without triangles cross
		// the art anywhere, so those are left as they are.
		val triangleEdges = HashSet<Long>()
		val triangles = ArrayList<IntArray>()
		parts.forEachIndexed { p, (vertices, indices) ->
			val b = offsets[p]
			val n = vertices.size / 2
			if (indices.size >= 3 && indices.all { it in 0 until n }) {
				for (i in indices.indices step 3) {
					triangles += intArrayOf(b + indices[i], b + indices[i + 1], b + indices[i + 2])
					for (k in 0..2) {
						val u = b + indices[i + k]; val v = b + indices[i + (k + 1) % 3]
						link(u, v)
						if (u != v) triangleEdges += minOf(u, v).toLong() * base + maxOf(u, v)
					}
				}
			} else {
				for (a in 0 until n) {
					(0 until n).filter { it != a }.sortedBy { ownDistance(b + a, b + it) }.take(6).forEach { link(b + a, b + it) }
				}
			}
		}
		for (p in parts.indices) for (q in p + 1 until parts.size) {
			var best = -1 to -1
			var bestDistance = Double.MAX_VALUE
			for (a in offsets[p] until offsets[p + 1]) for (b in offsets[q] until offsets[q + 1]) {
				val d = ownDistance(a, b)
				if (d < bestDistance) { bestDistance = d; best = a to b }
			}
			if (best.first >= 0) link(best.first, best.second)
		}
		baseAdjacency = Array(base) { edges[it].toIntArray() }

		// Sample along the triangle edges, so a cross-section of the art has points across its whole width however
		// the mesh happens to be cut: statistics over vertices alone say more about the triangulation than the limb.
		var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
		for (i in 0 until base) { minX = minOf(minX, own[i * 2]); maxX = maxOf(maxX, own[i * 2]); minY = minOf(minY, own[i * 2 + 1]); maxY = maxOf(maxY, own[i * 2 + 1]) }
		var edgeLength = 0.0
		for (key in triangleEdges) edgeLength += ownDistance((key / base).toInt(), (key % base).toInt())
		// A pixel or two for a limb; more for a big mesh, so that the graph stays a few tens of thousands of points.
		val step = maxOf((hypot(maxX - minX, maxY - minY) * 0.012f).coerceIn(2f, 12f), (edgeLength / MAX_SAMPLES).toFloat())
		val extra = ArrayList<Float>()
		val chain = ArrayList<IntArray>()
		val onEdge = HashMap<Long, IntArray>()
		for (key in triangleEdges.sorted()) {
			val u = (key / base).toInt(); val v = (key % base).toInt()
			val length = ownDistance(u, v)
			val k = Math.ceil(length / step).toInt() - 1
			if (k < 1) continue
			val ids = IntArray(k) { base + extra.size / 2 + it }
			for (j in 1..k) {
				val t = j.toFloat() / (k + 1)
				extra += own[u * 2] + (own[v * 2] - own[u * 2]) * t
				extra += own[u * 2 + 1] + (own[v * 2 + 1] - own[u * 2 + 1]) * t
			}
			chain += intArrayOf(u, v, *ids)
			onEdge[key] = ids
		}
		points = FloatArray(own.size + extra.size).also { own.copyInto(it); extra.toFloatArray().copyInto(it, own.size) }
		count = points.size / 2
		val all = Array(count) { if (it < base) HashSet(edges[it].toList()) else HashSet<Int>() }
		fun join(a: Int, b: Int) { all[a] += b; all[b] += a }
		for (c in chain) {
			all[c[0]] -= c[1]; all[c[1]] -= c[0]
			var previous = c[0]
			for (j in 2 until c.size) { join(previous, c[j]); previous = c[j] }
			join(previous, c[1])
		}
		// Straight lines across a triangle are paths too: join the points on its three edges to the nearest ones on the
		// others, so that distance through the art is nearly the straight distance and does not run along the mesh's edges.
		fun d(a: Int, b: Int) = hypot((points[a * 2] - points[b * 2]).toDouble(), (points[a * 2 + 1] - points[b * 2 + 1]).toDouble())
		for (t in triangles) {
			val sides = (0..2).map { k ->
				val u = t[k]; val v = t[(k + 1) % 3]
				intArrayOf(u, v) + (onEdge[minOf(u, v).toLong() * base + maxOf(u, v)] ?: IntArray(0))
			}
			for (e in 0..2) for (f in 0..2) if (e != f) for (p in sides[e]) {
				sides[f].filter { it != p }.sortedBy { d(p, it) }.take(3).forEach { join(p, it) }
			}
		}
		adjacency = Array(count) { all[it].toIntArray() }
	}

	fun x(i: Int): Float = points[i * 2]
	fun y(i: Int): Float = points[i * 2 + 1]
	fun neighbours(i: Int): IntArray = adjacency[i]

	/** The meshes' own links between their own vertices, without the sampled points between them. */
	fun baseNeighbours(i: Int): IntArray = baseAdjacency[i]

	/** The vertices nearest ([x], [y]): within a few percent of the art's extent of the closest one. */
	fun nearest(x: Float, y: Float): List<Int> {
		val straight = DoubleArray(count) { hypot((points[it * 2] - x).toDouble(), (points[it * 2 + 1] - y).toDouble()) }
		val nearest = straight.min()
		val limit = nearest + (straight.max() - nearest) * 0.03 + 1e-6
		return (0 until count).filter { straight[it] <= limit }
	}

	fun distance(a: Int, b: Int): Double = hypot((points[a * 2] - points[b * 2]).toDouble(), (points[a * 2 + 1] - points[b * 2 + 1]).toDouble())

	/** Shortest distances through the art from [seeds] (vertex to the distance it starts at); [Double.MAX_VALUE] where unreached. */
	fun geodesic(seeds: List<Pair<Int, Double>>): Geodesic {
		val dist = DoubleArray(count) { Double.MAX_VALUE }
		val pred = IntArray(count) { -1 }
		val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
		for ((v, d) in seeds) if (d < dist[v]) { dist[v] = d; queue += d to v }
		while (queue.isNotEmpty()) {
			val (cost, at) = queue.poll()!!
			if (cost > dist[at]) continue
			for (next in adjacency[at]) {
				val through = cost + distance(at, next)
				if (through < dist[next]) {
					dist[next] = through
					pred[next] = at
					queue += through to next
				}
			}
		}
		return Geodesic(dist, pred)
	}

	class Geodesic(val dist: DoubleArray, val pred: IntArray) {
		fun reached(v: Int): Boolean = dist[v] < Double.MAX_VALUE
	}

	/**
	 * The two ends of the limb: the root end, the one nearer ([hintX], [hintY]), and the far end. Found by walking
	 * from the vertex nearest the hint to the farthest vertex, then back from there to the farthest again, which
	 * is the limb's other extremity however the hint was placed.
	 */
	fun ends(hintX: Float, hintY: Float): Ends? {
		if (count < 3) return null
		val nearest = (0 until count).minBy { hypot(x(it) - hintX, y(it) - hintY) }
		val first = geodesic(listOf(nearest to 0.0))
		val a = (0 until count).filter(first::reached).maxByOrNull { first.dist[it] } ?: return null
		val second = geodesic(listOf(a to 0.0))
		val b = (0 until count).filter(second::reached).maxByOrNull { second.dist[it] } ?: return null
		if (second.dist[b] <= 1e-3) return null
		val aNearer = hypot(x(a) - hintX, y(a) - hintY) < hypot(x(b) - hintX, y(b) - hintY)
		val rootEnd = if (aNearer) a else b
		val farEnd = if (aNearer) b else a
		// The slice of vertices at the root end: the end's cap, whose centre the joint sits in.
		val fromFar = geodesic(listOf(farEnd to 0.0))
		val length = fromFar.dist[rootEnd]
		val slice = (0 until count).filter { fromFar.reached(it) && fromFar.dist[it] >= length * 0.94 }
		return Ends(rootEnd, farEnd, slice, length, fromFar)
	}

	/**
	 * For art about as wide as it is long, where the walk to the farthest vertex ends in a corner: the points along the
	 * whole end edge of the principal axis nearest ([hintX], [hintY]), so that the distance from them runs flat across the limb.
	 */
	fun axisSlice(hintX: Float, hintY: Float): List<Int> {
		var mx = 0.0; var my = 0.0
		for (i in 0 until count) { mx += x(i); my += y(i) }
		mx /= count; my /= count
		var sxx = 0.0; var sxy = 0.0; var syy = 0.0
		for (i in 0 until count) {
			val dx = x(i) - mx; val dy = y(i) - my
			sxx += dx * dx; sxy += dx * dy; syy += dy * dy
		}
		val angle = 0.5 * Math.atan2(2 * sxy, sxx - syy)
		val ux = Math.cos(angle); val uy = Math.sin(angle)
		val along = DoubleArray(count) { (x(it) - mx) * ux + (y(it) - my) * uy }
		val low = along.min(); val high = along.max()
		val hint = (hintX - mx) * ux + (hintY - my) * uy
		val end = if (kotlin.math.abs(hint - low) <= kotlin.math.abs(hint - high)) low else high
		val depth = (high - low) * 0.08
		return (0 until count).filter { kotlin.math.abs(along[it] - end) <= depth }
	}

	class Ends(val root: Int, val far: Int, val rootSlice: List<Int>, val length: Double, val fromFar: Geodesic)

	/**
	 * Where the joint at a limb's root goes: the centre of its end cap. The slice of art at the end has its centre
	 * moved in by half the limb's width there, because a round end turns about the middle of its circle, not about
	 * the point it reaches furthest. Null when the cap says nothing (too few vertices).
	 */
	fun capCentre(ends: Ends): Cap? {
		if (ends.rootSlice.size < 2) return null
		var cx = 0f; var cy = 0f
		for (i in ends.rootSlice) { cx += x(i); cy += y(i) }
		cx /= ends.rootSlice.size; cy /= ends.rootSlice.size
		// The limb's direction at the root: from the cap towards the vertices a little further in.
		val fromFar = ends.fromFar
		val inner = (0 until count).filter { fromFar.reached(it) && fromFar.dist[it] in (ends.length * 0.78)..(ends.length * 0.9) }
		if (inner.isEmpty()) return Cap(cx, cy, 0f, 0f)
		var ix = 0f; var iy = 0f
		for (i in inner) { ix += x(i); iy += y(i) }
		ix /= inner.size; iy /= inner.size
		val ux = ix - cx; val uy = iy - cy
		val unit = hypot(ux, uy).coerceAtLeast(1e-3f)
		val nx = -uy / unit; val ny = ux / unit
		var low = Float.MAX_VALUE; var high = -Float.MAX_VALUE; var outermost = Float.MAX_VALUE
		for (i in ends.rootSlice) {
			val dx = x(i) - cx; val dy = y(i) - cy
			val t = dx * nx + dy * ny
			low = minOf(low, t); high = maxOf(high, t)
			outermost = minOf(outermost, (dx * ux + dy * uy) / unit)
		}
		val width = high - low
		// A joint turns about the middle of its circle: half the limb's width in from the end it reaches furthest, and
		// the middle of the slice across the limb. Not the slice's mean: points sampled along the edges crowd at the
		// outline, and the strip that is furthest is lopsided wherever distance through a mesh runs further one way.
		val middle = (low + high) / 2
		val along = (outermost + width * 0.5f).coerceAtMost((ends.length * 0.25).toFloat())
		return Cap(cx + nx * middle + ux / unit * along, cy + ny * middle + uy / unit * along, ux / unit, uy / unit)
	}

	/** A limb's root joint and the direction (unit, canvas pixels) the limb leaves it in; (0, 0) when the cap gave none. */
	class Cap(val x: Float, val y: Float, val ux: Float, val uy: Float)

	private companion object {
		/** Roughly how many points the edges of the meshes are sampled into at most. */
		const val MAX_SAMPLES = 30000.0

		fun concat(arrays: List<FloatArray>): FloatArray {
			val out = FloatArray(arrays.sumOf { it.size })
			var offset = 0
			for (a in arrays) { a.copyInto(out, offset); offset += a.size }
			return out
		}
	}
}

/** A lobe that leaves the trunk at geodesic distance [reachAt] from the root, as its own line starting on the trunk. */
class LimbFork(val reachAt: Double, val line: SkeletonAutoBuilder.Medial)

/** What [LimbTrace.trace] found: the line the longest way through the art, and the lobes that branch off it. */
class LimbTree(val trunk: SkeletonAutoBuilder.Medial, val forks: List<LimbFork>, val trunkVertices: FloatArray)

object LimbTrace {
	/** A branch must reach this share of the limb past the fork to count as a lobe rather than a bump of the outline. */
	private const val MIN_LOBE = 0.1

	/** ...and be at least this many times as long as the art is wide where it splits off, or it is a bump, not a lobe. */
	private const val LOBE_ASPECT = 0.6

	/** At most this many lobes branch off one limb; the ones that reach furthest win. */
	private const val MAX_FORKS = 3

	/**
	 * The centreline tree of the art in [graph] from the root joint ([rootX], [rootY]), whose end cap is
	 * [rootSlice]. Distance is measured through the art from the cap; the vertices at each distance
	 * form a cross-section whose middle is a point of the line. With [forks] a branch of the art that reaches
	 * [minLobe] of the limb past where it splits from the trunk becomes a lobe of its own: the tips of
	 * the art are the peaks of that distance, a lobe is a peak that persists that long before it meets a higher one.
	 */
	fun trace(graph: LimbGraph, rootX: Float, rootY: Float, rootSlice: List<Int>, forks: Boolean, minLobe: Double = MIN_LOBE, centeredTip: Boolean = false, bins: Int = 16): LimbTree? {
		val count = graph.count
		if (count < 3) return null
		val geo = graph.geodesic(rootSlice.map { it to hypot((graph.x(it) - rootX).toDouble(), (graph.y(it) - rootY).toDouble()) })
		val dist = geo.dist
		val reached = (0 until count).filter(geo::reached)
		if (reached.size < 3) return null
		val sorted = reached.map { dist[it] }.sorted()
		val far = sorted[(sorted.size * 0.97).toInt().coerceAtMost(sorted.size - 1)]
		if (far <= 1e-6) return null

		// Lobes: peaks of the distance that persist, found as the components of the art further than a level are
		// merged going down; a component that dies into a higher one at its saddle was a branch of that length.
		// Tips are the meshes' own vertices: points sampled along an edge between two reached vertices only make a tent of it.
		val ownReached = reached.filter { it < graph.base }
		if (ownReached.size < 3) return null
		// The trunk runs to the tip of the heaviest branch, not the farthest one: a charm swinging from a sleeve reaches
		// further than the hand but is not what the arm ends in. Lighter branches that persist are the lobes.
		val tree = mergeTree(graph, dist, ownReached)
		val trunkTip = tree.heavyTip
		val lobes = if (!forks) emptyList() else tree.peaks
			.filter { it.persistence >= minLobe * far && it.persistence >= 12.0 &&
				it.persistence >= LOBE_ASPECT * widthAround(graph, dist, reached, dist[it.saddle], far * 0.03) }
			.sortedByDescending { it.persistence }.take(MAX_FORKS)

		// Each vertex goes to the branch whose path it lies nearest, a lobe taking only what is past its fork.
		val label = IntArray(count) { -1 }
		fun mark(tip: Int, id: Int, past: Double) {
			var v = tip
			while (v >= 0 && label[v] < 0 && dist[v] >= past) { label[v] = id; v = geo.pred[v] }
		}
		mark(trunkTip, 0, -1.0)
		lobes.forEachIndexed { i, lobe -> mark(lobe.tip, i + 1, dist[lobe.saddle]) }
		val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
		val owned = DoubleArray(count) { if (label[it] >= 0) 0.0 else Double.MAX_VALUE }
		for (v in 0 until count) if (label[v] >= 0) queue += 0.0 to v
		while (queue.isNotEmpty()) {
			val (cost, at) = queue.poll()!!
			if (cost > owned[at]) continue
			for (next in graph.neighbours(at)) {
				if (!geo.reached(next)) continue
				val id = label[at]
				if (id > 0 && dist[next] < dist[lobes[id - 1].saddle]) continue
				val through = cost + graph.distance(at, next)
				if (through < owned[next] && (label[next] < 0 || label[next] == id)) { owned[next] = through; label[next] = id; queue += through to next }
			}
		}

		// The trunk ends where the heavy branch does, whatever hangs on further.
		val trunkMembers = reached.filter { label[it] == 0 && dist[it] <= dist[trunkTip] + 1e-6 }
		if (trunkMembers.size < 3) return null
		val trunkFar = trunkMembers.map { dist[it] }.sorted().let { it[(it.size * 0.97).toInt().coerceAtMost(it.size - 1)] }
		if (trunkFar <= 1e-6) return null
		val trunk = centreline(graph, dist, trunkMembers, 0.0, trunkFar, bins, rootX to rootY, centeredTip, reachFloor = 0.0) ?: return null
		val forked = lobes.mapIndexedNotNull { i, lobe ->
			val from = dist[lobe.saddle]
			val members = reached.filter { label[it] == i + 1 }
			if (members.size < 3) return@mapIndexedNotNull null
			val lobeFar = members.map { dist[it] }.sorted().let { it[(it.size * 0.97).toInt().coerceAtMost(it.size - 1)] }
			if (lobeFar - from < minLobe * far * 0.5) return@mapIndexedNotNull null
			val start = trunk.pointAtReach(from)
			val line = centreline(graph, dist, members.filter { dist[it] <= lobeFar }, from, lobeFar, (bins * (lobeFar - from) / far).toInt().coerceIn(4, bins),
				start, centeredTip, reachFloor = from) ?: return@mapIndexedNotNull null
			LimbFork(from, line)
		}
		val trunkPoints = FloatArray(trunkMembers.size * 2) { if (it % 2 == 0) graph.x(trunkMembers[it / 2]) else graph.y(trunkMembers[it / 2]) }
		return LimbTree(trunk, forked, trunkPoints)
	}

	/** How wide the art is across the level [at] of the distance: the widest span between vertices within [band] of it. */
	private fun widthAround(graph: LimbGraph, dist: DoubleArray, reached: List<Int>, at: Double, band: Double): Double {
		val ring = reached.filter { dist[it] in (at - band)..(at + band) }
		var widest = 0.0
		for (a in ring.indices) for (b in a + 1 until ring.size) widest = maxOf(widest, graph.distance(ring[a], ring[b]))
		return widest
	}

	/** A lighter branch of the art, with where it joins the heavier one and how far past that it reaches. */
	private class Peak(val tip: Int, val saddle: Int, val persistence: Double)

	private class MergeTree(val peaks: List<Peak>, val heavyTip: Int)

	/**
	 * The branches of the art as its components further than a level are merged going down: at each saddle the lighter of
	 * the two (by vertices) is a [Peak], reaching [Peak.persistence] past it, and the tip of the heavier one carries on -
	 * the heavy tip of the whole is the tip of the branch that is heaviest at every saddle on the way down.
	 */
	private fun mergeTree(graph: LimbGraph, dist: DoubleArray, reached: List<Int>): MergeTree {
		val order = reached.sortedWith(compareByDescending<Int> { dist[it] }.thenBy { it })
		val parent = IntArray(graph.count) { it }
		// Per component root: its highest vertex, its heavy leaf, its size.
		val top = IntArray(graph.count) { it }
		val heavy = IntArray(graph.count) { it }
		val size = IntArray(graph.count) { 1 }
		val added = BooleanArray(graph.count)
		fun find(v: Int): Int { var r = v; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
		val found = mutableListOf<Peak>()
		for (v in order) {
			added[v] = true
			for (u in graph.baseNeighbours(v)) {
				if (!added[u]) continue
				val a = find(u); val b = find(v)
				if (a == b) continue
				val aHeavier = size[a] > size[b] || (size[a] == size[b] && dist[top[a]] >= dist[top[b]])
				val (win, lose) = if (aHeavier) a to b else b to a
				found += Peak(top[lose], v, dist[top[lose]] - dist[v])
				parent[lose] = win
				if (dist[top[lose]] > dist[top[win]]) top[win] = top[lose]
				size[win] += size[lose]
			}
		}
		val whole = order.map(::find).distinct().maxBy { size[it] }
		return MergeTree(found, heavy[whole])
	}

	/**
	 * The line through [members] between distances [from] and [to]: per slice of distance, the middle of the
	 * cross-section (the mean along the line, the middle of the extremes across it, so a mesh denser on one side
	 * does not pull the line toward that side), and how wide that cross-section is. It starts at [start] and
	 * ends where the art ends.
	 */
	private fun centreline(
		graph: LimbGraph, dist: DoubleArray, members: List<Int>, from: Double, to: Double, bins: Int,
		start: Pair<Float, Float>, centeredTip: Boolean, reachFloor: Double,
	): SkeletonAutoBuilder.Medial? {
		if (members.size < 3 || to - from <= 1e-6) return null
		val bucket = Array(bins) { ArrayList<Int>() }
		for (v in members) bucket[(((dist[v] - from) / (to - from)) * bins).toInt().coerceIn(0, bins - 1)] += v
		val means = ArrayList<DoubleArray>()
		val used = ArrayList<List<Int>>()
		for (b in bucket.indices) if (bucket[b].isNotEmpty()) {
			var sx = 0.0; var sy = 0.0; var sd = 0.0
			for (v in bucket[b]) { sx += graph.x(v); sy += graph.y(v); sd += dist[v] }
			val n = bucket[b].size
			means += doubleArrayOf(sx / n, sy / n, sd / n)
			used += bucket[b]
		}
		if (means.isEmpty()) return null
		val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val ds = ArrayList<Double>(); val ws = ArrayList<Double>()
		xs += start.first; ys += start.second; ds += reachFloor; ws += Double.NaN
		for (i in means.indices) {
			// The direction of the line here is read over a few slices either way, so the wobble of single means is not one.
			val prev = means.getOrNull(i - 2) ?: means.getOrNull(i - 1) ?: doubleArrayOf(start.first.toDouble(), start.second.toDouble(), 0.0)
			val next = means.getOrNull(i + 2) ?: means.getOrNull(i + 1) ?: means[i]
			var ux = next[0] - prev[0]; var uy = next[1] - prev[1]
			val ul = hypot(ux, uy)
			var cx = means[i][0]; var cy = means[i][1]
			var width = Double.NaN
			if (ul > 1e-6 && used[i].size >= 4) {
				ux /= ul; uy /= ul
				// Across a turn the cross-section is an arc, not a segment: its extremes say little about the middle.
				val inx = means[i][0] - prev[0]; val iny = means[i][1] - prev[1]
				val outx = next[0] - means[i][0]; val outy = next[1] - means[i][1]
				val inl = hypot(inx, iny); val outl = hypot(outx, outy)
				val straight = inl < 1e-6 || outl < 1e-6 || (inx * outx + iny * outy) / (inl * outl) > 0.8
				val across = used[i].map { (graph.x(it) - means[i][0]) * -uy + (graph.y(it) - means[i][1]) * ux }.sorted()
				val low = across[(across.size * 0.03).toInt()]
				val high = across[((across.size - 1) * 0.97).toInt()]
				if (straight) { val mid = (low + high) / 2; cx += -uy * mid; cy += ux * mid; width = high - low }
			}
			xs += cx.toFloat(); ys += cy.toFloat(); ds += means[i][2]; ws += width
		}
		// The end of the art: the farthest vertices, or - for a tail - on down the line as far as the art goes.
		val tipCount = (members.size * 0.03).toInt().coerceAtLeast(1)
		val farthest = members.sortedByDescending { dist[it] }.take(tipCount)
		var tx = 0f; var ty = 0f
		for (v in farthest) { tx += graph.x(v); ty += graph.y(v) }
		tx /= farthest.size; ty /= farthest.size
		if (centeredTip && xs.size >= 3) {
			val ax = xs[xs.size - 2]; val ay = ys[ys.size - 2]
			val bx = xs.last(); val by = ys.last()
			val length = hypot(bx - ax, by - ay).coerceAtLeast(1e-3f)
			val dx = (bx - ax) / length; val dy = (by - ay) / length
			// To the end of the art: the mean of the farthest vertices, so that a corner of the end does not pull it past the edge.
			val reach = farthest.map { (graph.x(it) - bx) * dx + (graph.y(it) - by) * dy }.average().toFloat()
			if (reach > 0.5f) { tx = bx + dx * reach; ty = by + dy * reach } else { tx = bx; ty = by }
		}
		xs += tx; ys += ty; ds += to; ws += Double.NaN
		// Drop points that sit on the one before.
		val points = ArrayList<Pair<Float, Float>>(); val reach = ArrayList<Double>(); val widths = ArrayList<Double>()
		for (i in xs.indices) {
			if (points.isNotEmpty() && hypot(xs[i] - points.last().first, ys[i] - points.last().second) <= 0.5f) {
				if (i == xs.size - 1) { points[points.size - 1] = xs[i] to ys[i]; reach[reach.size - 1] = ds[i] }
				continue
			}
			points += xs[i] to ys[i]; reach += ds[i]; widths += ws[i]
		}
		if (points.size < 2) return null
		return SkeletonAutoBuilder.Medial(points, reach, smoothWidths(widths))
	}

	/** Widths with the gaps filled from their neighbours and the slice-to-slice wobble taken out. */
	private fun smoothWidths(raw: List<Double>): List<Double> {
		val known = raw.indices.filter { !raw[it].isNaN() }
		if (known.isEmpty()) return raw.map { 0.0 }
		val filled = raw.indices.map { i ->
			if (!raw[i].isNaN()) raw[i] else {
				val before = known.lastOrNull { it < i }; val after = known.firstOrNull { it > i }
				when {
					before != null && after != null -> raw[before] + (raw[after] - raw[before]) * (i - before) / (after - before)
					before != null -> raw[before]
					else -> raw[after!!]
				}
			}
		}
		return filled.indices.map { i -> (filled[(i - 1).coerceAtLeast(0)] + 2 * filled[i] + filled[(i + 1).coerceAtMost(filled.size - 1)]) / 4 }
	}
}
