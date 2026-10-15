package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The paint engine lands the same pixels on the iPad as on the Mac: one scripted session - brush, eraser,
 * bucket, undo, redo, a jump back - hashed after every step, against the hashes the desktop produces.
 */
class PaintEngineParityTest {
	private fun hash(image: RigImage): Long {
		var h = -0x340d631b7bdddcdbL
		for (p in image.readArgb(0, 0, image.width, image.height)) { h = (h xor (p.toLong() and 0xFFFFFFFFL)) * 0x100000001b3L }
		return h
	}

	private fun seeded(): RigImage = newRigImage(200, 150).also { image ->
		val pixels = IntArray(200 * 150) { i ->
			val x = i % 200; val y = i / 200
			if (x in 40..150 && y in 30..110) (0xFF shl 24) or ((x * 2) shl 16) or ((y * 2) shl 8) or 0x40 else 0
		}
		image.writeArgb(0, 0, 200, 150, pixels)
	}

	private fun copy(image: RigImage) = newRigImage(image.width, image.height).also {
		it.writeArgb(0, 0, image.width, image.height, image.readArgb(0, 0, image.width, image.height))
	}

	private fun stroke(session: PaintRasterSession, points: List<Pair<Float, Float>>, tip: RasterPaintEngine.Tip,
		color: Int, opacity: Float, erase: Boolean, name: String) {
		session.beginStroke()
		for (i in 1 until points.size) {
			val (x0, y0) = points[i - 1]; val (x1, y1) = points[i]
			session.stroke().addSegment(x0, y0, x1, y1, tip)?.let { session.landSegment(it, color, opacity, erase) }
		}
		session.recordStroke(name)
	}

	@Test
	fun aScriptedSessionLandsTheDesktopsPixels() {
		val work = seeded()
		val session = PaintRasterSession("layer", "Layer", work, copy(work))
		val hashes = mutableListOf(hash(work))
		stroke(session, listOf(10f to 10f, 60.5f to 40.25f, 120f to 35f, 180f to 140f), RasterPaintEngine.Tip(9f, 0.4f),
			0xCC3366AA.toInt(), 0.8f, false, "brush")
		hashes += hash(work)
		stroke(session, listOf(30f to 120f, 170f to 20f, 175f to 25f), RasterPaintEngine.Tip(5.5f, 1f, antialias = true),
			0xFF000000.toInt(), 0.6f, true, "eraser")
		hashes += hash(work)
		// As the workspace does: the fill announces the area it is about to write before writing it.
		RasterPaintEngine.floodFill(work, 5, 140, 0xFF22AA44.toInt(), 12, Rectangle(0, 0, 200, 150),
			before = { session.willWrite(it) }, opacity = 0.75f)
		session.recordStroke("bucket")
		hashes += hash(work)
		session.undo(); hashes += hash(work)
		session.redo(); hashes += hash(work)
		session.jumpToStroke(1); hashes += hash(work)
		session.discard(); hashes += hash(work)
		assertEquals(hashes[0], hashes.last(), "discard gives back the starting raster")
		assertEquals(hashes[2], hashes[4], "undoing the bucket gives back the eraser's raster")
		assertEquals(hashes[3], hashes[5], "redo lands the bucket again")
		assertEquals(hashes[1], hashes[6], "a jump to the first stroke")
		assertEquals(DESKTOP, hashes.joinToString(","), "the desktop's pixels, step by step")
	}

	@Test
	fun aShapeStaysInsideItsAreaAndClip() {
		val image = newRigImage(64, 64)
		val clip = Rectangle(0, 0, 40, 64)
		val area = RasterPaintEngine.drawShape(image, 10, 10, 50, 40, RasterPaintShape.ELLIPSE, 0xFF0000FF.toInt(), 1f, 3f, false, clip)!!
		val pixels = image.readArgb(0, 0, 64, 64)
		var painted = 0
		for (y in 0 until 64) for (x in 0 until 64) if (pixels[y * 64 + x] ushr 24 != 0) {
			painted++
			assertTrue(area.contains(x, y) && clip.contains(x, y), "paint at $x,$y is outside the shape's area or clip")
		}
		assertTrue(painted > 40, "the outline is drawn")
	}

	private companion object {
		const val DESKTOP = "-4963770183065967963,-6796660346252288623,3893358456612461969,6942967255309927581,3893358456612461969,6942967255309927581,-6796660346252288623,-4963770183065967963"
	}
}
