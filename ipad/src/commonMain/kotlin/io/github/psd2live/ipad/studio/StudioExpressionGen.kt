package io.github.psd2live.ipad.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Expression generators, the web's `expression-gen.js`: a blink (both eyes closed in one edit) and mouth shapes, made
 * from the whole face so they match it. Each is kept as a hidden part right above the eye or mouth it stands in for -
 * the form the rig switches by parameter (Eye L/R Open, Mouth Open Y, Mouth Form, vowels); `StudioRig.rigName` already
 * turns those roles into PSD2Live's "eye close-l/r", "mouth" and "mouth close".
 */
object StudioExpressionGen {
	class Shape(val id: String, val name: String, val words: String, val wide: Boolean)

	val mouths = listOf(
		Shape("open", "Mouth Open", "open as if mid-word saying 'ah': a small, natural open mouth with the dark inside of the mouth and a hint of tongue, top teeth barely visible", false),
		Shape("i", "Mouth I", "as if saying 'ee': lips stretched a little wide, slightly parted, top and bottom teeth showing close together", true),
		Shape("u", "Mouth U", "as if saying 'oo': lips pushed forward into a small round pout with a tiny round dark opening", true),
		Shape("e", "Mouth E", "as if saying 'eh': half open and a little wide, top teeth visible, a hint of tongue", true),
		Shape("o", "Mouth O", "as if saying 'oh': open in a rounded oval, a bit taller than wide, dark inside, a hint of tongue", true),
		Shape("smile", "Mouth Smile", "closed in a gentle happy smile, the corners turned up", true),
		Shape("frown", "Mouth Frown", "closed in a small unhappy frown, the corners turned down, slightly pouty", true),
	)

	/** A generated piece waiting for review: its role, name, the parts it stands in for, its art, and its rig parameter. */
	class Item(val role: String, val name: String, val above: List<String>, val art: String, val paramId: String, val paramAt: Double)

	/** Generated, not yet applied (kept here so switching inspector tabs doesn't lose a paid result). */
	var pending by mutableStateOf<List<Item>>(emptyList())
	/** The box the review should zoom to. */
	var pendingBounds by mutableStateOf<IntArray?>(null)

	class Face(val left: List<StudioPart>, val right: List<StudioPart>, val mouth: StudioPart?)

	private fun sideOf(name: String) = when {
		Regex("\\b(l|left)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "l"
		Regex("\\b(r|right)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "r"
		else -> null
	}
	private val eyeWords = Regex("eyewhite|eye white|sclera|irides|iris|pupil|eyelash|lash|lid|highlight", RegexOption.IGNORE_CASE)
	private fun hasArt(project: StudioProject, p: StudioPart) = p.cutout != null || project.parts.any { o -> o.repairs.any { it.str("targetPartId") == p.id } }

	/**
	 * The eyes and the mouth, found the general way: what the eye plan assigns, what the rig would call an eye white /
	 * iris / lash, or eye-ish names with a side (Auto-split's "Eyewhite L", the eye slots' "Left Upper Lid"); the mouth
	 * is what rigs as "mouth". Generated expression parts never count.
	 */
	fun findFace(project: StudioProject): Face {
		val live = project.parts.filter { it.json.str("expressionRole") == null && it.json["expressionOf"] == null && hasArt(project, it) }
		val planned = mapOf("l" to "left", "r" to "right").mapValues { (_, side) -> StudioFace.eyeRolesOf(project, side)?.mapNotNull { it.partId }?.toSet().orEmpty() }
		val eyes = mapOf("l" to mutableListOf<StudioPart>(), "r" to mutableListOf())
		for (p in live) {
			val rig = StudioRig.rigName(project, p) ?: ""
			val side = when {
				p.id in planned.getValue("l") -> "l"
				p.id in planned.getValue("r") -> "r"
				Regex("^(eyewhite|irides|eyelash)-[lr]").containsMatchIn(rig) -> rig.substringAfter('-').take(1)
				eyeWords.containsMatchIn(p.name) -> sideOf(p.name)
				else -> null
			}
			if (side != null) eyes.getValue(side) += p
		}
		val mouth = live.firstOrNull { StudioRig.fromName(it.name) == "mouth" && !Regex("open|smile|frown|close", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
		return Face(eyes.getValue("l"), eyes.getValue("r"), mouth)
	}

	/** How many eye or mouth parts exist but have no artwork yet (slots not extracted), to say why nothing was found. */
	fun emptySlots(project: StudioProject): Int = project.parts.count { p ->
		p.json.str("expressionRole") == null && !hasArt(project, p) && (eyeWords.containsMatchIn(p.name) || StudioRig.fromName(p.name) == "mouth")
	}

	/** Where [parts] have art (alpha ≥ 64), their approved patches included. */
	private fun footprint(project: StudioProject, parts: List<StudioPart>): ByteArray {
		val w = project.width; val h = project.height; val map = ByteArray(w * h)
		val ids = parts.map { it.id }.toSet()
		for (l in StudioRender.layers(project, includeHidden = true)) if (l.partId in ids) {
			val L = l.layer
			for (r in 0 until L.height) for (c in 0 until L.width) if ((L.argb[r * L.width + c] ushr 24) >= 64) map[(L.y + r) * w + L.x + c] = 1
		}
		return map
	}

	private fun bounds(map: ByteArray, w: Int, h: Int): IntArray? {
		var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
		for (y in 0 until h) for (x in 0 until w) if (map[y * w + x].toInt() != 0) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
		return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
	}

	/** The model's answer inside [region] (feathered by [feather] px), transparent elsewhere: a whole-canvas PNG. */
	private fun cutFrom(sq: IntArray, box: IntArray, region: ByteArray, w: Int, h: Int, feather: Int): String {
		val (bx, by, side) = Triple(box[0], box[1], box[2])
		val out = IntArray(w * h)
		for (y in by until by + side) for (x in bx until bx + side) {
			var sum = 0; var n = 0
			for (dy in -feather..feather) for (dx in -feather..feather) {
				val yy = y + dy; val xx = x + dx
				if (yy < 0 || yy >= h || xx < 0 || xx >= w) continue
				sum += region[yy * w + xx]; n++
			}
			if (sum == 0) continue
			val a = (sum * 255.0 / n).roundToInt()
			out[y * w + x] = (a shl 24) or (sq[(y - by) * side + x - bx] and 0xFFFFFF)
		}
		return pngDataUrl(encodePng(w, h, out))
	}

	private fun onWhite(a: IntArray) = IntArray(a.size) { StudioRender.blend(-1, a[it]) }

	class Result(val items: List<Item>, val bounds: IntArray)

	/** Both eyes closed in one image edit; one hidden "Eye Close L/R" part per eye. */
	suspend fun blink(project: StudioProject): Result {
		val w = project.width; val h = project.height
		val face = findFace(project)
		if (face.left.isEmpty() && face.right.isEmpty()) throw IllegalStateException("No eye parts found (names like \"Eyewhite L\", \"Left Iris\", or the eye plan on this tab).")
		val region = ByteArray(w * h); val areas = mutableMapOf<String, ByteArray>()
		for ((s, parts) in listOf("l" to face.left, "r" to face.right)) {
			if (parts.isEmpty()) continue
			val print = footprint(project, parts)
			val b = bounds(print, w, h) ?: continue
			// Its parts, grown a little so the closed lid and lash fit.
			val area = StudioMatch.dilate(print, w, h, (max(b[2] - b[0], b[3] - b[1]) * 0.12).roundToInt() + 6)
			areas[s] = area
			for (i in region.indices) if (area[i].toInt() != 0) region[i] = 1
		}
		val b = bounds(region, w, h) ?: throw IllegalStateException("The eye parts have no artwork yet.")
		val box = StudioAI.squareAround(b, w, h)
		val prompt = "Close both of the character's eyes in a gentle, relaxed blink: each eye becomes a single soft downward-curving closed eyelid line with the same dark lashes, the eye corners in the same place, and plain skin where the eye white and iris were. Same expression otherwise, no tears, no blush change. Match the art style, line weight and skin shading exactly."
		val sq = StudioAI.edit(onWhite(StudioRender.composite(project)), region, w, h, box, prompt, "expression-blink")
		val items = listOf("l" to face.left, "r" to face.right).mapNotNull { (s, parts) ->
			val area = areas[s] ?: return@mapNotNull null
			Item("eye-close-$s", "Eye Close ${s.uppercase()}", parts.map { it.id }, cutFrom(sq, box, area, w, h, 2), if (s == "l") "ParamEyeLOpen" else "ParamEyeROpen", 0.0)
		}
		return Result(items, b)
	}

	/** One mouth shape in one image edit, sized from the face (the eyes' spread) and centred on the mouth. */
	suspend fun mouth(project: StudioProject, shape: Shape): Result {
		val w = project.width; val h = project.height
		val face = findFace(project)
		val mouth = face.mouth ?: throw IllegalStateException("No mouth part found (a part named \"Mouth\").")
		val m = bounds(footprint(project, listOf(mouth)), w, h) ?: throw IllegalStateException("The mouth part has no artwork yet.")
		val eyeB = bounds(footprint(project, face.left + face.right), w, h)
		val faceW = if (eyeB != null) (eyeB[2] - eyeB[0]).toDouble() else (m[2] - m[0]) * 6.0
		val cx = (m[0] + m[2]) / 2.0; val cy = (m[1] + m[3]) / 2.0
		val hw = max(faceW * 0.16 * (if (shape.wide) 1.3 else 1.0), (m[2] - m[0]) * 0.8)
		val hh = max(faceW * 0.11 * (if (shape.wide) 1.2 else 1.0), (m[3] - m[1]) * 1.6)
		val region = ByteArray(w * h)
		for (y in max(0, floor(cy - hh * 0.7).toInt()) until min(h.toDouble(), cy + hh).let { kotlin.math.ceil(it).toInt() })
			for (x in max(0, floor(cx - hw).toInt()) until kotlin.math.ceil(min(w.toDouble(), cx + hw)).toInt()) {
				val dy = if (y < cy) (y - cy) / (hh * 0.7) else (y - cy) / hh; val dx = (x - cx) / hw
				if (dx * dx + dy * dy <= 1) region[y * w + x] = 1
			}
		val b = bounds(region, w, h) ?: throw IllegalStateException("The mouth is outside the canvas.")
		val box = StudioAI.squareAround(b, w, h)
		val prompt = "Change only the character's mouth: ${shape.words}. Same art style and line weight, small and cute and sized to her face, centred where her mouth is now. Same expression otherwise; nothing else changes."
		val sq = StudioAI.edit(onWhite(StudioRender.composite(project)), region, w, h, box, prompt, "expression-mouth-${shape.id}")
		val (pid, at) = when (shape.id) { "open" -> "ParamMouthOpenY" to 1.0; "smile" -> "ParamMouthForm" to 1.0; "frown" -> "ParamMouthForm" to -1.0; else -> "vowel-${shape.id}" to 1.0 }
		return Result(listOf(Item("mouth-${shape.id}", shape.name, listOf(mouth.id), cutFrom(sq, box, region, w, h, 3), pid, at)), b)
	}

	/** The character with [items] shown and the parts they replace hidden (empty = the neutral face). */
	fun preview(project: StudioProject, items: List<Item>): IntArray {
		val hide = items.flatMap { it.above }.toSet()
		val extra = items.map { StudioPart.blank(it.name, "Face").with(mapOf("cutout" to JsonPrimitive(it.art))) }
		return StudioRender.composite(project.withParts(project.parts.map { if (it.id in hide) it.with(mapOf("visible" to JsonPrimitive(false))) else it } + extra))
	}

	/** Each item becomes a hidden part right above the topmost part it stands in for; an earlier one with the same role is replaced. */
	fun apply(project: StudioProject, items: List<Item>): StudioProject {
		val parts = project.parts.filter { p -> items.none { p.json.str("expressionRole") == it.role } }.toMutableList()
		for (it in items) {
			val after = it.above.maxOf { id -> parts.indexOfFirst { p -> p.id == id } }
			parts.add(after + 1, StudioPart(JsonObject(mapOf(
				"id" to JsonPrimitive(uuid()), "name" to JsonPrimitive(it.name), "group" to JsonPrimitive(if (it.role.startsWith("eye")) "Eyes" else "Face"),
				"visible" to JsonPrimitive(false), "mask" to JsonNull, "repairs" to JsonArray(emptyList()), "cutout" to JsonPrimitive(it.art),
				"imported" to JsonPrimitive(true), "expressionRole" to JsonPrimitive(it.role), "expressionOf" to JsonArray(it.above.map { id -> JsonPrimitive(id) }),
				"expressionParam" to JsonObject(mapOf("id" to JsonPrimitive(it.paramId), "at" to JsonPrimitive(it.paramAt))),
			))))
		}
		return project.withParts(parts, "Added ${items.joinToString { it.name }}")
	}
}
