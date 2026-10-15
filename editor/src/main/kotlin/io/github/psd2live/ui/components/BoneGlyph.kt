package io.github.psd2live.ui.components
import io.github.psd2live.core.Math

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The one bone look, shared by the canvas armature and every bone icon: a Blender-style octahedron
 * seen from the side - head, two shoulders a short way down, tail - its two facets shaded apart.
 */
class BoneShape(val head: Offset, val tail: Offset, val left: Offset, val right: Offset) {
	fun outline() = Path().apply {
		moveTo(head.x, head.y); lineTo(left.x, left.y); lineTo(tail.x, tail.y); lineTo(right.x, right.y); close()
	}

	fun leftFacet() = Path().apply { moveTo(head.x, head.y); lineTo(left.x, left.y); lineTo(tail.x, tail.y); close() }

	fun rightFacet() = Path().apply { moveTo(head.x, head.y); lineTo(right.x, right.y); lineTo(tail.x, tail.y); close() }

	companion object {
		/** Shoulders at [shoulder] of the length, [width] of the length to each side; null for a degenerate bone. */
		fun of(head: Offset, tail: Offset, shoulder: Float = 0.2f, width: Float = 0.1f): BoneShape? {
			val dx = tail.x - head.x
			val dy = tail.y - head.y
			val length = hypot(dx, dy)
			if (length < 1e-3f) return null
			val c = Offset(head.x + dx * shoulder, head.y + dy * shoulder)
			val n = Offset(-dy * width, dx * width)
			return BoneShape(head, tail, c + n, c - n)
		}
	}
}

/**
 * What a bone is, as far as its look goes: each kind has its own silhouette so the kind reads without color.
 * [LIMB] is the plain octahedron; [FLEX] is a strip of even width (it bends as a curve); [BODY] is a stout half of the
 * torso; [ANCHOR] is the diamond the body hangs from and carries no tail joint.
 */
enum class BoneKind(val shoulder: Float, val width: Float) {
	LIMB(0.18f, 0.09f),
	FLEX(0.18f, 0.09f),
	BODY(0.30f, 0.15f),
	ANCHOR(0.50f, 0.28f),
}

/** Shared single-bone glyph for tree rows, overlay toggles and the mode menu. */
fun DrawScope.drawSingleBoneIcon(tint: Color, kind: BoneKind = BoneKind.LIMB) {
	val w = size.width
	val h = size.height
	drawBoneIcon(Offset(w * 0.16f, h * 0.84f), Offset(w * 0.88f, h * 0.12f), tint, stroke = IconPen(this, tint).px(1.2f), headRadius = w * 0.13f, kind = kind)
}

/** A bone icon: shaded octahedron with a solid head, in [tint] at icon scale. */
fun DrawScope.drawBoneIcon(head: Offset, tail: Offset, tint: Color, stroke: Float, headRadius: Float, kind: BoneKind = BoneKind.LIMB) {
	if (kind == BoneKind.FLEX) {
		val body = RectBody(head, tail, hypot(tail.x - head.x, tail.y - head.y) * 0.13f)
		drawPath(body.leftFacet(), tint.copy(alpha = tint.alpha * 0.45f))
		drawPath(body.outline(), tint, style = Stroke(stroke, join = StrokeJoin.Round))
		drawCircle(tint, headRadius, head)
		return
	}
	val shape = when (kind) {
		BoneKind.LIMB, BoneKind.FLEX -> BoneShape.of(head, tail, shoulder = 0.24f, width = 0.16f)
		BoneKind.BODY -> BoneShape.of(head, tail, shoulder = 0.34f, width = 0.24f)
		BoneKind.ANCHOR -> BoneShape.of(head, tail, shoulder = 0.5f, width = 0.34f)
	} ?: return
	drawPath(shape.leftFacet(), tint.copy(alpha = tint.alpha * 0.45f))
	drawPath(shape.outline(), tint, style = Stroke(stroke, join = StrokeJoin.Round))
	drawCircle(tint, headRadius, head)
}

/**
 * A flexible bone's body: a plain rectangle, [half] to each side of its axis and split down the middle into
 * two shaded faces. The octahedron of a rigid bone swells to shoulders and narrows to a tip; the rectangle is
 * the same width end to end, as a strip that bends.
 */
private class RectBody(val head: Offset, val tail: Offset, half: Float) {
	private val n: Offset
	init {
		val dx = tail.x - head.x
		val dy = tail.y - head.y
		val length = hypot(dx, dy).coerceAtLeast(1e-3f)
		n = Offset(-dy / length * half, dx / length * half)
	}
	fun outline() = Path().apply {
		moveTo(head.x + n.x, head.y + n.y); lineTo(tail.x + n.x, tail.y + n.y)
		lineTo(tail.x - n.x, tail.y - n.y); lineTo(head.x - n.x, head.y - n.y); close()
	}
	fun leftFacet() = Path().apply {
		moveTo(head.x, head.y); lineTo(head.x + n.x, head.y + n.y); lineTo(tail.x + n.x, tail.y + n.y); lineTo(tail.x, tail.y); close()
	}
	fun rightFacet() = Path().apply {
		moveTo(head.x, head.y); lineTo(head.x - n.x, head.y - n.y); lineTo(tail.x - n.x, tail.y - n.y); lineTo(tail.x, tail.y); close()
	}
}

/** The color IK is drawn in everywhere, distinct from the selection's accent and the blend band's yellow. */
val IkGuideColor = Color(0xFFFF5C8A)

/**
 * Shows what IK will do for the selected bone: the chain it may turn is laid over in [color], the root it pivots
 * from is pinned with a square, and a bend direction other than automatic is an arrow arcing at the elbow, the
 * joint that folds. [joints] run root head to effector tail.
 */
fun DrawScope.drawIkGuide(joints: List<Offset>, color: Color, bendDirection: Int) {
	if (joints.size < 2) return
	val path = Path().apply {
		moveTo(joints[0].x, joints[0].y)
		for (i in 1 until joints.size) lineTo(joints[i].x, joints[i].y)
	}
	// A broad glow under the chain, then its spine, so it stands out over the bones and the art alike.
	drawPath(path, color.copy(alpha = 0.30f), style = Stroke(16f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = StrokeJoin.Round))
	drawPath(path, color.copy(alpha = 0.95f), style = Stroke(2.4f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = StrokeJoin.Round))
	// Chevrons along every bone point from the pivot to the tip: the way the pull travels down the chain.
	for (i in 0 until joints.size - 1) {
		val a = joints[i]; val b = joints[i + 1]
		val length = hypot(b.x - a.x, b.y - a.y)
		if (length < 14f) continue
		val ux = (b.x - a.x) / length; val uy = (b.y - a.y) / length
		val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
		val chevron = Path().apply {
			moveTo(mid.x - ux * 4f - uy * 5f, mid.y - uy * 4f + ux * 5f)
			lineTo(mid.x + ux * 4f, mid.y + uy * 4f)
			lineTo(mid.x - ux * 4f + uy * 5f, mid.y - uy * 4f - ux * 5f)
		}
		drawPath(chevron, Color.White, style = Stroke(2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = StrokeJoin.Round))
	}
	// Each joint the solver may turn is a ring.
	for (i in 1 until joints.size - 1) drawCircle(color, 5f, joints[i], style = Stroke(2f))
	// The pivot is pinned with a filled square: nothing above it moves.
	val root = joints.first()
	drawRect(color, Offset(root.x - 6f, root.y - 6f), androidx.compose.ui.geometry.Size(12f, 12f))
	drawRect(Color.White, Offset(root.x - 6f, root.y - 6f), androidx.compose.ui.geometry.Size(12f, 12f), style = Stroke(1.2f))
	// The tip is the handle IK drags: a reticle.
	val tip = joints.last()
	drawCircle(color, 10f, tip, style = Stroke(2.4f))
	drawCircle(color, 3f, tip)
	for ((cx, cy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
		drawLine(color, Offset(tip.x + cx * 8f, tip.y + cy * 8f), Offset(tip.x + cx * 15f, tip.y + cy * 15f), strokeWidth = 2.4f)
	}
	if (bendDirection == 0 || joints.size < 3) return
	// The arrow sweeps round the elbow in the direction the joint folds: clockwise on screen is positive.
	val elbow = joints[joints.size - 2]
	val radius = 14f
	val sign = if (bendDirection > 0) 1f else -1f
	val start = -60f
	val sweep = 240f * sign
	drawArc(color, start, sweep, useCenter = false, topLeft = Offset(elbow.x - radius, elbow.y - radius),
		size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(1.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
	val end = Math.toRadians((start + sweep).toDouble())
	val arrowTip = Offset(elbow.x + radius * kotlin.math.cos(end).toFloat(), elbow.y + radius * kotlin.math.sin(end).toFloat())
	// Tangent along the sweep, for the arrow head.
	val tangent = Offset(-kotlin.math.sin(end).toFloat() * sign, kotlin.math.cos(end).toFloat() * sign)
	val normal = Offset(-tangent.y, tangent.x)
	val head = Path().apply {
		moveTo(arrowTip.x + tangent.x * 5f, arrowTip.y + tangent.y * 5f)
		lineTo(arrowTip.x - tangent.x * 2f + normal.x * 4f, arrowTip.y - tangent.y * 2f + normal.y * 4f)
		lineTo(arrowTip.x - tangent.x * 2f - normal.x * 4f, arrowTip.y - tangent.y * 2f - normal.y * 4f)
		close()
	}
	drawPath(head, color)
}

/**
 * A bone on the canvas, in screen space. Both the armature editor and the pose layer draw through
 * here, so the bones look the same in every mode: shaded facets, a solid head disc for the pivot and
 * a ring on the tail for the joint that moves. [lit] is the selected/hovered bone, [tipLit] its
 * grabbed tail; [halo] rings the joints so they read over the art.
 */
fun DrawScope.drawCanvasBone(
	head: Offset,
	tail: Offset,
	color: Color,
	halo: Color,
	lit: Boolean = false,
	tipLit: Boolean = false,
	strength: Float = 1f,
	kind: BoneKind = BoneKind.LIMB,
	pinned: Boolean = false,
) {
	val length = hypot(tail.x - head.x, tail.y - head.y)
	val tint = color.copy(alpha = color.alpha * strength)
	val edge = if (lit) Color.White.copy(alpha = strength) else tint
	if (length >= 6f) {
		val litFacet = (if (lit) 0.65f else 0.42f) * strength
		val dimFacet = (if (lit) 0.38f else 0.22f) * strength
		val pen = Stroke(if (lit) 1.8f else 1.2f, join = StrokeJoin.Round)
		if (kind == BoneKind.FLEX) {
			val body = RectBody(head, tail, (length * 0.07f).coerceIn(3.5f, 8f) * (if (lit) 1.15f else 1f))
			drawPath(body.leftFacet(), tint.copy(alpha = litFacet))
			drawPath(body.rightFacet(), tint.copy(alpha = dimFacet))
			drawPath(body.outline(), edge, style = pen)
		} else {
			val shoulder = (length * kind.shoulder).coerceIn(5f, 28f * kind.shoulder / 0.18f) / length
			val width = (length * kind.width).coerceIn(3f, 12f * kind.width / 0.09f) / length
			val shape = BoneShape.of(head, tail, shoulder, width)!!
			drawPath(shape.leftFacet(), tint.copy(alpha = litFacet))
			drawPath(shape.rightFacet(), tint.copy(alpha = dimFacet))
			drawPath(shape.outline(), edge, style = pen)
		}
	} else {
		drawLine(edge, head, tail, strokeWidth = if (lit) 2.6f else 1.8f)
	}
	val haloTint = halo.copy(alpha = halo.alpha * strength)
	val headRadius = if (lit) 5f else 4f
	drawCircle(haloTint, headRadius + 1.5f, head)
	drawCircle(edge, headRadius, head)
	drawCircle(tint, headRadius - 1.6f, head)
	// An anchor is only the pivot its body hangs from: no tail joint to grab.
	if (kind == BoneKind.ANCHOR) return
	val tailRadius = if (tipLit) 5.5f else if (lit) 4.4f else 3.8f
	drawCircle(haloTint, tailRadius + 1.2f, tail, style = Stroke(2.4f))
	val ring = if (tipLit) Color.White.copy(alpha = strength) else edge
	drawCircle(ring, tailRadius, tail, style = Stroke(1.6f))
	// A pinned tail has an IK target to chase: a dot in the ring, like the target's own bullseye.
	if (pinned) drawCircle(ring, 1.6f, tail)
}
