package io.github.psd2live.ipad

import io.github.psd2live.format.compile.RasterImage

/**
 * The desktop's avatar pose sheet (src/test/.../AvatarPoseSheetTool) on the iPad: the rig at the poses that show
 * whether it feels alive or like "a paper cut-out being bent" - head turns, tilt, eyes, blink, mouth, body lean - as the
 * whole figure or the face up close, with how much each pose changes from rest. For reviewing an avatar by eye.
 */
object RigPoseSheet {
	class Pose(val label: String, val values: Map<String, Float>)

	/** The desktop tool's poses, in its order. */
	val poses = listOf(
		Pose("rest", mapOf()),
		Pose("look left (X -30)", mapOf("ParamAngleX" to -30f)),
		Pose("look right (X +30)", mapOf("ParamAngleX" to 30f)),
		Pose("look up (Y +30)", mapOf("ParamAngleY" to 30f)),
		Pose("look down (Y -30)", mapOf("ParamAngleY" to -30f)),
		Pose("tilt (Z +30)", mapOf("ParamAngleZ" to 30f)),
		Pose("right + up", mapOf("ParamAngleX" to 30f, "ParamAngleY" to 30f)),
		Pose("eyes left", mapOf("ParamEyeBallX" to -1f)),
		Pose("blink", mapOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 0f)),
		Pose("mouth open", mapOf("ParamMouthOpenY" to 1f)),
		Pose("smile", mapOf("ParamMouthForm" to 1f, "ParamMouthOpenY" to 0.5f)),
		Pose("body lean (Body X +10)", mapOf("ParamBodyAngleX" to 10f, "ParamBodyAngleZ" to 10f)),
	)

	/** The face crop as fractions of the canvas: the desktop's defaults (the middle 60%, from 4% down, 34% tall). */
	data class Face(val top: Float = 0.04f, val height: Float = 0.34f, val left: Float = 0.2f, val width: Float = 0.6f)

	/** One tile: [pose] on [preview], the whole canvas or [face]'s region, [edge] px on its longer side. */
	class Tile(val pose: Pose, val image: RasterImage, val missing: List<String>, val changed: Double)

	/**
	 * Every pose rendered, each with the share of pixels that differ from rest (a cheap "did it move" number) and the
	 * parameters it needs that this rig doesn't have. Blocking; [onTile] gets each tile as it is drawn.
	 */
	fun render(preview: RigPreview, canvasWidth: Int, canvasHeight: Int, face: Face?, edge: Int, onTile: (Int, Tile) -> Unit = { _, _ -> }): List<Tile> {
		val known = preview.parameters.map { it.id }.toSet()
		fun draw(values: Map<String, Float>): RasterImage =
			if (face == null) preview.render(values, edge)
			else {
				val w = canvasWidth * face.width; val h = canvasHeight * face.height.coerceAtMost(1f - face.top)
				val scale = edge / maxOf(w, h)
				preview.render(values, canvasWidth * face.left, canvasHeight * face.top, w, h,
					(w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
			}
		var rest: RasterImage? = null
		return poses.mapIndexed { i, pose ->
			val image = draw(pose.values)
			if (rest == null) rest = image
			val base = rest!!.argb
			val changed = if (i == 0) 0.0 else image.argb.indices.count { image.argb[it] != base[it] }.toDouble() / image.argb.size
			Tile(pose, image, pose.values.keys.filter { it !in known }, changed).also { onTile(i, it) }
		}
	}

	/** [tiles] laid out [columns] across on a light background, as one ARGB image (labels go in the file names). */
	fun sheet(tiles: List<Tile>, columns: Int = 4, gap: Int = 8): Triple<Int, Int, IntArray> {
		val tw = tiles.maxOf { it.image.width }; val th = tiles.maxOf { it.image.height }
		val rows = (tiles.size + columns - 1) / columns
		val w = columns * tw + (columns + 1) * gap; val h = rows * th + (rows + 1) * gap
		val out = IntArray(w * h) { 0xFFEEEAF4.toInt() }
		for ((i, t) in tiles.withIndex()) {
			val x0 = gap + (i % columns) * (tw + gap); val y0 = gap + (i / columns) * (th + gap)
			for (y in 0 until t.image.height) for (x in 0 until t.image.width) {
				val src = t.image.argb[y * t.image.width + x]
				val a = src ushr 24
				if (a == 0) continue
				val at = (y0 + y) * w + x0 + x
				out[at] = if (a == 255) src else blend(out[at], src, a)
			}
		}
		return Triple(w, h, out)
	}

	/**
	 * The sheet and every tile as PNGs in the rig's folder (poses/ or faces/), named by pose so the labels travel with
	 * them; the folder's path, for sharing. Blocking.
	 */
	fun write(outcome: RigOutcome, tiles: List<Tile>, face: Boolean, encode: (Int, Int, IntArray) -> ByteArray): String {
		val dir = if (face) "faces" else "poses"
		val base = outcome.name.substringBeforeLast('.')
		val (w, h, argb) = sheet(tiles)
		RigFiles.write(outcome.folder, "$dir/$base-$dir.png", encode(w, h, argb))
		for ((i, t) in tiles.withIndex()) {
			val name = t.pose.label.replace(Regex("[^A-Za-z0-9+ -]"), "").trim()
			RigFiles.write(outcome.folder, "$dir/${(i + 1).toString().padStart(2, '0')} $name.png", encode(t.image.width, t.image.height, t.image.argb))
		}
		return outcome.folder + "/" + dir
	}

	private fun blend(dst: Int, src: Int, a: Int): Int {
		fun ch(s: Int) = (((src ushr s) and 0xFF) * a + ((dst ushr s) and 0xFF) * (255 - a)) / 255
		return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
	}
}
