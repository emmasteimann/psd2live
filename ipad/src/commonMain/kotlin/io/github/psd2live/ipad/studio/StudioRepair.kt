package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Cleanup and Clone, the web studio's `cleanup.js` and `clone.js`, on the native studio's own mask: the selected part's
 * painted mask is the keep-mask (Cleanup) or the region to clone into (Clone). Every change keeps what it needs to be
 * undone, in the same fields the web uses (`edgeCleanupBackup`, `cloneUndo`), so the Mac can undo it too.
 */
object StudioRepair {
	private fun maskAlpha(project: StudioProject, url: String?): IntArray {
		val w = project.width; val h = project.height
		val out = IntArray(w * h)
		val m = StudioImages.layer(url ?: return out, w, h)
		for (row in 0 until m.height) for (col in 0 until m.width) out[(m.y + row) * w + m.x + col] = m.argb[row * m.width + col] ushr 24
		return out
	}

	private fun png(project: StudioProject, argb: IntArray) = pngDataUrl(encodePng(project.width, project.height, argb))

	/** The keep-mask softened inward by [radius] px (Manhattan distance to its edge); never grows it. */
	fun featherInward(alpha: IntArray, w: Int, h: Int, radius: Int): IntArray {
		if (radius <= 0) return alpha
		val limit = radius + 1
		val d = IntArray(w * h)
		for (y in 0 until h) for (x in 0 until w) {
			val i = y * w + x; if (alpha[i] == 0) continue
			d[i] = minOf(limit, if (x > 0) d[i - 1] + 1 else 1, if (y > 0) d[i - w] + 1 else 1)
		}
		val out = alpha.copyOf()
		for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
			val i = y * w + x; if (alpha[i] == 0) continue
			d[i] = minOf(d[i], if (x < w - 1) d[i + 1] + 1 else 1, if (y < h - 1) d[i + w] + 1 else 1)
			out[i] = kotlin.math.round(alpha[i] * minOf(1.0, d[i].toDouble() / limit)).toInt()
		}
		return out
	}

	enum class Leftover(val label: String) { DROP("Drop it"), RETURN("Return the drawing's pixels to a part behind"), TRANSFER("Move them into another part") }

	class CleanupResult(val changes: Map<String, JsonElement?>, val targetChange: Pair<String, String>?, val removed: Int, val softened: Int, val event: String)

	/**
	 * Trims part [partId]'s art to its mask (the keep-mask), softened inward by [feather] px. What's trimmed off is
	 * dropped, or the drawing's own pixels outside the keep-mask go back to part [targetId] behind as hidden art
	 * ([Leftover.RETURN], parts extracted from the drawing only), or the trimmed pixels move into part [targetId]
	 * exactly ([Leftover.TRANSFER]: every antialiased pixel ends up in one piece or the other).
	 */
	fun cleanup(project: StudioProject, partId: String, feather: Int, leftover: Leftover, targetId: String?): CleanupResult {
		val w = project.width; val h = project.height
		val part = project.part(partId) ?: throw IllegalStateException("That part is gone.")
		val artUrl = part.cutout ?: throw IllegalStateException("Extract or import this part's art first. Hidden art under it isn't trimmed.")
		if (part.mask == null) throw IllegalStateException("Paint the keep-mask first: cover what should stay.")
		val art = StudioImages.layer(artUrl, w, h).full()
		val now = nowIso()
		if (leftover == Leftover.TRANSFER) {
			val target = project.part(targetId) ?: throw IllegalStateException("Choose the part the removed pixels go into.")
			if (target.id == partId) throw IllegalStateException("Choose a different part for the removed pixels.")
			val keep = maskAlpha(project, part.mask)
			val kept = art.copyOf(); val moved = IntArray(w * h)
			for (i in art.indices) if (keep[i] < 128 && (art[i] ushr 24) != 0) { moved[i] = art[i]; kept[i] = 0 }
			val keptCount = kept.count { (it ushr 24) != 0 }; val movedCount = moved.count { (it ushr 24) != 0 }
			if (keptCount == 0 || movedCount == 0) throw IllegalStateException("Splitting needs pixels on both sides. Refine the keep-mask.")
			val merged = moved.copyOf()
			target.cutout?.let { for ((i, p) in StudioImages.layer(it, w, h).full().withIndex()) merged[i] = StudioRender.blend(merged[i], p) }
			val keptUrl = png(project, kept); val mergedUrl = png(project, merged)
			val split = JsonObject(mapOf("id" to JsonPrimitive(target.id), "before" to (target.cutout?.let { JsonPrimitive(it) } ?: JsonNull), "after" to JsonPrimitive(mergedUrl)))
			return CleanupResult(mapOf("cutout" to JsonPrimitive(keptUrl), "edgeCleanupBackup" to JsonObject(mapOf(
				"variantId" to JsonNull, "before" to JsonPrimitive(artUrl), "after" to JsonPrimitive(keptUrl), "at" to JsonPrimitive(now), "splitTarget" to split))),
				target.id to mergedUrl, movedCount, 0, "Moved $movedCount px of ${part.name} into ${target.name}")
		}
		val keep = featherInward(maskAlpha(project, part.mask), w, h, feather)
		val out = IntArray(w * h)
		var changed = 0; var softened = 0
		for (i in art.indices) {
			val a = art[i] ushr 24; if (a == 0) continue
			val na = (a * keep[i] + 127) / 255
			if (na < a) { changed++; if (na > 0) softened++ }
			out[i] = (na shl 24) or (art[i] and 0xFFFFFF)
		}
		if (out.none { (it ushr 24) != 0 }) throw IllegalStateException("This mask removes all the art. Refine the keep-mask first.")
		if (changed == 0) throw IllegalStateException("This mask doesn't trim anything. Paint a tighter keep-mask.")
		val removed = art.count { (it ushr 24) != 0 } - out.count { (it ushr 24) != 0 }
		val artOut = png(project, out)
		var repair: JsonObject? = null
		if (leftover == Leftover.RETURN) {
			val footprint = part.extractionMask ?: throw IllegalStateException("Only parts extracted from the drawing have original pixels to return.")
			val target = project.part(targetId) ?: throw IllegalStateException("Choose the part behind that gets the returned pixels.")
			if (target.id == partId) throw IllegalStateException("Choose a different part for the returned pixels.")
			if (project.parts.indexOfFirst { it.id == target.id } >= project.parts.indexOfFirst { it.id == partId })
				throw IllegalStateException("Move ${target.name} behind ${part.name} first.")
			val foot = maskAlpha(project, footprint); val keepRaw = maskAlpha(project, part.mask)
			val source = StudioImages.layer(project.source, w, h).full()
			val patch = IntArray(w * h)
			for (i in patch.indices) {
				val region = foot[i] * (255 - keepRaw[i]) / 255; if (region == 0) continue
				val p = source[i]; patch[i] = (((p ushr 24) * region / 255) shl 24) or (p and 0xFFFFFF)
			}
			if (patch.none { (it ushr 24) != 0 }) throw IllegalStateException("No original pixels remain outside this keep-mask.")
			repair = JsonObject(mapOf("id" to JsonPrimitive(uuid()), "patch" to JsonPrimitive(png(project, patch)), "targetPartId" to JsonPrimitive(target.id),
				"model" to JsonPrimitive("local-source-return"), "createdAt" to JsonPrimitive(now),
				"prompt" to JsonPrimitive("Artist-reviewed visible original pixels outside a component keep-mask. Not generated hidden art.")))
		}
		val changes = mutableMapOf<String, JsonElement?>("cutout" to JsonPrimitive(artOut))
		if (repair != null) changes["repairs"] = JsonArray(part.repairs + repair)
		changes["edgeCleanupBackup"] = JsonObject(mapOf("variantId" to JsonNull, "before" to JsonPrimitive(artUrl), "after" to JsonPrimitive(artOut), "at" to JsonPrimitive(now),
			"returnedRepairId" to (repair?.get("id") ?: JsonNull),
			"returnedRepair" to (repair?.let { JsonObject(mapOf("id" to it["id"]!!, "patch" to it["patch"]!!, "targetPartId" to it["targetPartId"]!!)) } ?: JsonNull)))
		return CleanupResult(changes, null, removed, softened,
			"Trimmed ${part.name} to its keep-mask (${removed} px removed${if (feather > 0) ", ${feather}px soft edge" else ""}${if (repair != null) ", original pixels returned" else ""})")
	}

	/** Applies a cleanup to the project as it is now (the part and, for a transfer, the receiving part). */
	fun applyCleanup(project: StudioProject, partId: String, r: CleanupResult): StudioProject {
		var next = project.updatePart(partId, r.changes, r.event)
		r.targetChange?.let { (id, url) -> next = next.withParts(next.parts.map { if (it.id == id) it.with(mapOf("cutout" to JsonPrimitive(url))) else it }) }
		return next
	}

	/** Undoes the last cleanup, refusing if the art (or the receiving part) changed since, so no newer work is lost. */
	fun undoCleanup(project: StudioProject, partId: String): StudioProject {
		val part = project.part(partId) ?: return project
		val b = part.json["edgeCleanupBackup"] as? JsonObject ?: throw IllegalStateException("Nothing to undo here.")
		if (part.cutout != b.str("after")) throw IllegalStateException("The art changed since that cleanup. Restore a saved project instead of overwriting newer work.")
		val split = b["splitTarget"] as? JsonObject
		if (split != null) {
			val target = project.part(split.str("id")) ?: throw IllegalStateException("The receiving part is gone.")
			if (target.cutout != split.str("after")) throw IllegalStateException("The receiving part changed since. No newer work was overwritten.")
			val next = project.updatePart(partId, mapOf("cutout" to JsonPrimitive(b.str("before")!!), "edgeCleanupBackup" to JsonNull), "Undid cleanup")
			return next.withParts(next.parts.map { if (it.id == target.id) it.with(mapOf("cutout" to (split.str("before")?.let { u -> JsonPrimitive(u) } ?: JsonNull))) else it })
		}
		val returned = b.str("returnedRepairId")
		val repairs = if (returned != null) JsonArray(part.repairs.filter { it.str("id") != returned }) else null
		return project.updatePart(partId, buildMap {
			put("cutout", JsonPrimitive(b.str("before")!!)); put("edgeCleanupBackup", JsonNull); if (repairs != null) put("repairs", repairs)
		}, "Undid cleanup")
	}

	// ---- Clone (clone.js) ----

	val cloneScales = listOf(1.0, 1.5, 2.0, 4.0)

	/**
	 * Copies the drawing's pixels into the selected part's mask region, sampled from [source] to [dest] (the drawing
	 * scaled by [scale] about the source point), as hidden art on part [targetId] behind it. Refuses when a painted
	 * pixel would sample outside the drawing or from transparent art.
	 */
	fun clone(project: StudioProject, partId: String, source: Pt, dest: Pt, targetId: String, scale: Double): Pair<StudioProject, String> {
		val w = project.width; val h = project.height
		val part = project.part(partId) ?: throw IllegalStateException("That part is gone.")
		val region = maskAlpha(project, part.mask ?: throw IllegalStateException("Paint the region to clone into first (the part's mask)."))
		val ti = project.parts.indexOfFirst { it.id == targetId }; val oi = project.parts.indexOfFirst { it.id == partId }
		if (ti < 0 || ti >= oi) throw IllegalStateException("Choose a part behind ${part.name} to receive the cloned art.")
		val ox = kotlin.math.round(dest.x - source.x * scale); val oy = kotlin.math.round(dest.y - source.y * scale)
		val drawing = StudioImages.layer(project.source, w, h).full()
		val patch = IntArray(w * h)
		for (y in 0 until h) for (x in 0 until w) {
			val m = region[y * w + x]; if (m == 0) continue
			val sx = kotlin.math.floor((x + 0.5 - ox) / scale).toInt(); val sy = kotlin.math.floor((y + 0.5 - oy) / scale).toInt()
			val p = if (sx in 0 until w && sy in 0 until h) drawing[sy * w + sx] else 0
			if (m >= 16 && (p ushr 24) == 0) throw IllegalStateException("Some of the painted area samples outside the drawing or transparent art. Move the points or paint a smaller region.")
			patch[y * w + x] = (((p ushr 24) * m / 255) shl 24) or (p and 0xFFFFFF)
		}
		if (patch.none { (it ushr 24) != 0 }) throw IllegalStateException("The clone region has no visible pixels.")
		val id = uuid(); val url = png(project, patch)
		val repair = JsonObject(mapOf("id" to JsonPrimitive(id), "patch" to JsonPrimitive(url), "targetPartId" to JsonPrimitive(targetId), "model" to JsonPrimitive("local-clone"),
			"createdAt" to JsonPrimitive(nowIso()), "prompt" to JsonPrimitive("Artist-reviewed visible source pixels cloned to an underlying component; not AI-generated."),
			"clone" to JsonObject(mapOf("sourcePoint" to pt(source), "destinationPoint" to pt(dest), "offset" to JsonObject(mapOf("x" to JsonPrimitive(ox), "y" to JsonPrimitive(oy))), "scale" to JsonPrimitive(scale)))))
		val target = project.part(targetId)!!.name
		return project.updatePart(partId, mapOf("repairs" to JsonArray(part.repairs + repair), "cloneUndo" to JsonObject(mapOf("id" to JsonPrimitive(id), "patch" to JsonPrimitive(url)))),
			"Cloned drawing pixels into $target behind ${part.name}") to target
	}

	private fun pt(p: Pt) = JsonObject(mapOf("x" to JsonPrimitive(p.x), "y" to JsonPrimitive(p.y)))

	fun undoClone(project: StudioProject, partId: String): StudioProject {
		val part = project.part(partId) ?: return project
		val u = part.json["cloneUndo"] as? JsonObject ?: throw IllegalStateException("Nothing to undo here.")
		val repair = part.repairs.firstOrNull { it.str("id") == u.str("id") }
		if (repair == null || repair.str("patch") != u.str("patch")) throw IllegalStateException("The last cloned patch changed or was removed. No newer work was overwritten.")
		return project.updatePart(partId, mapOf("repairs" to JsonArray(part.repairs.filter { it.str("id") != u.str("id") }), "cloneUndo" to JsonNull), "Undid clone")
	}

}
