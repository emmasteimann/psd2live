package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Proposes a humanoid skeleton from the layers' semantic tags, the way the head rig is placed from the
 * face: a body frame first (the torso's centre, shoulder and hip lines), then every limb fitted to the
 * meshes tagged for it on its side, with human proportions filling in whatever the drawing does not say.
 *
 * Binding follows the tags. Arms take the handwear of their side, legs the legwear, feet the footwear,
 * and tails and wings their own layers; toggle and switch variants of a part are bound with it but do
 * not steer where its joints go, since an alternate pose would pull the fit toward a limb that is not
 * shown. Limbs are expected to arrive split per side (the import's mesh split does that); a mesh still
 * drawn across both sides cannot follow either side's bones, so it only helps place the joints and is
 * left unbound.
 *
 * Joints follow the limb's medial line measured *through the mesh* ([LimbTrace]): the art is sampled along its mesh edges,
 * distances run through it from the root, and the middle of each cross-section is a point of the line, so an arm folded
 * back on itself still reads as one continuous arm. Where the art branches - a fish tail's flukes, a second tail, a
 * wing's feathers - the branch that persists becomes a lobe with its own bones from the fork; the trunk follows the
 * heavier branch, so a charm hanging off a sleeve does not pass for the hand. A long limb's root joint is the centre
 * of its end cap, a short wide one is put on its axis; an elbow or knee goes where the line bends most if it clearly
 * bends, else at the proportional point.
 *
 * Every reading is held to what a human body allows ([Anatomy]): shoulders and hips a plausible distance from the spine
 * and height from the shoulder and hip lines, the two sides alike within what a turn or lean explains, hinges dividing
 * their limb in human proportion and not folding through themselves. The body frame is read off the meshes, not the
 * layers' bounds, and layers of a few pixels (an empty template slot) are not parts at all.
 */
object SkeletonAutoBuilder {
	private const val UPPER_BODY = SkeletonSpec.UPPER_BODY_ID
	private const val LOWER_BODY = SkeletonSpec.LOWER_BODY_ID
	private const val HEAD = SkeletonSpec.HEAD_ID

	/** Tags the skeleton takes out of the shared breath warp. */
	val limbTags = setOf(SemanticTag.HANDWEAR, SemanticTag.LEGWEAR, SemanticTag.FOOTWEAR, SemanticTag.TAIL, SemanticTag.WINGS)

	/** Upper arm : forearm : hand, shoulder to fingertip. */
	private const val UPPER_ARM = 1.0
	private const val FOREARM = 0.85
	private const val HAND = 0.55

	/** A limb counts as folded when going via its outermost corner is this much longer than going straight. */
	private const val FOLD_RATIO = 1.25f

	/** A joint only goes to a bend at least this sharp; a straighter limb uses the proportional point. */
	private const val MIN_BEND_DEGREES = 20.0

	/** One tagged mesh: its drawable, its layer, its rest vertices in canvas pixels and its triangles. */
	class Part(val drawableId: String, val layer: ClassifiedLayer, val points: FloatArray, val indices: IntArray) {
		/** Toggle and switch variants are bound but do not place joints. */
		val placesJoints: Boolean get() = layer.semantic.type == LayerType.PRESET

		/** Where the mesh really is: the layer's own bounds can be a whole canvas padded with transparency. */
		val bounds: Bounds by lazy {
			var left = Float.MAX_VALUE; var top = Float.MAX_VALUE; var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
			for (i in 0 until points.size / 2) {
				left = minOf(left, points[i * 2]); right = maxOf(right, points[i * 2])
				top = minOf(top, points[i * 2 + 1]); bottom = maxOf(bottom, points[i * 2 + 1])
			}
			Bounds(left, top, right, bottom)
		}
	}

	fun build(analysis: PipelineAnalysis, rig: BuiltRig): SkeletonSpec =
		build(analysis, rig.puppet, rig.layerIdByDrawableId, canvasVertices(rig.puppet))

	fun canvasVertices(puppet: PuppetModel): Map<String, FloatArray> =
		CpuDeformationEvaluator().evaluate(puppet.copy(glues = emptyList()), emptyMap()).worldPositions
			.mapKeys { it.key.raw }
			.mapValues { (_, world) -> FloatArray(world.size) { if (it % 2 == 1) -world[it] else world[it] } }

	/** A mesh smaller than this share of the canvas is a placeholder layer, not a body part. */
	private const val PLACEHOLDER_SHARE = 0.025f

	private fun parts(
		analysis: PipelineAnalysis,
		puppet: PuppetModel,
		layerIdByDrawableId: Map<String, String>,
		verticesByDrawable: Map<String, FloatArray>,
	): List<Part> {
		val layerById = analysis.layers.associateBy { it.source.id.raw }
		val canvas = maxOf(analysis.source.widthPx, analysis.source.heightPx).toFloat()
		return puppet.drawables.mapNotNull { drawable ->
			val layer = layerById[layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw] ?: return@mapNotNull null
			val points = verticesByDrawable[drawable.id.raw]?.takeIf { it.size >= 6 } ?: return@mapNotNull null
			Part(drawable.id.raw, layer, points, drawable.mesh?.indices ?: IntArray(0))
				// A layer that holds a speck of pixels so the slot exists has nothing to hang a bone on.
				.takeIf { maxOf(it.bounds.width, it.bounds.height) >= canvas * PLACEHOLDER_SHARE }
		}
	}

	/** The torso the limbs hang from: its centre line, shoulder and hip lines, and shoulder width. */
	private class BodyFrame(val centerX: Float, val shoulderY: Float, val hipY: Float, val shoulderHalf: Float, val torso: Bounds) {
		val torsoHeight: Float get() = hipY - shoulderY

		fun sideOf(part: Part): Side = part.layer.semantic.side.takeIf { it != Side.NONE }
			// Cubism convention: L is the character's left, which faces the viewer's right.
			?: if (meanX(part.points) >= centerX) Side.LEFT else Side.RIGHT

		/** A mesh with a real share of its vertices clearly on each side of the centre line. */
		fun straddles(part: Part): Boolean {
			if (part.layer.semantic.side != Side.NONE) return false
			val margin = torso.width * 0.05f
			var left = 0
			var right = 0
			for (i in 0 until part.points.size / 2) {
				val x = part.points[i * 2]
				if (x < centerX - margin) right++ else if (x > centerX + margin) left++
			}
			return minOf(left, right) >= part.points.size / 2 * 0.2f
		}

		/** The vertices of [part] on [side] of the centre line. */
		fun half(part: Part, side: Side): FloatArray = (0 until part.points.size / 2)
			.filter { (part.points[it * 2] >= centerX) == (side == Side.LEFT) }
			.flatMap { listOf(part.points[it * 2], part.points[it * 2 + 1]) }.toFloatArray()

		companion object {
			/**
			 * Read off the meshes, not the layers' bounds: the shoulder line a little under the top of the torso
			 * garment, the hip line a little under the top of the lower garment - or, with none, at the top of
			 * the legs - and the shoulder joints inside the torso's edges just below the shoulders.
			 */
			fun of(analysis: PipelineAnalysis, parts: List<Part>): BodyFrame {
				val anchors = analysis.anchors
				val torsoParts = parts.filter { it.layer.semantic.tag == SemanticTag.TOPWEAR && it.placesJoints }
				val torsoMesh = torsoParts.map { it.bounds }.reduceOrNull(Bounds::union)
				val torso = torsoMesh ?: anchors.body
				val shoulderY = torsoMesh?.let { it.top + it.height * 0.12f } ?: anchors.shoulderY
				val lower = parts.filter { it.layer.semantic.tag == SemanticTag.BOTTOMWEAR && it.placesJoints }.map { it.bounds }.reduceOrNull(Bounds::union)
				val legTop = parts.filter { it.layer.semantic.tag == SemanticTag.LEGWEAR && it.placesJoints }.minOfOrNull { it.bounds.top }
				val hipY = (lower?.let { it.top + it.height * 0.2f } ?: legTop ?: anchors.hipY).coerceAtLeast(shoulderY + 1f)
				// The torso's width just below the shoulder line; the shoulder joints sit inside its edges.
				val band = torsoParts.flatMap { part ->
					(0 until part.points.size / 2).filter { part.points[it * 2 + 1] in shoulderY..(shoulderY + (hipY - shoulderY) * 0.2f) }
						.map { part.points[it * 2] }
				}
				val halfWidth = if (band.size >= 4) (band.max() - band.min()) * 0.5f else torso.width * 0.5f
				return BodyFrame(torso.centerX, shoulderY, hipY, halfWidth * 0.8f, torso)
			}
		}
	}

	/** One side's share of a tag: the meshes to bind, and the geometry the joints are fitted to. */
	private class SideParts(val bind: List<String>, val fit: List<Pair<FloatArray, IntArray>>)

	/** A limb's art as a graph with its root end read off it, where the root joint's hint says the limb starts. */
	private class Anchor(
		val graph: LimbGraph,
		val ends: LimbGraph.Ends,
		val cap: LimbGraph.Cap?,
		val hintX: Float,
		val hintY: Float,
		val tolerance: Float,
		/** How many times as long as wide the limb is, end to end: only a long one has two ends worth reading a cap off. */
		val aspect: Float,
	) {
		val elongated: Boolean get() = aspect >= MIN_ASPECT

		/**
		 * The root joint read off the art: the end cap's centre of a long limb that really starts near the hint
		 * (within [tolerance]) - a limb whose art stops short of the body, like a forearm drawn without its sleeve, says
		 * nothing about it - and otherwise null, the hint to be moved onto the limb's axis.
		 */
		fun capRoot(): Pair<Float, Float>? = cap?.takeIf { elongated && hypot(it.x - hintX, it.y - hintY) <= tolerance }?.let { it.x to it.y }

		/**
		 * The tree of the limb's art from [root], which is the cap's centre when [fromCap], else a point known only along the
		 * limb - moved across onto its axis, which the first trace finds - and the walk starts at the vertices nearest the hint.
		 */
		fun trace(root: Pair<Float, Float>, fromCap: Boolean, forks: Boolean, minLobe: Double = 0.1, centeredTip: Boolean = false): LimbTree? {
			val seeds = when {
				fromCap -> ends.rootSlice
				// A short, wide limb: the end edge across the axis, not the corner the hint happens to be nearest.
				!elongated -> graph.axisSlice(hintX, hintY)
				else -> graph.nearest(hintX, hintY)
			}
			val first = LimbTrace.trace(graph, root.first, root.second, seeds, forks, minLobe, centeredTip) ?: return null
			// A long limb has the axis of a stick only if it is straight, and the hint of one that stands off from the art
			// is the better word: only a short, wide limb, whose ends cannot say where it runs, is moved onto its axis.
			if (fromCap || elongated) return first
			// Only a hint that lies on the art says where across it the limb runs; one off the art (a forearm drawn without
			// its sleeve, a leg under a skirt) says nothing about it.
			val nearest = (0 until graph.count).minOf { hypot(graph.x(it) - root.first, graph.y(it) - root.second) }
			if (nearest > first.trunk.medianWidth() * 0.5) return first
			val moved = first.trunk.snap(root.first, root.second, tolerance * 0.5f) ?: return first
			return LimbTrace.trace(graph, moved.first, moved.second, seeds, forks, minLobe, centeredTip) ?: first
		}

		companion object {
			private const val MIN_ASPECT = 3.5f

			fun of(parts: List<Pair<FloatArray, IntArray>>, hintX: Float, hintY: Float, tolerance: Float): Anchor? {
				val graph = LimbGraph(parts)
				val ends = graph.ends(hintX, hintY) ?: return null
				val line = LimbTrace.trace(graph, hintX, hintY, graph.axisSlice(hintX, hintY), forks = false)?.trunk
				val width = line?.medianWidth() ?: 0.0
				// The chord, not the length: slice to slice the line wobbles, and a curled limb is not long for its width.
				val aspect = if (line == null || width < 1.0) 0f else (line.at(0.0).let { a -> line.at(1.0).let { b -> hypot(b.first - a.first, b.second - a.second) } } / width).toFloat()
				return Anchor(graph, ends, graph.capCentre(ends), hintX, hintY, tolerance, aspect)
			}
		}
	}

	fun build(
		analysis: PipelineAnalysis,
		puppet: PuppetModel,
		layerIdByDrawableId: Map<String, String>,
		verticesByDrawable: Map<String, FloatArray>,
	): SkeletonSpec {
		val all = parts(analysis, puppet, layerIdByDrawableId, verticesByDrawable)
		val body = BodyFrame.of(analysis, all)
		val centerX = body.centerX
		val shoulderY = body.shoulderY
		val hipY = body.hipY
		val torsoHeight = body.torsoHeight
		fun tagged(tag: SemanticTag) = all.filter { it.layer.semantic.tag == tag }

		val bones = mutableListOf<SkeletonBone>()
		fun anchor(id: String, parent: String?, role: BoneRole, hx: Float, hy: Float, tx: Float, ty: Float, meshes: List<String>) {
			bones += SkeletonBone(id, SkeletonNames.bone(role, Side.NONE), parent, role, Side.NONE, hx, hy, tx, ty, meshes)
		}
		// The body splits at the waist into the two halves the body rig moves; the head stands on the
		// pivot of the head Z rotation and leans with it, from the mouth line up to the top of the face.
		anchor(UPPER_BODY, null, BoneRole.UPPER_BODY, centerX, hipY, centerX, shoulderY,
			(tagged(SemanticTag.TOPWEAR) + tagged(SemanticTag.NECKWEAR) + tagged(SemanticTag.NECK)).map { it.drawableId })
		anchor(LOWER_BODY, null, BoneRole.LOWER_BODY, centerX, hipY, centerX, hipY + torsoHeight * 0.35f,
			tagged(SemanticTag.BOTTOMWEAR).map { it.drawableId })
		// Placed as the rig builder places the head rotation, so the two stay one pivot.
		val rigged = analysis.copy(layers = analysis.layers.filter { it.source !is MouthLipLayer })
		val faceRig = NinePoseFaceRig.from(rigged.calibration ?: rigged)
		val headSpace = faceRig.coordinateSpace
		val (pivotX, pivotY) = headSpace.toCanvas(faceRig.centerX, faceRig.mouthLineY)
		val (crownX, crownY) = headSpace.toCanvas(faceRig.centerX, faceRig.face.top)
		anchor(HEAD, UPPER_BODY, BoneRole.HEAD, pivotX, pivotY, crownX, crownY, emptyList())

		fun direction(x: Float) = if (x < centerX) 1f else -1f

		/** A mesh across both sides gives each side its half of the geometry and binds to neither. */
		fun bySide(tag: SemanticTag): Map<Side, SideParts> {
			val bind = HashMap<Side, MutableList<String>>()
			val fit = HashMap<Side, MutableList<Pair<FloatArray, IntArray>>>()
			val parts = tagged(tag)
			val placing = parts.filter { it.placesJoints }.ifEmpty { parts }
			for (part in parts) {
				if (body.straddles(part)) continue
				bind.getOrPut(body.sideOf(part)) { mutableListOf() } += part.drawableId
			}
			for (part in placing) {
				if (body.straddles(part)) {
					// Halves lose their triangles; the medial line falls back to nearest-neighbour paths.
					for (side in listOf(Side.LEFT, Side.RIGHT)) {
						body.half(part, side).takeIf { it.size >= 6 }?.let { fit.getOrPut(side) { mutableListOf() } += it to IntArray(0) }
					}
				} else {
					fit.getOrPut(body.sideOf(part)) { mutableListOf() } += part.points to part.indices
				}
			}
			// Declaration order, not hash order: enum hash codes change between processes.
			return (bind.keys + fit.keys).sorted().associateWith { SideParts(bind[it].orEmpty(), fit[it].orEmpty()) }
		}

		// Arms: the shoulder joint is the centre of the arm art's top end - inside the torso's edge below the
		// shoulder line, where that is what the drawing says - held to where a shoulder can be: a plausible distance from
		// the spine and height from the shoulder line, and the two sides about as far from the spine and as high as each other.
		val arms = bySide(SemanticTag.HANDWEAR).filterValues { it.fit.isNotEmpty() }.mapNotNull { (side, arm) ->
			val sign = if (side == Side.LEFT) 1f else -1f
			val hintX = centerX + sign * body.shoulderHalf
			val hintY = shoulderY + torsoHeight * 0.08f
			Anchor.of(arm.fit, hintX, hintY, torsoHeight * 0.3f)?.let { Triple(side, arm, it) }
		}
		val shoulders = Anatomy.symmetric(arms.associate { (side, _, found) ->
			side to Anatomy.shoulder(found.capRoot() ?: (found.hintX to found.hintY), centerX, shoulderY, torsoHeight, body.shoulderHalf, if (side == Side.LEFT) 1f else -1f)
		}, arms.filter { it.third.capRoot() != null }.map { it.first }.toSet(), centerX)
		for ((side, arm, found) in arms) {
			val capRoot = found.capRoot()
			val root = shoulders.getValue(side)
			val tree = found.trace(root, capRoot != null && capRoot == root, forks = false, centeredTip = true) ?: continue
			val line = tree.trunk
			val (sx, sy) = line.at(0.0)
			val total = UPPER_ARM + FOREARM + HAND
			val tip = line.at(1.0)
			val armPoints = tree.trunkVertices
			// A bent arm has its elbow at the fold's outer corner; a straight one where the proportions say - and always where
			// an elbow can be: the upper arm and the rest in human proportion, the elbow bending the way it does.
			val proportional = line.at(UPPER_ARM / total.toDouble())
			val drawn = foldCorner(armPoints, sx, sy, tip.first, tip.second) ?: line.at(line.jointNear(0.28, 0.62, UPPER_ARM / total))
			val e = if (Anatomy.hinge(sx to sy, drawn, tip, 0.3f, 0.6f)) drawn else proportional
			val wristShare = FOREARM / (FOREARM + HAND)
			val w = (e.first + (tip.first - e.first) * wristShare).toFloat() to (e.second + (tip.second - e.second) * wristShare).toFloat()
			val s = side.name.first().lowercase()
			val dir = direction(sx)
			bones += SkeletonBone("arm_upper_$s", SkeletonNames.bone(BoneRole.UPPER_ARM, side), UPPER_BODY, BoneRole.UPPER_ARM, side,
				sx, sy, e.first, e.second, arm.bind, direction = dir)
			bones += SkeletonBone("arm_fore_$s", SkeletonNames.bone(BoneRole.FOREARM, side), "arm_upper_$s", BoneRole.FOREARM, side,
				e.first, e.second, w.first, w.second, direction = dir)
			bones += SkeletonBone("hand_$s", SkeletonNames.bone(BoneRole.HAND, side), "arm_fore_$s", BoneRole.HAND, side,
				w.first, w.second, tip.first, tip.second, direction = dir)
		}

		// Legs: hip joints under the hip line at the top of each leg; the ankle at the mouth of the shoe
		// when the shoes are their own meshes, the knee halfway or where the leg bends.
		val legs = bySide(SemanticTag.LEGWEAR)
		val feet = bySide(SemanticTag.FOOTWEAR)
		// How far each leg's top is from the spine, outward, as the hip hint reads it.
		val legTopOffset = legs.filterValues { it.fit.isNotEmpty() }.mapValues { (sd, p) ->
			val pts = concat(p.fit.map { it.first })
			val ys = (0 until pts.size / 2).map { pts[it * 2 + 1] }
			val top = ys.min(); val h = ys.max() - top
			val tx = mean(pts, (0 until pts.size / 2).filter { pts[it * 2 + 1] <= top + h * 0.15f }).first
			val hx = tx + (centerX - tx) * 0.1f
			(hx - centerX) * (if (sd == Side.LEFT) 1f else -1f)
		}
		for (side in listOf(Side.LEFT, Side.RIGHT)) {
			val leg = legs[side]?.takeIf { it.fit.isNotEmpty() }
			val foot = feet[side]?.takeIf { it.fit.isNotEmpty() }
			if (leg == null && foot == null) continue
			val s = side.name.first().lowercase()
			var parent = LOWER_BODY
			var ankle: Pair<Float, Float>? = null
			val footPoints = foot?.let { concat(it.fit.map { f -> f.first }) }
			// The top of the shoe, where the leg goes in.
			val shoeMouth = footPoints?.let { points ->
				val ys = (0 until points.size / 2).map { points[it * 2 + 1] }
				val top = ys.min()
				val height = ys.max() - top
				mean(points, (0 until points.size / 2).filter { points[it * 2 + 1] <= top + height * 0.3f })
			}
			if (leg != null) {
				val legPoints = concat(leg.fit.map { it.first })
				val ys = (0 until legPoints.size / 2).map { legPoints[it * 2 + 1] }
				val legTop = ys.min()
				val legHeight = ys.max() - legTop
				val topX = mean(legPoints, (0 until legPoints.size / 2).filter { legPoints[it * 2 + 1] <= legTop + legHeight * 0.15f }).first
				// The hip joint is where the thigh turns, just under the hip line - hidden under a skirt when
				// the drawn leg starts lower - and a little inside the top of the drawn leg.
				val hintY = hipY + torsoHeight * 0.12f
				val hintX = topX + (centerX - topX) * 0.1f
				val hipOut = ((hintX - centerX) * (if (side == Side.LEFT) 1f else -1f)).let { own ->
					legs.filter { (_, p) -> p.fit.isNotEmpty() }.keys.mapNotNull { sd -> legTopOffset[sd] }.takeIf { it.size == 2 }?.average()?.toFloat() ?: own
				}
				val found = Anchor.of(leg.fit, hintX, hintY, torsoHeight * 0.5f) ?: continue
				val cap = found.cap?.takeIf { found.elongated }
				// A leg drawn from the hip has its joint in the middle of its top; one that starts below the hip
				// (under a skirt) goes on up along its own axis to the hip line.
				val sign = if (side == Side.LEFT) 1f else -1f
				val hip: Pair<Float, Float> = when {
					// Drawn from the hip: the middle of the top of the art says where it is.
					cap != null && cap.y <= hintY + legHeight * 0.08f -> cap.x to cap.y
					// Hidden under a skirt or a coat there is nothing to read: the hips are as far from the spine as the legs'
					// tops say on average, one each side, just under the hip line.
					else -> (centerX + sign * hipOut) to hintY
				}
				val hipRoot = Anatomy.hip(hip, centerX, hipY, torsoHeight, if (side == Side.LEFT) 1f else -1f, body.shoulderHalf)
				val tree = found.trace(hipRoot, cap != null && hipRoot.first == cap.x && hipRoot.second == cap.y, forks = false, centeredTip = true) ?: continue
				val line = tree.trunk
				val (hx, hy) = line.at(0.0)
				val ankleAt = shoeMouth?.let { line.nearest(it.first, it.second) } ?: 0.92
				val a = line.at(ankleAt)
				// Thigh and shin are about the same length: the knee is where hip-to-knee equals knee-to-ankle.
				val even = (0..100).map { ankleAt * it / 100.0 }.minBy { f ->
					val p = line.at(f)
					abs(hypot(p.first - hx, p.second - hy) - hypot(a.first - p.first, a.second - p.second)).toDouble()
				}
				val drawnKnee = foldCorner(tree.trunkVertices, hx, hy, a.first, a.second)
					?: line.at(line.jointNear((even - 0.15).coerceAtLeast(0.05), (even + 0.15).coerceAtMost(ankleAt - 0.05), even))
				val k = if (Anatomy.hinge(hx to hy, drawnKnee, a, 0.35f, 0.65f)) drawnKnee else line.at(even)
				val dir = direction(hx)
				bones += SkeletonBone("leg_upper_$s", SkeletonNames.bone(BoneRole.THIGH, side), LOWER_BODY, BoneRole.THIGH, side,
					hx, hy, k.first, k.second, leg.bind, direction = dir)
				bones += SkeletonBone("leg_lower_$s", SkeletonNames.bone(BoneRole.SHIN, side), "leg_upper_$s", BoneRole.SHIN, side,
					k.first, k.second, a.first, a.second, direction = dir)
				parent = "leg_lower_$s"
				ankle = a
				// Legwear that goes on past the ankle already draws the foot.
				if (foot == null && ankleAt < 0.99) {
					val toe = line.at(1.0)
					bones += SkeletonBone("foot_$s", SkeletonNames.bone(BoneRole.FOOT, side), parent, BoneRole.FOOT, side,
						a.first, a.second, toe.first, toe.second, direction = dir)
				}
			}
			if (foot != null && footPoints != null && shoeMouth != null) {
				val from = ankle ?: shoeMouth
				val count = footPoints.size / 2
				val far = (0 until count).sortedByDescending { hypot(footPoints[it * 2] - from.first, footPoints[it * 2 + 1] - from.second) }
				val toe = mean(footPoints, far.take((count * 0.05f).toInt().coerceAtLeast(1)))
				bones += SkeletonBone("foot_$s", SkeletonNames.bone(BoneRole.FOOT, side), parent, BoneRole.FOOT, side,
					from.first, from.second, toe.first, toe.second, foot.bind, direction = direction(from.first))
			}
		}

		// Tail: rooted where it leaves the body, split into equal lengths along its medial line, one flexible chain that bends
		// as a curve. A tail that forks (a fish's flukes, a second tail) puts a branch at the fork.
		val tails = tagged(SemanticTag.TAIL)
		if (tails.isNotEmpty()) {
			val fit = tails.filter { it.placesJoints }.ifEmpty { tails }.map { it.points to it.indices }
			val found = Anchor.of(fit, centerX, hipY, torsoHeight * 0.3f)
			val capRoot = found?.capRoot()
			found?.trace(capRoot ?: (centerX to hipY), capRoot != null, forks = true, centeredTip = true)?.let { tree ->
				bones += tailBones(tree, tree.trunk.at(0.0), torsoHeight, tails.map { it.drawableId })
			}
		}

		// Wings: rooted between the shoulder blades, one bone out to the wing's far edge, a branch where it forks.
		for ((side, wing) in bySide(SemanticTag.WINGS)) {
			if (wing.fit.isEmpty()) continue
			val hintX = centerX + (if (side == Side.LEFT) 1f else -1f) * body.shoulderHalf * 0.3f
			val hintY = shoulderY + torsoHeight * 0.25f
			val found = Anchor.of(wing.fit, hintX, hintY, torsoHeight * 0.3f) ?: continue
			val capRoot = found.capRoot()
			val tree = found.trace(capRoot ?: (hintX to hintY), capRoot != null, forks = true, minLobe = 0.2) ?: continue
			val root = tree.trunk.at(0.0)
			bones += wingBones(tree, root, side, wing.bind, direction(root.first))
		}
		return SkeletonSpec(enabled = true, bones = bones)
	}

	/** Forks closer than this to the root or the tip of the trunk are not branches: the root's are separate limbs, the tip's bumps. */
	private const val FORK_MARGIN = 0.08

	/** The fork fractions along [tree]'s trunk, nearest the root first, with the lobe each belongs to. */
	private fun forkCuts(tree: LimbTree): List<Pair<Double, LimbFork>> = tree.forks
		.map { tree.trunk.fractionAtReach(it.reachAt) to it }
		.filter { it.first < 1.0 - FORK_MARGIN }
		.sortedBy { it.first }

	/**
	 * The tail chain: [count] flexible bones along the trunk with joints in its turns, and one or two bones along each
	 * lobe from the bone that ends where it splits off. A lobe that starts at the root is a second tail on the hips.
	 */
	private fun tailBones(tree: LimbTree, root: Pair<Float, Float>, torsoHeight: Float, meshes: List<String>): List<SkeletonBone> {
		val trunk = tree.trunk
		val count = if (trunk.length > torsoHeight * 1.2f) 4 else 3
		val cuts = forkCuts(tree)
		val rooted = cuts.filter { it.first < FORK_MARGIN }
		val branched = cuts.filter { it.first >= FORK_MARGIN }
		// Trunk segments between forks, each with its share of the bones and its joints in its own turns.
		val marks = listOf(0.0) + branched.map { it.first } + 1.0
		val trunkEnds = mutableListOf(0.0)
		val forkEnd = ArrayList<Int>()
		for (i in 0 until marks.size - 1) {
			val from = marks[i]; val to = marks[i + 1]
			val n = Math.round(count * (to - from)).toInt().coerceAtLeast(1)
			if (n > 1) trunkEnds += trunk.slice(from, to).joints(n).map { from + (to - from) * it }
			trunkEnds += to
			if (i < branched.size) forkEnd += trunkEnds.size - 2
		}
		val out = mutableListOf<SkeletonBone>()
		var parent = LOWER_BODY
		for (i in 1 until trunkEnds.size) {
			val head = if (i == 1) root else trunk.at(trunkEnds[i - 1])
			val tail = trunk.at(trunkEnds[i])
			out += SkeletonBone("tail_$i", SkeletonNames.bone(BoneRole.TAIL, Side.NONE, i), parent, BoneRole.TAIL, Side.NONE,
				head.first, head.second, tail.first, tail.second, if (i == 1) meshes else emptyList(), chainIndex = i, direction = 1f, flex = true)
			parent = "tail_$i"
		}
		fun lobe(k: Int, fork: LimbFork, parentBone: SkeletonBone?, from: Pair<Float, Float>) {
			val n = if (fork.line.length > trunk.length * 0.35f) 2 else 1
			val ends = listOf(0.0) + (if (n > 1) fork.line.joints(n) else emptyList()) + 1.0
			var up = parentBone?.id ?: LOWER_BODY
			val base = parentBone?.chainIndex ?: 0
			for (j in 1..n) {
				val head = if (j == 1) from else fork.line.at(ends[j - 1])
				val tail = fork.line.at(ends[j])
				val index = base + j
				val id = "tail_f${k}_$j"
				out += SkeletonBone(id, SkeletonNames.bone(BoneRole.TAIL, Side.NONE, index) + " F$k", up, BoneRole.TAIL, Side.NONE,
					head.first, head.second, tail.first, tail.second, emptyList(), chainIndex = index, direction = 1f,
					parameterOverride = "ParamTail${index}F$k", flex = true)
				up = id
			}
		}
		var lobes = 0
		// A fork at the root: another tail, hanging from the hips on its own.
		for ((_, fork) in rooted) lobe(++lobes, fork, null, fork.line.at(0.0))
		branched.forEachIndexed { i, (_, fork) ->
			val parentBone = out[forkEnd[i]]
			lobe(++lobes, fork, parentBone, parentBone.tailX to parentBone.tailY)
		}
		return out
	}

	/** The wing: a bone from the root to the wing's far edge, cut at each fork with a bone out to each lobe's far edge. */
	private fun wingBones(tree: LimbTree, root: Pair<Float, Float>, side: Side, bind: List<String>, direction: Float): List<SkeletonBone> {
		val trunk = tree.trunk
		val s = side.name.first().lowercase()
		val cuts = forkCuts(tree).filter { it.first >= FORK_MARGIN }
		val marks = listOf(0.0) + cuts.map { it.first } + 1.0
		val out = mutableListOf<SkeletonBone>()
		var parent = UPPER_BODY
		for (i in 0 until marks.size - 1) {
			val head = if (i == 0) root else trunk.at(marks[i])
			val tail = trunk.at(marks[i + 1])
			val id = if (i == 0) "wing_$s" else "wing_${s}_$i"
			out += SkeletonBone(id, SkeletonNames.bone(BoneRole.WING, side) + (if (i == 0) "" else " ${i + 1}"), parent, BoneRole.WING, side,
				head.first, head.second, tail.first, tail.second, if (i == 0) bind else emptyList(), direction = direction,
				parameterOverride = if (i == 0) null else "ParamWing${s.uppercase()}${i + 1}")
			parent = id
		}
		cuts.forEachIndexed { k, (_, fork) ->
			val up = out[k]
			val tip = fork.line.at(1.0)
			out += SkeletonBone("wing_${s}_f${k + 1}", SkeletonNames.bone(BoneRole.WING, side) + " F${k + 1}", up.id, BoneRole.WING, side,
				up.tailX, up.tailY, tip.first, tip.second, emptyList(), direction = direction,
				parameterOverride = "ParamWing${s.uppercase()}F${k + 1}")
		}
		return out
	}

	/**
	 * What a human body allows, as the auto skeleton checks its readings of the art against: shoulders and hips are a
	 * plausible distance from the spine and height from the shoulder and hip lines (perspective and a lean move them
	 * by a fraction of that, not past it), the two sides about alike, and an elbow or a knee divides its limb into
	 * lengths in human proportion and bends the one way a hinge does.
	 */
	private object Anatomy {
		/** [point] held to a shoulder's place: on the [sign] side of the spine (+1 for the figure's left), a half to one and a half of the expected [half] width out, from a little above the shoulder line to a third of the torso below it. */
		fun shoulder(point: Pair<Float, Float>, centerX: Float, shoulderY: Float, torso: Float, half: Float, sign: Float): Pair<Float, Float> {
			val out = ((point.first - centerX) * sign).coerceIn(half * 0.55f, half * 1.45f)
			return (centerX + out * sign) to point.second.coerceIn(shoulderY - torso * 0.1f, shoulderY + torso * 0.3f)
		}

		/** The same for a hip: about half as far from the spine as a shoulder, from just above the hip line to a third of the torso below it. */
		fun hip(point: Pair<Float, Float>, centerX: Float, hipY: Float, torso: Float, sign: Float, shoulderHalf: Float): Pair<Float, Float> {
			val out = ((point.first - centerX) * sign).coerceIn(shoulderHalf * 0.15f, shoulderHalf * 0.95f)
			return (centerX + out * sign) to point.second.coerceIn(hipY - torso * 0.05f, hipY + torso * 0.35f)
		}

		/**
		 * The two sides' joints about as far from the spine and as high as each other, allowing for a figure turned or leaning
		 * (up to half as far again): past that, a side read off the art ([drawn]) is believed over one that is only the
		 * default and the default is put where the mirror of the other says; with both or neither read, the side nearer the
		 * pair's mean is believed. Alone, a side stands as it is.
		 */
		fun symmetric(joints: Map<Side, Pair<Float, Float>>, drawn: Set<Side>, centerX: Float): Map<Side, Pair<Float, Float>> {
			val left = joints[Side.LEFT] ?: return joints
			val right = joints[Side.RIGHT] ?: return joints
			val a = left.first - centerX; val b = centerX - right.first
			if (a <= 1f || b <= 1f) return joints
			if (maxOf(a, b) / minOf(a, b) <= 1.5f && abs(left.second - right.second) <= 0.2f * (a + b)) return joints
			val trustLeft = when {
				Side.LEFT in drawn && Side.RIGHT !in drawn -> true
				Side.RIGHT in drawn && Side.LEFT !in drawn -> false
				else -> abs(a - (a + b) / 2) <= abs(b - (a + b) / 2)
			}
			val x = if (trustLeft) a else b
			val y = if (trustLeft) left.second else right.second
			return mapOf(Side.LEFT to (centerX + x to y), Side.RIGHT to (centerX - x to y))
		}

		/**
		 * Whether a hinge at [joint] between [root] and [end] is a human one: the upper part [low] to [high] of the whole,
		 * and not folded back through itself (the two parts further apart than about 30 degrees).
		 */
		fun hinge(root: Pair<Float, Float>, joint: Pair<Float, Float>, end: Pair<Float, Float>, low: Float, high: Float): Boolean {
			val upper = hypot(joint.first - root.first, joint.second - root.second)
			val lower = hypot(end.first - joint.first, end.second - joint.second)
			if (upper + lower < 1f) return false
			if (upper / (upper + lower) !in low..high) return false
			val cos = ((joint.first - root.first) * (end.first - joint.first) + (joint.second - root.second) * (end.second - joint.second)) / (upper * lower).coerceAtLeast(1e-3f)
			return cos > -0.87f
		}
	}

	/**
	 * The joint of a limb bent between root ([rx], [ry]) and end ([ex], [ey]), or null when it is about
	 * straight. The outer corner of a fold is the drawn point that makes the path root-corner-end longest;
	 * the joint sits inside it, at the middle of the limb there. A limb folded back on itself defeats any
	 * line traced through the mesh - the two halves touch - but not this.
	 */
	fun foldCorner(points: FloatArray, rx: Float, ry: Float, ex: Float, ey: Float): Pair<Float, Float>? {
		val count = points.size / 2
		if (count < 3) return null
		val straight = hypot(ex - rx, ey - ry)
		if (straight < 1f) return null
		var best = -1
		var bestPath = 0f
		// Near either end the path is long for the wrong reason: the width of a shoulder, or a foot past
		// the ankle. A joint is well inside the limb.
		val clearance = straight * 0.25f
		for (i in 0 until count) {
			val x = points[i * 2]
			val y = points[i * 2 + 1]
			val fromRoot = hypot(x - rx, y - ry)
			val toEnd = hypot(ex - x, ey - y)
			if (fromRoot < clearance || toEnd < clearance) continue
			val path = fromRoot + toEnd
			if (path > bestPath) { bestPath = path; best = i }
		}
		if (best < 0 || bestPath < straight * FOLD_RATIO) return null
		val cx = points[best * 2]
		val cy = points[best * 2 + 1]
		// The corner is on the outline; the joint is the middle of the limb around it.
		val radius = bestPath * 0.12f
		val near = (0 until count).filter { hypot(points[it * 2] - cx, points[it * 2 + 1] - cy) <= radius }
		return mean(points, near)
	}

	/**
	 * A limb's medial line: from its root, the mean of the vertices at each distance travelled inside the
	 * mesh. Positions along it are fractions of its arc length.
	 */
	class Medial(
		private val points: List<Pair<Float, Float>>,
		/** Distance through the art from the root at each point, when the line was traced through a mesh. */
		private val reach: List<Double>? = null,
		/** Width of the art across the line at each point, when known. */
		private val widths: List<Double>? = null,
	) {
		private val cumulative = DoubleArray(points.size).also { c ->
			for (i in 1 until points.size) {
				c[i] = c[i - 1] + hypot((points[i].first - points[i - 1].first).toDouble(), (points[i].second - points[i - 1].second).toDouble())
			}
		}
		val length: Float get() = cumulative.last().toFloat()

		/** The point where the art is [distance] through it from the root. */
		fun pointAtReach(distance: Double): Pair<Float, Float> = at(fractionAtReach(distance))

		/** The fraction of the line's length at which the art is [distance] through it from the root. */
		fun fractionAtReach(distance: Double): Double {
			val r = reach ?: return (distance / cumulative.last().coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
			if (cumulative.last() <= 0.0) return 0.0
			val i = (1 until r.size).firstOrNull { r[it] >= distance } ?: return 1.0
			val span = r[i] - r[i - 1]
			val t = if (span <= 1e-9) 0.0 else ((distance - r[i - 1]) / span).coerceIn(0.0, 1.0)
			return (cumulative[i - 1] + (cumulative[i] - cumulative[i - 1]) * t) / cumulative.last()
		}

		/** The part of the line between fractions [from] and [to], as a line of its own. */
		fun slice(from: Double, to: Double): Medial {
			val a = from.coerceIn(0.0, 1.0); val b = to.coerceIn(a, 1.0)
			val total = cumulative.last()
			val inner = points.indices.filter { cumulative[it] > a * total + 1e-6 && cumulative[it] < b * total - 1e-6 }
			val picked = listOf(at(a)) + inner.map { points[it] } + at(b)
			val w = widths?.let { widths -> listOf(widthAt(a)) + inner.map { widths[it] } + widthAt(b) }
			return Medial(picked, null, w)
		}

		/** The middle width of the art along the line, pixels; 0 when not known. */
		fun medianWidth(): Double {
			val w = widths ?: return 0.0
			if (w.size < 3) return 0.0
			return w.subList(w.size / 5, (w.size * 4 / 5).coerceAtLeast(w.size / 5 + 1)).sorted().let { it[it.size / 2] }
		}

		/**
		 * ([x], [y]) moved across the line onto the axis the line has through its first part - a straight line fitted to
		 * the slice centres from a tenth to half of the way along, clear of the fan the slices make around a root
		 * seeded at a corner. A joint whose place along the limb is known goes on the limb; null when it is further off the
		 * axis than half the limb is wide: a limb that bends has no one axis, and the hint stays where it is.
		 */
		fun snap(x: Float, y: Float, limit: Float = Float.MAX_VALUE): Pair<Float, Float>? {
			// The slices only: the first point is the root itself and the last the tip.
			val slices = points.size - 2
			if (slices < 3) return null
			val inner = (1..slices).filter { it > slices * 0.1 && it <= slices * 0.5 + 1 }.ifEmpty { listOf(1, 2) }
			val mx = inner.sumOf { points[it].first.toDouble() } / inner.size
			val my = inner.sumOf { points[it].second.toDouble() } / inner.size
			var sxx = 0.0; var sxy = 0.0; var syy = 0.0
			for (i in inner) {
				val dx = points[i].first - mx; val dy = points[i].second - my
				sxx += dx * dx; sxy += dx * dy; syy += dy * dy
			}
			val angle = 0.5 * Math.atan2(2 * sxy, sxx - syy)
			val ux = Math.cos(angle); val uy = Math.sin(angle)
			// Only across the limb: the foot of the perpendicular keeps the hint's own place along it.
			val along = (x - mx) * ux + (y - my) * uy
			val fx = mx + ux * along; val fy = my + uy * along
			val reach = minOf(medianWidth() * 0.45, limit.toDouble())
			return if (hypot(x - fx, y - fy) > reach) null else fx.toFloat() to fy.toFloat()
		}

		/** How wide the art is across the line at fraction [fraction] of its length, pixels; 0 when not known. */
		fun widthAt(fraction: Double): Double {
			val w = widths ?: return 0.0
			if (points.size == 1 || cumulative.last() <= 0.0) return w.first()
			val target = fraction.coerceIn(0.0, 1.0) * cumulative.last()
			val i = (1 until points.size).firstOrNull { cumulative[it] >= target } ?: (points.size - 1)
			val span = cumulative[i] - cumulative[i - 1]
			val t = if (span <= 0.0) 0.0 else (target - cumulative[i - 1]) / span
			return w[i - 1] + (w[i] - w[i - 1]) * t
		}

		fun at(fraction: Double): Pair<Float, Float> {
			if (points.size == 1 || cumulative.last() <= 0.0) return points.first()
			val target = fraction.coerceIn(0.0, 1.0) * cumulative.last()
			val i = (1 until points.size).firstOrNull { cumulative[it] >= target } ?: (points.size - 1)
			val span = cumulative[i] - cumulative[i - 1]
			val t = if (span <= 0.0) 0.0 else (target - cumulative[i - 1]) / span
			return (points[i - 1].first + (points[i].first - points[i - 1].first) * t).toFloat() to
				(points[i - 1].second + (points[i].second - points[i - 1].second) * t).toFloat()
		}

		/**
		 * Where [count] bones along the line put their joints, as fractions: the line's own points that keep every
		 * bone's chord within the fewest pixels of the line it spans, so a tail that turns back on itself gets a joint
		 * in each turn instead of a chord cutting across it. Among choices that stay within [tolerance] of the line
		 * (or are equally far off it), the one with the most even lengths, none shorter than [shortest] of the line.
		 */
		fun joints(count: Int, tolerance: Double = 2.0, shortest: Double = 0.08): List<Double> {
			val total = cumulative.last()
			val even = (1 until count).map { it.toDouble() / count }
			if (count < 2 || total <= 0.0 || points.size < 3) return even
			val candidates = (1 until points.size - 1).filter { cumulative[it] / total in shortest..(1 - shortest) }
			fun deviation(from: Int, to: Int): Double {
				val ax = points[from].first.toDouble(); val ay = points[from].second.toDouble()
				val dx = points[to].first - ax; val dy = points[to].second - ay
				val length2 = dx * dx + dy * dy
				var worst = 0.0
				for (i in from + 1 until to) {
					val px = points[i].first - ax; val py = points[i].second - ay
					val t = if (length2 < 1e-9) 0.0 else ((px * dx + py * dy) / length2).coerceIn(0.0, 1.0)
					worst = maxOf(worst, hypot(px - dx * t, py - dy * t))
				}
				return worst
			}
			var best: List<Int>? = null
			var bestCost = Double.MAX_VALUE to Double.MAX_VALUE
			fun visit(chosen: List<Int>) {
				if (chosen.size == count - 1) {
					val ends = listOf(0) + chosen + (points.size - 1)
					val lengths = ends.zipWithNext { a, b -> (cumulative[b] - cumulative[a]) / total }
					if (lengths.any { it < shortest }) return
					val off = (ends.zipWithNext { a, b -> deviation(a, b) }.max() - tolerance).coerceAtLeast(0.0)
					val cost = Math.round(off * 2) / 2.0 to lengths.sumOf { (it - 1.0 / count) * (it - 1.0 / count) }
					if (cost.first < bestCost.first || (cost.first == bestCost.first && cost.second < bestCost.second)) { bestCost = cost; best = chosen }
					return
				}
				for (c in candidates) if (chosen.isEmpty() || c > chosen.last()) visit(chosen + c)
			}
			visit(emptyList())
			val joints = best ?: return even
			// A line that stays straight enough is cut evenly, as it always was.
			if ((listOf(0.0) + even + 1.0).zipWithNext { a, b -> chordDeviation(a, b) }.max() <= tolerance) return even
			return joints.map { cumulative[it] / total }
		}

		/** How far the line between fractions [a] and [b] strays from the chord between them, pixels. */
		private fun chordDeviation(a: Double, b: Double): Double {
			val p = at(a); val q = at(b)
			val dx = (q.first - p.first).toDouble(); val dy = (q.second - p.second).toDouble()
			val length2 = dx * dx + dy * dy
			return (0..32).maxOf { k ->
				val r = at(a + (b - a) * k / 32.0)
				val px = (r.first - p.first).toDouble(); val py = (r.second - p.second).toDouble()
				val t = if (length2 < 1e-9) 0.0 else ((px * dx + py * dy) / length2).coerceIn(0.0, 1.0)
				hypot(px - dx * t, py - dy * t)
			}
		}

		/** The fraction along the line closest to ([x], [y]). */
		fun nearest(x: Float, y: Float): Double = (0..200).map { it / 200.0 }.minBy { f ->
			val p = at(f)
			hypot((p.first - x).toDouble(), (p.second - y).toDouble())
		}

		/**
		 * Where a joint goes between fractions [low] and [high]: at a clear bend of the line there
		 * (at least [MIN_BEND_DEGREES]), otherwise at the proportional point [fallback].
		 *
		 * Bends are weighed by how close they sit to [fallback], so the kink a puffed sleeve makes at the
		 * shoulder does not outbid the elbow where the proportions expect one.
		 */
		fun jointNear(low: Double, high: Double, fallback: Double): Double {
			if (high <= low || cumulative.last() <= 0.0) return fallback.coerceIn(0.0, 1.0)
			// The bend is measured between points an eighth of the limb before and after, so the wobble
			// of the vertex averages does not read as a joint.
			val reach = 0.12
			var best = fallback
			var bestScore = 0.0
			for (step in 0..60) {
				val f = low + (high - low) * step / 60.0
				val a = at(f - reach)
				val b = at(f)
				val c = at(f + reach)
				val ux = (b.first - a.first).toDouble()
				val uy = (b.second - a.second).toDouble()
				val vx = (c.first - b.first).toDouble()
				val vy = (c.second - b.second).toDouble()
				val lu = hypot(ux, uy)
				val lv = hypot(vx, vy)
				if (lu < 1e-6 || lv < 1e-6) continue
				val bend = Math.toDegrees(acos(((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1.0, 1.0)))
				if (bend < MIN_BEND_DEGREES) continue
				val offset = (f - fallback) / 0.15
				val score = bend * exp(-offset * offset)
				if (score > bestScore) { bestScore = score; best = f }
			}
			return best.coerceIn(0.0, 1.0)
		}
	}

	private fun mean(points: FloatArray, indices: List<Int>): Pair<Float, Float> {
		var x = 0f; var y = 0f
		for (i in indices) { x += points[i * 2]; y += points[i * 2 + 1] }
		val n = indices.size.coerceAtLeast(1)
		return (x / n) to (y / n)
	}

	private fun meanX(points: FloatArray): Float {
		var x = 0f
		for (i in 0 until points.size / 2) x += points[i * 2]
		return x / (points.size / 2).coerceAtLeast(1)
	}

	private fun concat(arrays: List<FloatArray>): FloatArray {
		val out = FloatArray(arrays.sumOf { it.size })
		var offset = 0
		for (a in arrays) { a.copyInto(out, offset); offset += a.size }
		return out
	}
}
