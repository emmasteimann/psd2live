package io.github.psd2live.core

/**
 * java.awt.Rectangle for shared code (Layerloom iPad plan, phase 5): a mutable integer rectangle whose methods are
 * ported line for line from OpenJDK 21's Rectangle (union/add/intersection's long arithmetic and clamping included),
 * so paint and raster code computes the same regions on the desktop and the iPad. Pinned to java.awt.Rectangle by
 * RectangleParityTest. The desktop converts at its AWT edges (toAwt / toShared).
 */
class Rectangle(var x: Int = 0, var y: Int = 0, var width: Int = 0, var height: Int = 0) {
	constructor(r: Rectangle) : this(r.x, r.y, r.width, r.height)
	constructor(width: Int, height: Int) : this(0, 0, width, height)

	val minX: Double get() = x.toDouble()
	val minY: Double get() = y.toDouble()
	val maxX: Double get() = x.toDouble() + width.toDouble()
	val maxY: Double get() = y.toDouble() + height.toDouble()
	val bounds: Rectangle get() = Rectangle(x, y, width, height)

	fun setBounds(x: Int, y: Int, width: Int, height: Int) = reshape(x, y, width, height)
	fun setBounds(r: Rectangle) = reshape(r.x, r.y, r.width, r.height)
	private fun reshape(x: Int, y: Int, width: Int, height: Int) { this.x = x; this.y = y; this.width = width; this.height = height }

	fun isEmpty(): Boolean = width <= 0 || height <= 0

	fun contains(x: Int, y: Int): Boolean = inside(x, y)

	fun inside(X: Int, Y: Int): Boolean {
		var w = width; var h = height
		if ((w or h) < 0) return false
		val x = this.x; val y = this.y
		if (X < x || Y < y) return false
		w += x; h += y
		return (w < x || w > X) && (h < y || h > Y)
	}

	fun contains(r: Rectangle): Boolean = contains(r.x, r.y, r.width, r.height)

	fun contains(X: Int, Y: Int, W0: Int, H0: Int): Boolean {
		var w = width; var h = height
		var W = W0; var H = H0
		if ((w or h or W or H) < 0) return false
		val x = this.x; val y = this.y
		if (X < x || Y < y) return false
		w += x; W += X
		if (W <= X) { if (w >= x || W > w) return false } else { if (w >= x && W > w) return false }
		h += y; H += Y
		if (H <= Y) { if (h >= y || H > h) return false } else { if (h >= y && H > h) return false }
		return true
	}

	fun intersects(r: Rectangle): Boolean {
		var tw = width; var th = height; var rw = r.width; var rh = r.height
		if (rw <= 0 || rh <= 0 || tw <= 0 || th <= 0) return false
		val tx = x; val ty = y; val rx = r.x; val ry = r.y
		rw += rx; rh += ry; tw += tx; th += ty
		return (rw < rx || rw > tx) && (rh < ry || rh > ty) && (tw < tx || tw > rx) && (th < ty || th > ry)
	}

	fun intersection(r: Rectangle): Rectangle {
		var tx1 = x; var ty1 = y
		val rx1 = r.x; val ry1 = r.y
		var tx2 = tx1.toLong(); tx2 += width
		var ty2 = ty1.toLong(); ty2 += height
		var rx2 = rx1.toLong(); rx2 += r.width
		var ry2 = ry1.toLong(); ry2 += r.height
		if (tx1 < rx1) tx1 = rx1
		if (ty1 < ry1) ty1 = ry1
		if (tx2 > rx2) tx2 = rx2
		if (ty2 > ry2) ty2 = ry2
		tx2 -= tx1; ty2 -= ty1
		if (tx2 < Int.MIN_VALUE) tx2 = Int.MIN_VALUE.toLong()
		if (ty2 < Int.MIN_VALUE) ty2 = Int.MIN_VALUE.toLong()
		return Rectangle(tx1, ty1, tx2.toInt(), ty2.toInt())
	}

	fun union(r: Rectangle): Rectangle {
		var tx2 = width.toLong(); var ty2 = height.toLong()
		if ((tx2 or ty2) < 0) return Rectangle(r)
		var rx2 = r.width.toLong(); var ry2 = r.height.toLong()
		if ((rx2 or ry2) < 0) return Rectangle(this)
		var tx1 = x; var ty1 = y
		tx2 += tx1; ty2 += ty1
		val rx1 = r.x; val ry1 = r.y
		rx2 += rx1; ry2 += ry1
		if (tx1 > rx1) tx1 = rx1
		if (ty1 > ry1) ty1 = ry1
		if (tx2 < rx2) tx2 = rx2
		if (ty2 < ry2) ty2 = ry2
		tx2 -= tx1; ty2 -= ty1
		if (tx2 > Int.MAX_VALUE) tx2 = Int.MAX_VALUE.toLong()
		if (ty2 > Int.MAX_VALUE) ty2 = Int.MAX_VALUE.toLong()
		return Rectangle(tx1, ty1, tx2.toInt(), ty2.toInt())
	}

	fun add(newx: Int, newy: Int) {
		if ((width or height) < 0) { x = newx; y = newy; width = 0; height = 0; return }
		var x1 = x; var y1 = y
		var x2 = width.toLong(); var y2 = height.toLong()
		x2 += x1; y2 += y1
		if (x1 > newx) x1 = newx
		if (y1 > newy) y1 = newy
		if (x2 < newx) x2 = newx.toLong()
		if (y2 < newy) y2 = newy.toLong()
		x2 -= x1; y2 -= y1
		if (x2 > Int.MAX_VALUE) x2 = Int.MAX_VALUE.toLong()
		if (y2 > Int.MAX_VALUE) y2 = Int.MAX_VALUE.toLong()
		reshape(x1, y1, x2.toInt(), y2.toInt())
	}

	/** As OpenJDK's: an empty rectangle takes [r]'s bounds, then the (stale) widths carry on. */
	fun add(r: Rectangle) {
		var tx2 = width.toLong(); var ty2 = height.toLong()
		if ((tx2 or ty2) < 0) reshape(r.x, r.y, r.width, r.height)
		var rx2 = r.width.toLong(); var ry2 = r.height.toLong()
		if ((rx2 or ry2) < 0) return
		var tx1 = x; var ty1 = y
		tx2 += tx1; ty2 += ty1
		val rx1 = r.x; val ry1 = r.y
		rx2 += rx1; ry2 += ry1
		if (tx1 > rx1) tx1 = rx1
		if (ty1 > ry1) ty1 = ry1
		if (tx2 < rx2) tx2 = rx2
		if (ty2 < ry2) ty2 = ry2
		tx2 -= tx1; ty2 -= ty1
		if (tx2 > Int.MAX_VALUE) tx2 = Int.MAX_VALUE.toLong()
		if (ty2 > Int.MAX_VALUE) ty2 = Int.MAX_VALUE.toLong()
		reshape(tx1, ty1, tx2.toInt(), ty2.toInt())
	}

	fun translate(dx: Int, dy: Int) { x += dx; y += dy }

	override fun equals(other: Any?): Boolean = other is Rectangle && x == other.x && y == other.y && width == other.width && height == other.height

	/** Rectangle2D.hashCode: the coordinates' double bits, mixed as Java mixes them. */
	override fun hashCode(): Int {
		var bits = x.toDouble().toBits()
		bits += y.toDouble().toBits() * 37
		bits += width.toDouble().toBits() * 43
		bits += height.toDouble().toBits() * 47
		return bits.toInt() xor (bits shr 32).toInt()
	}

	override fun toString(): String = "java.awt.Rectangle[x=$x,y=$y,width=$width,height=$height]"
}
