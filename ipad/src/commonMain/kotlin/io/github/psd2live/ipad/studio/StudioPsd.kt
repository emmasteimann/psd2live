package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import org.umamo.format.psd.PsdReader

/** Layered PSDs in and out of the studio, as the web studio's `psd-import.js` and "Export PSD" do. */
object StudioPsd {
	private val groups = listOf(
		Regex("hair", RegexOption.IGNORE_CASE) to "Hair",
		Regex("eye|irides|iris|lash|brow|lid", RegexOption.IGNORE_CASE) to "Eyes",
		Regex("face|nose|mouth|ear|neck|cheek|skin", RegexOption.IGNORE_CASE) to "Face",
		Regex("hat|hood|headwear|crown|talisman|ribbon|bow|neckwear|earwear|eyewear|object|lantern|tassel", RegexOption.IGNORE_CASE) to "Accessories",
		Regex("wear|sleeve|skirt|dress|robe|cloth|top|bottom", RegexOption.IGNORE_CASE) to "Clothing",
		Regex("arm|hand|leg|foot|body|tail|wing", RegexOption.IGNORE_CASE) to "Body",
	)

	/** The part group a layer name suggests (the web's `groupOf`). */
	fun groupOf(name: String): String = groups.firstOrNull { it.first.containsMatchIn(name) }?.second ?: "Custom"

	/**
	 * A project from a layered PSD: one part per pixel layer, bottom to top, each its own art and footprint; the
	 * drawing is the visible layers together; layers hidden in the PSD arrive hidden.
	 */
	fun import(bytes: ByteArray, fileName: String): StudioProject {
		require(bytes.size <= 200 * 1024 * 1024) { "Use a PSD smaller than 200 MB." }
		require(PsdReader.matches(bytes)) { "Choose a layered .psd file." }
		val art = PsdReader.read(bytes)
		val w = art.widthPx; val h = art.heightPx
		require(w > 0 && h > 0 && w.toLong() * h <= 16_000_000L) { "Use a PSD under 16 megapixels for this version." }
		val layers = art.layers.filter { it.raster.width > 0 && it.raster.height > 0 }
		require(layers.isNotEmpty()) { "This PSD has no pixel layers." }
		require(layers.size <= 100) { "This PSD has more than 100 layers." }
		val composite = IntArray(w * h)
		val parts = layers.map { layer ->
			val full = IntArray(w * h)
			val r = layer.raster; val b = layer.bounds
			for (row in 0 until r.height) {
				val y = b.top + row; if (y !in 0 until h) continue
				for (col in 0 until r.width) {
					val x = b.left + col; if (x !in 0 until w) continue
					val o = (row * r.width + col) * 4
					full[y * w + x] = ((r.rgba[o + 3].toInt() and 0xFF) shl 24) or ((r.rgba[o].toInt() and 0xFF) shl 16) or
						((r.rgba[o + 1].toInt() and 0xFF) shl 8) or (r.rgba[o + 2].toInt() and 0xFF)
				}
			}
			if (layer.visible) StudioRender.over(composite, w, Layer.trimmed(w, h, full))
			val name = layer.name.trim().take(80).ifEmpty { "Layer" }
			val png = JsonPrimitive(pngDataUrl(encodePng(w, h, full)))
			JsonObject(mapOf(
				"id" to JsonPrimitive(uuid()), "name" to JsonPrimitive(name), "group" to JsonPrimitive(groupOf(name)),
				"visible" to JsonPrimitive(layer.visible), "mask" to JsonNull, "repairs" to JsonArray(emptyList()),
				// Its own alpha is its footprint: for a full PSD, the remaining artwork is nothing.
				"cutout" to png, "extractionMask" to png, "imported" to JsonPrimitive(true),
			))
		}
		val now = nowIso()
		return StudioProject(JsonObject(mapOf(
			"version" to JsonPrimitive(1), "id" to JsonPrimitive(uuid()), "name" to JsonPrimitive(fileName.replace(Regex("\\.psd$", RegexOption.IGNORE_CASE), "")),
			"source" to JsonPrimitive(pngDataUrl(encodePng(w, h, composite))), "width" to JsonPrimitive(w), "height" to JsonPrimitive(h),
			"parts" to JsonArray(parts),
			"history" to JsonArray(listOf(JsonObject(mapOf("at" to JsonPrimitive(now), "event" to JsonPrimitive("Imported ${parts.size} layers from $fileName"))))),
			"updatedAt" to JsonPrimitive(now),
		)))
	}

	/**
	 * The project as a flat PSD, bottom to top - every layer `renderLayers` gives with hidden ones included (hidden in
	 * the PSD too) - and the visible layers merged as the file's composite image. 8-bit RGB, RLE channels.
	 */
	fun export(project: StudioProject): ByteArray {
		val w = project.width; val h = project.height
		val layers = StudioRender.layers(project, includeHidden = true)
		val merged = IntArray(w * h) { 0xFFFFFFFF.toInt() }
		for (l in layers) if (l.visible) StudioRender.over(merged, w, l.layer)
		return write(w, h, layers.map { PsdLayer(it.name, it.layer, !it.visible) }, merged)
	}

	class PsdLayer(val name: String, val layer: Layer, val hidden: Boolean)

	/** A flat PSD of [layers] (bottom first) on a [w] x [h] canvas with [merged] as its composite image. */
	fun write(w: Int, h: Int, layers: List<PsdLayer>, merged: IntArray): ByteArray {
		val out = Buffer()
		out.writeUtf8("8BPS"); out.writeShort(1); out.write(ByteArray(6)); out.writeShort(3); out.writeInt(h); out.writeInt(w); out.writeShort(8); out.writeShort(3)
		out.writeInt(0) // colour mode data
		out.writeInt(0) // image resources
		val records = Buffer(); val channels = Buffer()
		records.writeShort(layers.size)
		for (l in layers) {
			val g = l.layer
			// Each channel: compression 1 (RLE), the row byte counts, then the rows. Alpha (-1) first, as Photoshop writes.
			val encoded = listOf(-1 to 24, 0 to 16, 1 to 8, 2 to 0).map { (id, shift) -> id to rleChannel(g.argb, g.width, g.height, shift) }
			records.writeInt(g.y); records.writeInt(g.x); records.writeInt(g.y + g.height); records.writeInt(g.x + g.width)
			records.writeShort(encoded.size)
			for ((id, data) in encoded) { records.writeShort(id); records.writeInt(data.size) }
			records.writeUtf8("8BIMnorm"); records.writeByte(255); records.writeByte(0)
			records.writeByte(if (l.hidden) 0x02 else 0x00); records.writeByte(0)
			val extra = Buffer()
			extra.writeInt(0); extra.writeInt(0) // no layer mask, no blending ranges
			val ascii = l.name.map { if (it.code in 32..126) it else '?' }.joinToString("").take(255)
			extra.writeByte(ascii.length); extra.writeUtf8(ascii)
			repeat((4 - (1 + ascii.length) % 4) % 4) { extra.writeByte(0) }
			// The full name (any language) in a "luni" block.
			val luni = Buffer(); luni.writeInt(l.name.length); for (c in l.name) luni.writeShort(c.code)
			if (luni.size % 4 != 0L) repeat((4 - (luni.size % 4).toInt())) { luni.writeByte(0) }
			extra.writeUtf8("8BIMluni"); extra.writeInt(luni.size.toInt()); extra.writeAll(luni)
			records.writeInt(extra.size.toInt()); records.writeAll(extra)
			for ((_, data) in encoded) channels.write(data)
		}
		val info = Buffer(); info.writeAll(records); info.writeAll(channels)
		if (info.size % 2 != 0L) info.writeByte(0)
		out.writeInt((4 + info.size + 4).toInt()) // layer and mask information
		out.writeInt(info.size.toInt()); out.writeAll(info)
		out.writeInt(0) // global layer mask
		// The composite: RLE, every channel's row counts first, then the rows, R G B.
		out.writeShort(1)
		val planes = listOf(16, 8, 0).map { shift -> List(h) { y -> packBits(ByteArray(w) { x -> (merged[y * w + x] ushr shift).toByte() }) } }
		for (plane in planes) for (row in plane) out.writeShort(row.size)
		for (plane in planes) for (row in plane) out.write(row)
		return out.readByteArray()
	}

	/** One layer channel (the byte at [shift] of each pixel), RLE: compression 1, row byte counts, rows. */
	private fun rleChannel(argb: IntArray, w: Int, h: Int, shift: Int): ByteArray {
		val rows = List(h) { y -> packBits(ByteArray(w) { x -> (argb[y * w + x] ushr shift).toByte() }) }
		val b = Buffer(); b.writeShort(1)
		for (r in rows) b.writeShort(r.size)
		for (r in rows) b.write(r)
		return b.readByteArray()
	}

	/** Macintosh PackBits, as PSD channels use. */
	fun packBits(input: ByteArray): ByteArray {
		val out = Buffer()
		var i = 0
		while (i < input.size) {
			var run = 1
			while (i + run < input.size && run < 128 && input[i + run] == input[i]) run++
			if (run >= 2) { out.writeByte(1 - run); out.writeByte(input[i].toInt()); i += run; continue }
			var lit = 1
			while (i + lit < input.size && lit < 128 && !(i + lit + 1 < input.size && input[i + lit] == input[i + lit + 1])) lit++
			out.writeByte(lit - 1); out.write(input, i, lit); i += lit
		}
		return out.readByteArray()
	}
}
