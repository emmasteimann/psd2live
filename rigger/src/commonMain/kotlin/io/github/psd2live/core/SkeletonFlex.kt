package io.github.psd2live.core

import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Flexible bone chains: a tail, a fin, a finger that bends as one smooth curve rather than a row of rigid
 * segments turning at corners.
 *
 * The bones of a chain stay ordinary bones - each keeps its rotation deformer, its parameter, its physics and
 * its poses - and only the skin changes. A chain is a path of [SkeletonBone.flex] bones from a root (whose parent
 * is not flexible) down to a tip. Its bones are the control polygon of its centerline, as a path deformer's handles
 * are ([Curve]): a quadratic B-spline from the root's head through the middle of every bone to the tip, turning
 * smoothly between the middles about each joint, so the art bends along the whole chain and still reaches the tip
 * wherever the bones put it. Where the chain forks (a bone with more than one flexible child) the stalk runs
 * straight on to the fork and each lobe turns away from there, so turning one lobe of a fish tail leaves the stalk
 * and the other lobe alone; near the fork a lobe's art eases out of the stalk's frame.
 *
 * A vertex binds to the chain by the mesh, not by distance: it takes the bone of the nearest centerline sample
 * inside the art reached along mesh edges (two coils of a curled tail lying side by side never share vertices), its
 * closest point on the curve along that bone and the halves of the bones beside it, and its offset across the curve
 * there. It then turns with the curve's frame wherever the curve goes, however far from the centerline it lies, the
 * way a bone carries a rigid part.
 *
 * Every bone may also carry a [SkeletonBone.drawnBend]: how much more the drawing bends at its head than the bone's
 * rest geometry. The binding reads the art against the chain bent that way ([bound]), and the rest pose is the bone
 * geometry itself, so a chain [straighten]ed rests straight with its art unrolled along it, and turning each bone by
 * its drawn bend brings the drawing back.
 */
object SkeletonFlex {
	/** Sample spacing along each bone for the seeds of the binding, in pixels. */
	private const val SEED_SPACING = 3.0

	/** Spacing of the drawn curve's samples a vertex's closest point is looked for among, in pixels. */
	private const val PROJECTION_STEP = 0.5

	/** Whether any bone of [spec] is flexible. */
	fun any(spec: SkeletonSpec): Boolean = spec.bones.any { it.flex }

	/** Whether [bone] continues a flexible chain at its head: it and its parent are both flexible. */
	fun joined(spec: SkeletonSpec, bone: SkeletonBone): Boolean =
		bone.flex && bone.parentId?.let(spec::bone)?.flex == true

	/** The heading of [bone] from head to tail in the rig's convention, degrees. */
	private fun heading(x0: Double, y0: Double, x1: Double, y1: Double): Double = SkeletonIk.heading(x1 - x0, y1 - y0)

	// -------------------------------------------------------------------------------------------------------------
	// Drawn geometry and straightening

	/**
	 * [spec] with every bone where the art was drawn: each bone's subtree turned about its head by its
	 * [SkeletonBone.drawnBend], parents first. Identical to [spec] when no bone carries a drawn bend.
	 */
	fun bound(spec: SkeletonSpec): SkeletonSpec {
		if (spec.bones.none { it.drawnBend != 0f }) return spec
		val heads = HashMap<String, DoubleArray>()
		val tails = HashMap<String, DoubleArray>()
		for (b in spec.bones) {
			heads[b.id] = doubleArrayOf(b.headX.toDouble(), b.headY.toDouble())
			tails[b.id] = doubleArrayOf(b.tailX.toDouble(), b.tailY.toDouble())
		}
		for (bone in spec.topological()) {
			if (bone.drawnBend == 0f) continue
			val pivot = heads.getValue(bone.id).copyOf()
			for (id in spec.descendants(bone.id) + bone.id) {
				val h = heads.getValue(id)
				val t = tails.getValue(id)
				if (id != bone.id) heads[id] = SkeletonIk.rotate(h[0], h[1], pivot[0], pivot[1], bone.drawnBend.toDouble())
				tails[id] = SkeletonIk.rotate(t[0], t[1], pivot[0], pivot[1], bone.drawnBend.toDouble())
			}
		}
		return spec.copy(bones = spec.bones.map { b ->
			val h = heads.getValue(b.id)
			val t = tails.getValue(b.id)
			b.copy(headX = h[0].toFloat(), headY = h[1].toFloat(), tailX = t[0].toFloat(), tailY = t[1].toFloat())
		})
	}

	/**
	 * The chain through [id] laid along the middle of its art, [art] each mesh's canvas vertices and triangles as drawn.
	 *
	 * The chain carries its art by where each vertex lies off the curve through its joints ([curve]), and a straightened
	 * chain keeps those offsets about its line: a curve that strays off the middle of the art, as bones drawn with their
	 * joints on the art always do round a turn (the curve cuts inside them), would leave the art straightened in waves.
	 * So the stalk's joints (from the root's head, which stays, to the fork or the tip) move to fit the curve through
	 * them, by least squares, to the art's middle - halfway across its outline at each arc length - with a light pull
	 * back to where they were drawn, which holds a joint the art does not reach. Lobes past a fork move with it.
	 * A chain already straightened is left as it is: its art no longer lies as drawn.
	 */
	fun fitted(spec: SkeletonSpec, id: String, art: List<Pair<FloatArray, IntArray>>): SkeletonSpec {
		val start = spec.bone(id)?.takeIf { it.flex } ?: return spec
		val root = generateSequence(start) { b -> b.parentId?.let(spec::bone)?.takeIf { it.flex && b.flex } }.last()
		val chain = listOf(root.id) + spec.descendants(root.id).filter { d -> spec.bone(d)!!.flex && inChain(spec, d, root.id) }
		if (chain.size < 2 || art.isEmpty() || chain.any { spec.bone(it)!!.drawnBend != 0f }) return spec
		// The stalk: from the root on through single flexible children, to the fork or the tip.
		val stalk = mutableListOf(root.id)
		while (true) {
			val next = spec.children(stalk.last()).filter { it.flex && it.id in chain }
			if (next.size != 1) break
			stalk += next.single().id
		}
		if (stalk.size < 2) return spec
		val index = chain.withIndex().associate { it.value to it.index }
		val bones = chain.map { spec.bone(it)!! }
		val skin = bones.map { b -> SkinBone(b.parentId?.let { index[it] } ?: -1, b.headX.toDouble(), b.headY.toDouble(), b.tailX.toDouble(), b.tailY.toDouble(), 0.0) }
		val lengths = DoubleArray(stalk.size) { skin[index.getValue(stalk[it])].length.coerceAtLeast(1e-3) }
		val total = lengths.sum()
		val forked = spec.children(stalk.last()).count { it.flex && it.id in chain } > 1
		// Short of a fork by a quarter of the last bone, where the lobes spread and the art is no band.
		val reach = if (forked) total - lengths.last() * 0.25 else total

		// The art's middle at each arc length along the stalk.
		val bin = 6.0
		val bins = ceil(reach / bin).toInt().coerceAtLeast(1)
		val low = DoubleArray(bins) { Double.MAX_VALUE }; val high = DoubleArray(bins) { -Double.MAX_VALUE }
		val stalkPath = IntArray(stalk.size) { index.getValue(stalk[it]) }
		for ((canvas, triangles) in art) {
			val (_, binding) = bind(canvas, triangles, skin, BooleanArray(skin.size) { true }, SkeletonWeights.skin(canvas, skin, triangles)) ?: continue
			for (v in 0 until canvas.size / 2) {
				if (!binding.bound(v) || abs(binding.a[v]) > 1.0) continue
				// Arc lengths short of the stalk's end are on the stalk, whichever lobe the vertex follows on.
				val s = binding.s[v]
				if (s < 0.0 || s >= reach) continue
				val k = (s / bin).toInt().coerceIn(0, bins - 1)
				low[k] = minOf(low[k], binding.b[v]); high[k] = maxOf(high[k], binding.b[v])
			}
		}
		val drawnCurve = curve(skin, Chains(BooleanArray(skin.size) { true }, IntArray(skin.size) { skin[it].parent }), stalkPath, lengths)
		val targets = ArrayList<DoubleArray>()
		val widths = DoubleArray(bins) { (high[it] - low[it]).coerceAtLeast(0.0) }
		for (k in 0 until bins) {
			if (widths[k] < 1.0) continue
			// Where the art ends, cut across or aslant, a slice holds only part of its width and its middle lies off the
			// art's; a tail that narrows does so over many slices.
			if (widths[k] < 0.6 * (maxOf(0, k - 3)..minOf(bins - 1, k + 3)).maxOf { widths[it] }) continue
			val p = DoubleArray(2)
			drawnCurve.place((k + 0.5) * bin, 0.0, (low[k] + high[k]) * 0.5, p)
			targets += p
		}
		if (targets.size < stalk.size + 1) return spec

		// The joints, P0 (fixed) to Pm.
		val m = stalk.size
		val px = DoubleArray(m + 1); val py = DoubleArray(m + 1)
		px[0] = skin[stalkPath[0]].headX; py[0] = skin[stalkPath[0]].headY
		for (k in 0 until m) { px[k + 1] = skin[stalkPath[k]].tailX; py[k + 1] = skin[stalkPath[k]].tailY }
		val drawnX = px.copyOf(); val drawnY = py.copyOf()
		// The curve as [Curve] lays it, its pieces in the joints' coefficients, for the joints as they stand: a corner's
		// ends lie a fixed share of the way along its bones and its weight is held, so each piece is linear in them.
		fun pieces(): List<(Double) -> DoubleArray> {
			val out = ArrayList<(Double) -> DoubleArray>()
			fun node(k: Int, toward: Int, share: Double) = DoubleArray(m + 1).also { it[k] += 1 - share; it[toward] += share }
			var at = DoubleArray(m + 1).also { it[0] = 1.0 }
			fun line(to: DoubleArray) { val from = at; out += { u -> DoubleArray(m + 1) { from[it] * (1 - u) + to[it] * u } }; at = to }
			for (k in 1 until m) {
				val before = hypot(px[k] - px[k - 1], py[k] - py[k - 1]).coerceAtLeast(1e-9)
				val after = hypot(px[k + 1] - px[k], py[k + 1] - py[k]).coerceAtLeast(1e-9)
				val h = cornerReach(before, after)
				val a = node(k, k - 1, h / before); val b = node(k, k + 1, h / after)
				line(a)
				val w = cornerWeight(px[k] + (px[k - 1] - px[k]) * h / before, py[k] + (py[k - 1] - py[k]) * h / before, px[k], py[k],
					px[k] + (px[k + 1] - px[k]) * h / after, py[k] + (py[k + 1] - py[k]) * h / after)
				out += { u ->
					val ca = (1 - u) * (1 - u); val cp = 2 * u * (1 - u) * w; val cb = u * u
					val d = ca + cp + cb
					DoubleArray(m + 1) { (ca * a[it] + cb * b[it]) / d + if (it == k) cp / d else 0.0 }
				}
				at = b
			}
			line(DoubleArray(m + 1).also { it[m] = 1.0 })
			return out
		}
		val pull = 0.05 * targets.size / m
		repeat(6) {
			val ata = Array(m) { DoubleArray(m) }
			val bx = DoubleArray(m); val by = DoubleArray(m)
			val curvePieces = pieces()
			for (t in targets) {
				// The nearest point of the curve through the joints as they stand.
				var best = Double.MAX_VALUE; var bestW = DoubleArray(0)
				for (piece in curvePieces) for (step in 0..48) {
					val w = piece(step / 48.0)
					var x = 0.0; var y = 0.0
					for (j in 0..m) { x += w[j] * px[j]; y += w[j] * py[j] }
					val d = (x - t[0]) * (x - t[0]) + (y - t[1]) * (y - t[1])
					if (d < best) { best = d; bestW = w }
				}
				for (i in 1..m) {
					for (j in 1..m) ata[i - 1][j - 1] += bestW[i] * bestW[j]
					bx[i - 1] += bestW[i] * (t[0] - bestW[0] * px[0]); by[i - 1] += bestW[i] * (t[1] - bestW[0] * py[0])
				}
			}
			for (i in 1..m) { ata[i - 1][i - 1] += pull; bx[i - 1] += pull * drawnX[i]; by[i - 1] += pull * drawnY[i] }
			val sx = solve(ata.map { it.copyOf() }.toTypedArray(), bx) ?: return spec
			val sy = solve(ata, by) ?: return spec
			for (i in 1..m) { px[i] = sx[i - 1]; py[i] = sy[i - 1] }
		}

		var next = spec
		val fork = stalk.last()
		val dx = (px[m] - drawnX[m]).toFloat(); val dy = (py[m] - drawnY[m]).toFloat()
		val lobes = spec.children(fork).filter { it.flex && it.id in chain }.mapTo(HashSet()) { it.id }
		if (lobes.isNotEmpty()) next = next.withBonesTransformed(lobes, dx, dy, includeDescendants = true)
		for (k in 0 until m) next = next.withJointMoved(stalk[k], BoneEnd.TAIL, px[k + 1].toFloat(), py[k + 1].toFloat())
		return next
	}

	/**
	 * Straightens the chain through [id] as [straighten] does, its joints first laid along the middle of the art
	 * [puppet] draws for the chain's meshes at rest ([fitted]).
	 */
	fun straightenOnArt(spec: SkeletonSpec, id: String, poseName: String?, puppet: PuppetModel): SkeletonSpec {
		val start = spec.bone(id)?.takeIf { it.flex } ?: return spec
		val root = generateSequence(start) { b -> b.parentId?.let(spec::bone)?.takeIf { it.flex && b.flex } }.last()
		val chain = listOf(root.id) + spec.descendants(root.id).filter { d -> spec.bone(d)!!.flex && inChain(spec, d, root.id) }
		val meshes = chain.flatMap { spec.bone(it)!!.drawableIds }.toSet()
		val canvas = SkeletonAutoBuilder.canvasVertices(puppet)
		val art = puppet.drawables.filter { it.id.raw in meshes }.mapNotNull { d ->
			val mesh = d.mesh ?: return@mapNotNull null
			canvas[d.id.raw]?.let { it to mesh.indices }
		}
		return straighten(fitted(spec, id, art), id, poseName)
	}

	/** [a] x = [b] by Gaussian elimination with partial pivoting; null when singular. [a] is overwritten. */
	private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
		val n = b.size
		val x = b.copyOf()
		for (c in 0 until n) {
			val p = (c until n).maxBy { abs(a[it][c]) }
			if (abs(a[p][c]) < 1e-12) return null
			if (p != c) { val r = a[p]; a[p] = a[c]; a[c] = r; val t = x[p]; x[p] = x[c]; x[c] = t }
			for (r in c + 1 until n) {
				val f = a[r][c] / a[c][c]
				for (k in c until n) a[r][k] -= f * a[c][k]
				x[r] -= f * x[c]
			}
		}
		for (r in n - 1 downTo 0) {
			var sum = x[r]
			for (k in r + 1 until n) sum -= a[r][k] * x[k]
			x[r] = sum / a[r][r]
		}
		return x
	}

	/**
	 * Straightens the flexible chain through [id]: from its root down, every joint of the chain is turned out so each
	 * bone continues its parent's heading, and the angle taken out is added to the bone's drawn bend. The art stays
	 * where it was drawn ([bound] gives back the same geometry) and now rests along a straight line; a fork stays
	 * spread, so the lobes of a fish tail straighten each on its own. The drawn pose is saved as "[poseName]" and the
	 * joints' limits widen to reach it.
	 */
	fun straighten(spec: SkeletonSpec, id: String, poseName: String? = null): SkeletonSpec {
		val start = spec.bone(id)?.takeIf { it.flex } ?: return spec
		val root = generateSequence(start) { b -> b.parentId?.let(spec::bone)?.takeIf { it.flex && b.flex } }.last()
		var next = spec
		val chain = listOf(root.id) + spec.descendants(root.id).filter { d -> spec.bone(d)!!.flex && inChain(spec, d, root.id) }
		for (boneId in chain) {
			val bone = next.bone(boneId)!!
			val parent = bone.parentId?.let(next::bone) ?: continue
			if (!bone.flex || !parent.flex || boneId == root.id) continue
			// A fork keeps its spread: only the joints along one branch straighten.
			if (next.children(parent.id).count { it.flex } > 1) continue
			val bend = SkeletonIk.wrap(heading(bone.headX.toDouble(), bone.headY.toDouble(), bone.tailX.toDouble(), bone.tailY.toDouble()) -
				heading(parent.headX.toDouble(), parent.headY.toDouble(), parent.tailX.toDouble(), parent.tailY.toDouble()))
			if (abs(bend) < 1e-4) continue
			next = turned(next, boneId, -bend).let { turnedSpec ->
				val b = turnedSpec.bone(boneId)!!
				turnedSpec.withBone(b.copy(drawnBend = (b.drawnBend + bend).toFloat()).reaching())
			}
		}
		if (next == spec) return spec
		val name = poseName ?: return next
		return next.withSavedPose(name, drawnPose(next, chain))
	}

	/** Bends the chain through [id] back to its drawing: every drawn bend of the chain put back into its geometry. */
	fun unstraighten(spec: SkeletonSpec, id: String): SkeletonSpec {
		val start = spec.bone(id)?.takeIf { it.flex } ?: return spec
		val root = generateSequence(start) { b -> b.parentId?.let(spec::bone)?.takeIf { it.flex && b.flex } }.last()
		var next = spec
		val chain = listOf(root.id) + spec.descendants(root.id).filter { d -> spec.bone(d)!!.flex && inChain(spec, d, root.id) }
		for (boneId in chain) {
			val bone = next.bone(boneId)!!
			if (bone.drawnBend == 0f) continue
			next = turned(next, boneId, bone.drawnBend.toDouble()).let { t -> t.withBone(t.bone(boneId)!!.copy(drawnBend = 0f)) }
		}
		return next
	}

	/** The bone parameters that pose the chain [chain] of [spec] as it was drawn. */
	fun drawnPose(spec: SkeletonSpec, chain: Collection<String>): Map<String, Float> =
		chain.mapNotNull(spec::bone).filter { it.drawnBend != 0f }.associate { it.parameterId to it.drawnBend * it.direction }

	/** Whether [id] hangs from [root] through flexible bones only. */
	private fun inChain(spec: SkeletonSpec, id: String, root: String): Boolean {
		var bone = spec.bone(id) ?: return false
		while (bone.id != root) {
			if (!bone.flex) return false
			bone = bone.parentId?.let(spec::bone) ?: return false
		}
		return true
	}

	/** [spec] with bone [id] and everything below it turned about its head by [degrees]. */
	private fun turned(spec: SkeletonSpec, id: String, degrees: Double): SkeletonSpec {
		val pivot = spec.bone(id) ?: return spec
		val px = pivot.headX.toDouble()
		val py = pivot.headY.toDouble()
		val moving = spec.descendants(id) + id
		return spec.copy(bones = spec.bones.map { b ->
			if (b.id !in moving) b else {
				val h = if (b.id == id) doubleArrayOf(b.headX.toDouble(), b.headY.toDouble()) else SkeletonIk.rotate(b.headX.toDouble(), b.headY.toDouble(), px, py, degrees)
				val t = SkeletonIk.rotate(b.tailX.toDouble(), b.tailY.toDouble(), px, py, degrees)
				b.copy(headX = h[0].toFloat(), headY = h[1].toFloat(), tailX = t[0].toFloat(), tailY = t[1].toFloat())
			}
		}, ikTargets = spec.ikTargets)
	}

	/** This bone with its limits widened so its drawn pose lies inside them, with some room past it. */
	private fun SkeletonBone.reaching(): SkeletonBone {
		val value = drawnBend * direction
		return copy(minAngle = minOf(minAngle, value - 15f).coerceAtLeast(-180f), maxAngle = maxOf(maxAngle, value + 15f).coerceAtMost(180f))
	}

	// -------------------------------------------------------------------------------------------------------------
	// The chains of a limb tree

	/**
	 * The flexible chains of one limb tree as the skinning indexes it: [parent] is each bone's parent within the tree
	 * (-1 at the tree's root), and a path runs from a chain's root through flexible bones to one bone and on through
	 * single flexible children to the tip.
	 */
	class Chains(val flex: BooleanArray, val parent: IntArray) {
		val count: Int get() = flex.size

		/** The flexible parent of each bone, -1 where it starts a chain or is not flexible. */
		val flexParent = IntArray(count) { i -> parent[i].takeIf { p -> p >= 0 && flex[i] && flex[p] } ?: -1 }
		val flexChildren: List<List<Int>> = List(count) { i -> (0 until count).filter { flexParent[it] == i } }

		/** The root of the chain bone [i] is on. */
		fun root(i: Int): Int = generateSequence(i) { flexParent[it].takeIf { p -> p >= 0 } }.last()

		/** Whether the joint at bone [i]'s head bends only past it: its parent forks. */
		fun forks(i: Int): Boolean = flexParent[i] >= 0 && flexChildren[flexParent[i]].size > 1

		/** The path a vertex on bone [i] follows: the chain from its root to [i], then on while it does not fork. */
		fun pathOf(i: Int): IntArray {
			val up = generateSequence(i) { flexParent[it].takeIf { p -> p >= 0 } }.toList().reversed().toMutableList()
			var tip = i
			while (flexChildren[tip].size == 1) { tip = flexChildren[tip][0]; up += tip }
			return up.toIntArray()
		}

		companion object {
			fun of(tree: List<SkeletonBone>, parentIndex: IntArray): Chains? {
				if (tree.none { it.flex }) return null
				return Chains(BooleanArray(tree.size) { tree[it].flex }, parentIndex)
			}
		}
	}

	/** Half width of the span a joint at [child]'s head bends across, given its parent: half the shorter bone. */
	fun rampHalf(child: SkeletonBone, parent: SkeletonBone): Double = minOf(child.length, parent.length) * 0.5

	/**
	 * One path's centerline at one pose, with its bones as the control polygon, as a path deformer has its handles: it
	 * leaves the root's head along the root, runs along each bone, rounds each joint on a corner ([corner]) from as far
	 * before the joint as after it - half the shorter of the two bones ([cornerReach]) - and ends on the last bone's tail.
	 * Each corner is a circular arc (a conic where the bones meet unevenly), so the curve turns evenly through a joint
	 * however the chain was cut into bones, never bends back on itself, and reaches the tip wherever the bones put it.
	 * At a fork the stalk runs straight on to the joint and each lobe turns away from there.
	 *
	 * Arc lengths are counted along the bones, in their rest [lengths]: a vertex keeps its share of the stretch of bones
	 * the piece of curve it lies on stands for, so the art never falls short of the bones however far they bend.
	 */
	class Curve(px: DoubleArray, py: DoubleArray, lengths: DoubleArray, forks: BooleanArray) {
		private val segments = lengths.size
		val total = lengths.sum()

		/**
		 * A piece standing for the bones' arc lengths [from]..[to], sampled for its own arc length: a cubic Bezier through
		 * [x], [y], or with [conic] the rational quadratic through its three points ([corner]).
		 */
		private inner class Piece(val from: Double, val to: Double, val x: DoubleArray, val y: DoubleArray, val conic: Boolean = false) {
			val arcs = DoubleArray(SAMPLES + 1).also { arc ->
				val p = DoubleArray(2)
				var lx = x[0]; var ly = y[0]
				for (i in 1..SAMPLES) {
					at(i.toDouble() / SAMPLES, p)
					arc[i] = arc[i - 1] + hypot(p[0] - lx, p[1] - ly)
					lx = p[0]; ly = p[1]
				}
			}

			fun at(t: Double, out: DoubleArray) {
				if (conic) { corner(x[0], y[0], x[1], y[1], x[2], y[2], t, out); return }
				val u = 1 - t
				val b0 = u * u * u; val b1 = 3 * u * u * t; val b2 = 3 * u * t * t; val b3 = t * t * t
				out[0] = b0 * x[0] + b1 * x[1] + b2 * x[2] + b3 * x[3]
				out[1] = b0 * y[0] + b1 * y[1] + b2 * y[2] + b3 * y[3]
			}

			fun angle(t: Double): Double {
				if (conic) return cornerAngle(x[0], y[0], x[1], y[1], x[2], y[2], t)
				val u = 1 - t
				val dx = 3 * u * u * (x[1] - x[0]) + 6 * u * t * (x[2] - x[1]) + 3 * t * t * (x[3] - x[2])
				val dy = 3 * u * u * (y[1] - y[0]) + 6 * u * t * (y[2] - y[1]) + 3 * t * t * (y[3] - y[2])
				return if (hypot(dx, dy) < 1e-12) atan2(y[3] - y[0], x[3] - x[0]) else atan2(dy, dx)
			}

			/** The parameter at arc length [s], as the same share of this piece's own arc. */
			fun parameter(s: Double): Double {
				val target = ((s - from) / (to - from).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0) * arcs[SAMPLES]
				var i = 1
				while (i < SAMPLES && arcs[i] < target) i++
				val span = arcs[i] - arcs[i - 1]
				return (i - 1 + if (span <= 1e-12) 0.0 else ((target - arcs[i - 1]) / span).coerceIn(0.0, 1.0)) / SAMPLES
			}
		}

		private val pieces = ArrayList<Piece>()
		private val startX = px[0]; private val startY = py[0]
		private val endX = px[segments]; private val endY = py[segments]
		private val startAngle: Double
		private val endAngle: Double

		init {
			val dirX = DoubleArray(segments); val dirY = DoubleArray(segments)
			for (k in 0 until segments) {
				val dx = px[k + 1] - px[k]; val dy = py[k + 1] - py[k]
				val length = hypot(dx, dy)
				if (length > 1e-9) { dirX[k] = dx / length; dirY[k] = dy / length }
				else if (k > 0) { dirX[k] = dirX[k - 1]; dirY[k] = dirY[k - 1] } else dirX[k] = 1.0
			}
			startAngle = atan2(dirY[0], dirX[0]); endAngle = atan2(dirY[segments - 1], dirX[segments - 1])
			val starts = DoubleArray(segments).also { for (k in 1 until segments) it[k] = it[k - 1] + lengths[k - 1] }
			fun midX(k: Int) = (px[k] + px[k + 1]) / 2
			fun midY(k: Int) = (py[k] + py[k + 1]) / 2
			fun line(from: Double, to: Double, ax: Double, ay: Double, bx: Double, by: Double) {
				if (to - from <= 1e-9) return
				pieces += Piece(from, to, doubleArrayOf(ax, ax + (bx - ax) / 3, ax + (bx - ax) * 2 / 3, bx), doubleArrayOf(ay, ay + (by - ay) / 3, ay + (by - ay) * 2 / 3, by))
			}
			// Where the curve has reached, along the bones and on the canvas.
			var atS = 0.0; var atX = px[0]; var atY = py[0]
			for (k in 1 until segments) {
				val joint = starts[k]
				if (forks[k]) {
					// The stalk straight on to the fork, then the lobe turning away from it.
					line(atS, joint, atX, atY, px[k], py[k])
					val reach = hypot(midX(k) - px[k], midY(k) - py[k]) / 3
					pieces += Piece(joint, joint + lengths[k] / 2,
						doubleArrayOf(px[k], px[k] + dirX[k - 1] * reach, midX(k) - dirX[k] * reach, midX(k)),
						doubleArrayOf(py[k], py[k] + dirY[k - 1] * reach, midY(k) - dirY[k] * reach, midY(k)))
					atS = joint + lengths[k] / 2; atX = midX(k); atY = midY(k)
				} else {
					val h = cornerReach(lengths[k - 1], lengths[k]).coerceAtMost(joint - atS)
					val before = h / lengths[k - 1].coerceAtLeast(1e-9); val after = h / lengths[k].coerceAtLeast(1e-9)
					val ax = px[k] + (px[k - 1] - px[k]) * before; val ay = py[k] + (py[k - 1] - py[k]) * before
					val bx = px[k] + (px[k + 1] - px[k]) * after; val by = py[k] + (py[k + 1] - py[k]) * after
					line(atS, joint - h, atX, atY, ax, ay)
					if (h > 1e-9) pieces += Piece(joint - h, joint + h, doubleArrayOf(ax, px[k], bx), doubleArrayOf(ay, py[k], by), conic = true)
					atS = joint + h; atX = bx; atY = by
				}
			}
			line(atS, total, atX, atY, endX, endY)
			if (pieces.isEmpty()) pieces += Piece(0.0, total, doubleArrayOf(px[0], px[0], endX, endX), doubleArrayOf(py[0], py[0], endY, endY))
		}

		private fun piece(s: Double): Piece = pieces.lastOrNull { s >= it.from } ?: pieces.first()

		/** The heading at arc length [s] in radians, constant past both ends. */
		fun thetaAt(s: Double): Double = when {
			s <= 0.0 -> startAngle
			s >= total -> endAngle
			else -> piece(s).let { it.angle(it.parameter(s)) }
		}

		/** The point at arc length [s], carried on along the end bones past either end. */
		fun pointAt(s: Double, out: DoubleArray) {
			when {
				s <= 0.0 -> { out[0] = startX + s * cos(startAngle); out[1] = startY + s * sin(startAngle) }
				s >= total -> { val over = s - total; out[0] = endX + over * cos(endAngle); out[1] = endY + over * sin(endAngle) }
				else -> piece(s).let { it.at(it.parameter(s), out) }
			}
		}

		/** Where a vertex bound at arc length [s] with frame offset ([a] along, [b] across) lies on this curve. */
		fun place(s: Double, a: Double, b: Double, out: DoubleArray) {
			val clamped = s.coerceIn(0.0, total)
			pointAt(clamped, out)
			val theta = thetaAt(clamped)
			val c = cos(theta); val sn = sin(theta)
			out[0] += a * c - b * sn
			out[1] += a * sn + b * c
		}

		/** The frame offset of canvas point ([x], [y]) at arc length [s]: the inverse of [place]. */
		fun offset(s: Double, x: Double, y: Double): DoubleArray {
			val p = DoubleArray(2)
			val clamped = s.coerceIn(0.0, total)
			pointAt(clamped, p)
			val theta = thetaAt(clamped)
			val c = cos(theta); val sn = sin(theta)
			val dx = x - p[0]; val dy = y - p[1]
			return doubleArrayOf(dx * c + dy * sn, -dx * sn + dy * c)
		}
	}

	/** Samples of each piece of a curve when its arc length is measured. */
	private const val SAMPLES = 32

	/** How far either side of a joint between bones [before] and [after] long its corner reaches: half the shorter. */
	fun cornerReach(before: Double, after: Double): Double = minOf(before, after) * 0.5

	/**
	 * How much the rational quadratic A, P, B weighs its control point: cos of half its turn, so it runs on a circular
	 * arc where P is as far from A as from B, and on the nearest conic otherwise. A curve that bends as evenly as the art
	 * it carries does unrolls that art straight; a plain quadratic would bend it hardest at the joint.
	 */
	fun cornerWeight(ax: Double, ay: Double, px: Double, py: Double, bx: Double, by: Double): Double {
		val ux = px - ax; val uy = py - ay; val vx = bx - px; val vy = by - py
		val lu = hypot(ux, uy); val lv = hypot(vx, vy)
		if (lu < 1e-9 || lv < 1e-9) return 1.0
		val c = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1.0, 1.0)
		// cos(θ/2) = sqrt((1 + cos θ) / 2)
		return kotlin.math.sqrt((1 + c) / 2).coerceAtLeast(0.05)
	}

	/** The point [t] of the way (by parameter) along the rational quadratic A, P, B ([cornerWeight]). */
	fun corner(ax: Double, ay: Double, px: Double, py: Double, bx: Double, by: Double, t: Double, out: DoubleArray) {
		val w = cornerWeight(ax, ay, px, py, bx, by)
		val a = (1 - t) * (1 - t); val p = 2 * t * (1 - t) * w; val b = t * t
		val d = a + p + b
		out[0] = (a * ax + p * px + b * bx) / d
		out[1] = (a * ay + p * py + b * by) / d
	}

	/** The heading in radians at [t] along the rational quadratic A, P, B. */
	fun cornerAngle(ax: Double, ay: Double, px: Double, py: Double, bx: Double, by: Double, t: Double): Double {
		val w = cornerWeight(ax, ay, px, py, bx, by)
		val a = (1 - t) * (1 - t); val p = 2 * t * (1 - t) * w; val b = t * t
		val da = -2 * (1 - t); val dp = 2 * (1 - 2 * t) * w; val db = 2 * t
		val d = a + p + b; val dd = da + dp + db
		val nx = a * ax + p * px + b * bx; val ny = a * ay + p * py + b * by
		val dx = (da * ax + dp * px + db * bx) * d - nx * dd
		val dy = (da * ay + dp * py + db * by) * d - ny * dd
		return if (hypot(dx, dy) < 1e-12) atan2(by - ay, bx - ax) else atan2(dy, dx)
	}

	/**
	 * [path]'s curve through the joints of [bones]: the root's head and each bone's tail. [bones] may be the drawn
	 * geometry, the rest geometry or the bones where a pose puts them; arc lengths are counted in [lengths], the
	 * drawn lengths by default, so a vertex keeps its share of its bone.
	 */
	fun curve(bones: List<SkinBone>, chains: Chains, path: IntArray, lengths: DoubleArray? = null): Curve {
		val root = bones[path[0]]
		val px = DoubleArray(path.size + 1); val py = DoubleArray(path.size + 1)
		px[0] = root.headX; py[0] = root.headY
		for (k in path.indices) { px[k + 1] = bones[path[k]].tailX; py[k + 1] = bones[path[k]].tailY }
		return Curve(px, py, lengths ?: DoubleArray(path.size) { bones[path[it]].length.coerceAtLeast(1e-3) },
			BooleanArray(path.size) { k -> k > 0 && chains.forks(path[k]) })
	}

	/** Whether ([x], [y]) lies on the mesh of [canvas] and [triangles]. */
	fun inside(canvas: FloatArray, triangles: IntArray, x: Double, y: Double): Boolean {
		for (at in 0 until triangles.size - 2 step 3) {
			val a = triangles[at] * 2; val b = triangles[at + 1] * 2; val c = triangles[at + 2] * 2
			if (maxOf(a, b, c) + 1 >= canvas.size) continue
			val ax = canvas[a].toDouble(); val ay = canvas[a + 1].toDouble()
			val bx = canvas[b].toDouble(); val by = canvas[b + 1].toDouble()
			val cx = canvas[c].toDouble(); val cy = canvas[c + 1].toDouble()
			if (x < minOf(ax, bx, cx) || x > maxOf(ax, bx, cx) || y < minOf(ay, by, cy) || y > maxOf(ay, by, cy)) continue
			val d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
			val d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
			val d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
			if (!((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))) return true
		}
		return false
	}

	// -------------------------------------------------------------------------------------------------------------
	// Binding

	/**
	 * How the vertices of one mesh ride its tree's flexible chains. A vertex with a [path] index of -1 is not on a
	 * chain; any other is bound at arc length [s] along [paths] at that index with frame offset ([a], [b]), measured
	 * on the drawn ([bound]) curve. [label] is the bone each vertex was reached from, -1 where none reached it.
	 */
	class Binding(
		val chains: Chains,
		/** Each bone's drawn length, which arc lengths are counted in. */
		val lengths: DoubleArray,
		val paths: List<IntArray>,
		val label: IntArray,
		/** The bone whose path each vertex follows, -1 where it follows none. */
		val follow: IntArray,
		val path: IntArray,
		val s: DoubleArray,
		val a: DoubleArray,
		val b: DoubleArray,
		/**
		 * Past a fork: the arc length of the fork the vertex lies beyond (-1 for none), its offset in the frame the
		 * stalk ends in there ([forkU] along, [forkV] across), and how far it follows its lobe rather than that frame.
		 */
		val forkS: DoubleArray,
		val forkU: DoubleArray,
		val forkV: DoubleArray,
		val forkWeight: DoubleArray,
	) {
		fun bound(vertex: Int): Boolean = path[vertex] >= 0

		/**
		 * The joints along its path that bend vertex [vertex]: every joint up to the head of the bone after the one it
		 * follows, whose bend reaches back into it. The chain's root is not among them; it turns the vertex rigidly.
		 */
		fun bending(vertex: Int): Set<Int> {
			val p = path[vertex]
			if (p < 0) return emptySet()
			val bones = paths[p]
			val last = (bones.indexOf(follow[vertex]) + 1).coerceIn(0, bones.size - 1)
			return (1..last).mapTo(HashSet()) { bones[it] }
		}

		/** The root of the chain vertex [vertex] rides, -1 where it rides none. */
		fun root(vertex: Int): Int = path[vertex].let { if (it < 0) -1 else paths[it][0] }

		/**
		 * Where every bound vertex lies on its curve through the joints of [bones] - the rest geometry, or where a pose
		 * puts the bones; [out] keeps unbound vertices as given.
		 */
		fun placed(bones: List<SkinBone>, out: FloatArray) {
			val curves = paths.map { curve(bones, chains, it, DoubleArray(it.size) { k -> lengths[it[k]] }) }
			val p = DoubleArray(2)
			val joint = DoubleArray(2)
			for (v in path.indices) {
				val index = path[v]
				if (index < 0) continue
				val curve = curves[index]
				curve.place(s[v], a[v], b[v], p)
				// Near a fork a lobe's art eases out of the frame the stalk ends in, so the corner between them bends
				// instead of shearing.
				if (forkS[v] >= 0.0 && forkWeight[v] < 1.0) {
					curve.pointAt(forkS[v], joint)
					val theta = curve.thetaAt(forkS[v])
					val c = cos(theta); val sn = sin(theta)
					val x = joint[0] + forkU[v] * c - forkV[v] * sn
					val y = joint[1] + forkU[v] * sn + forkV[v] * c
					p[0] = x + (p[0] - x) * forkWeight[v]
					p[1] = y + (p[1] - y) * forkWeight[v]
				}
				out[v * 2] = p[0].toFloat()
				out[v * 2 + 1] = p[1].toFloat()
			}
		}

		/** The turn in degrees from the drawn frame to the rest frame at each bound vertex, 0 elsewhere. */
		fun restTurns(drawn: List<SkinBone>, rest: List<SkinBone>): DoubleArray {
			val bound = paths.map { curve(drawn, chains, it) }
			val resting = paths.map { curve(rest, chains, it, DoubleArray(it.size) { k -> lengths[it[k]] }) }
			return DoubleArray(path.size) { v ->
				val index = path[v]
				if (index < 0) 0.0 else Math.toDegrees(resting[index].thetaAt(s[v]) - bound[index].thetaAt(s[v]))
			}
		}
	}

	/**
	 * Binds [canvas] (the drawn vertices, interleaved) of a mesh with [triangles] to the chains of [bones] (the
	 * tree's drawn geometry). [skins] are the vertices' bone weights: a vertex that comes to follow a flexible bone,
	 * or blends from a rigid parent into a chain's root, is bound to that chain. Returns the binding and the weights
	 * with every vertex reached from a flexible bone made rigid to it - the curve carries it - or null when the tree
	 * has no chain.
	 */
	fun bind(canvas: FloatArray, triangles: IntArray, bones: List<SkinBone>, flex: BooleanArray, skins: List<VertexSkin>): Pair<List<VertexSkin>, Binding>? {
		if (flex.none { it }) return null
		val chains = Chains(flex, IntArray(bones.size) { bones[it].parent })
		val count = canvas.size / 2
		val edges = Array(count) { HashMap<Int, Double>() }
		for (at in 0 until triangles.size - 2 step 3) for (k in 0..2) {
			val u = triangles[at + k]; val w = triangles[at + (k + 1) % 3]
			if (u !in 0 until count || w !in 0 until count || u == w) continue
			val length = hypot((canvas[u * 2] - canvas[w * 2]).toDouble(), (canvas[u * 2 + 1] - canvas[w * 2 + 1]).toDouble())
			edges[u][w] = length; edges[w][u] = length
		}

		// Seeds: the vertex nearest each sample along each bone, taking the closest sample's bone and arc length.
		val starts = DoubleArray(bones.size)
		for (i in bones.indices) {
			val p = chains.flexParent[i]
			if (p >= 0) starts[i] = starts[p] + bones[p].length
		}
		val seedLabel = IntArray(count) { -1 }
		val seedDistance = DoubleArray(count) { Double.POSITIVE_INFINITY }
		for ((i, bone) in bones.withIndex()) {
			val n = ceil(bone.length / SEED_SPACING).toInt().coerceIn(2, 256)
			val reach = bone.length.coerceAtLeast(8.0)
			for (k in 0 until n) {
				val t = (k + 0.5) / n
				val x = bone.headX + (bone.tailX - bone.headX) * t
				val y = bone.headY + (bone.tailY - bone.headY) * t
				// A bone drawn on past the art - a fork's lobes reaching beyond a small fluke - seeds nothing there: the
				// art's edge would otherwise take an arc length far down the bone and swing round it.
				if (!inside(canvas, triangles, x, y)) continue
				var best = -1
				var bestDistance = reach
				for (v in 0 until count) {
					val d = hypot(canvas[v * 2] - x, canvas[v * 2 + 1] - y)
					if (d < bestDistance) { bestDistance = d; best = v }
				}
				if (best < 0 || bestDistance >= seedDistance[best]) continue
				seedDistance[best] = bestDistance
				seedLabel[best] = i
			}
		}

		// Labels: the bone of the nearest seed along the mesh.
		val label = IntArray(count) { -1 }
		val distance = DoubleArray(count) { Double.POSITIVE_INFINITY }
		val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
		for (v in 0 until count) if (seedLabel[v] >= 0) { label[v] = seedLabel[v]; distance[v] = 0.0; queue.add(0.0 to v) }
		while (queue.isNotEmpty()) {
			val (d, v) = queue.remove()
			if (d > distance[v]) continue
			for ((w, length) in edges[v]) {
				val next = d + length
				if (next < distance[w]) { distance[w] = next; label[w] = label[v]; queue.add(next to w) }
			}
		}

		// Weights: the chain carries what it reached; a rigid parent keeps its blend into a chain's root.
		val result = skins.toMutableList()
		val follows = IntArray(count) { -1 }
		for (v in 0 until count) {
			val skin = skins[v]
			val l = label[v]
			if (l < 0) {
				// No seed reached it: keep the nearest bone, and the curve of whichever chain that is.
				follows[v] = when {
					flex[skin.from] -> skin.from
					!skin.rigid && flex[skin.to] -> skin.to
					else -> -1
				}
				if (follows[v] >= 0 && flex[skin.from]) result[v] = VertexSkin(skin.from, skin.from, 0f)
				continue
			}
			val blendsIntoChain = !skin.rigid && !flex[skin.from] && flex[skin.to] && chains.flexParent[skin.to] < 0 && bones[skin.to].parent == skin.from
			if (flex[l]) {
				if (blendsIntoChain && (l == skin.to || l == skin.from)) { follows[v] = skin.to; continue }
				result[v] = VertexSkin(l, l, 0f)
				follows[v] = l
			} else {
				if (blendsIntoChain && skin.from == l) { follows[v] = skin.to; continue }
				if (flex[skin.from] || flex[skin.to]) result[v] = VertexSkin(l, l, 0f)
			}
		}

		// Arc lengths: each vertex's closest point on the drawn curve, looked for only along the bone it was reached from
		// and half of each bone beside it, so a vertex never takes the arc length of a coil it is not joined to. A vertex
		// then sits square across the curve from its point, and the curve's turn carries it and its neighbours alike.
		val pathIndex = HashMap<List<Int>, Int>()
		val paths = ArrayList<IntArray>()
		val path = IntArray(count) { -1 }
		for (v in 0 until count) {
			val i = follows[v]
			if (i < 0) continue
			val p = chains.pathOf(i)
			path[v] = pathIndex.getOrPut(p.toList()) { paths += p; paths.size - 1 }
		}
		val curves = paths.map { curve(bones, chains, it) }
		val samples = curves.map { c ->
			val n = ceil(c.total / PROJECTION_STEP).toInt().coerceIn(8, 8192)
			val xs = DoubleArray(n + 1); val ys = DoubleArray(n + 1)
			val point = DoubleArray(2)
			for (k in 0..n) { c.pointAt(c.total * k / n, point); xs[k] = point[0]; ys[k] = point[1] }
			Triple(n, xs, ys)
		}
		val s = DoubleArray(count)
		val a = DoubleArray(count)
		val b = DoubleArray(count)
		for (v in 0 until count) {
			val index = path[v]
			if (index < 0) continue
			val i = follows[v]
			val curve = curves[index]
			val (n, xs, ys) = samples[index]
			val before = chains.flexParent[i].let { if (it >= 0) bones[it].length * 0.5 else 0.0 }
			val after = chains.flexChildren[i].maxOfOrNull { bones[it].length * 0.5 } ?: 0.0
			val from = ((starts[i] - before) / curve.total * n).toInt().coerceIn(0, n)
			val to = ceil((starts[i] + bones[i].length + after) / curve.total * n).toInt().coerceIn(from, n)
			val x = canvas[v * 2].toDouble(); val y = canvas[v * 2 + 1].toDouble()
			var best = from
			var bestDistance = Double.MAX_VALUE
			for (k in from..to) {
				val d = (xs[k] - x) * (xs[k] - x) + (ys[k] - y) * (ys[k] - y)
				if (d < bestDistance) { bestDistance = d; best = k }
			}
			// Refined on the segments either side of the closest sample.
			var arc = curve.total * best / n
			var refined = bestDistance
			for (k in listOf(best - 1, best)) {
				if (k < from || k + 1 > to) continue
				val dx = xs[k + 1] - xs[k]; val dy = ys[k + 1] - ys[k]
				val t = (((x - xs[k]) * dx + (y - ys[k]) * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-12)).coerceIn(0.0, 1.0)
				val px = xs[k] + dx * t; val py = ys[k] + dy * t
				val d = (px - x) * (px - x) + (py - y) * (py - y)
				if (d < refined) { refined = d; arc = curve.total * (k + t) / n }
			}
			s[v] = arc
			val offset = curve.offset(arc, x, y)
			a[v] = offset[0]; b[v] = offset[1]
		}

		// Past a fork, how far each vertex follows its lobe: none at the fork, all of it half the lobe away.
		val forkS = DoubleArray(count) { -1.0 }
		val forkU = DoubleArray(count)
		val forkV = DoubleArray(count)
		val forkWeight = DoubleArray(count) { 1.0 }
		for (v in 0 until count) {
			val index = path[v]
			if (index < 0) continue
			val bonesOnPath = paths[index]
			val k = (1 until bonesOnPath.size).lastOrNull { chains.forks(bonesOnPath[it]) && starts[bonesOnPath[it]] <= s[v] } ?: continue
			val lobe = bonesOnPath[k]
			val curve = curves[index]
			val joint = DoubleArray(2).also { curve.pointAt(starts[lobe], it) }
			val theta = curve.thetaAt(starts[lobe])
			val dx = canvas[v * 2] - joint[0]; val dy = canvas[v * 2 + 1] - joint[1]
			forkS[v] = starts[lobe]
			forkU[v] = dx * cos(theta) + dy * sin(theta)
			forkV[v] = -dx * sin(theta) + dy * cos(theta)
			forkWeight[v] = SkeletonWeights.smoothstep(hypot(dx, dy) / (bones[lobe].length * 0.5).coerceAtLeast(1.0))
		}
		return result to Binding(chains, DoubleArray(bones.size) { bones[it].length.coerceAtLeast(1e-3) }, paths, label, follows, path, s, a, b,
			forkS, forkU, forkV, forkWeight)
	}
}
