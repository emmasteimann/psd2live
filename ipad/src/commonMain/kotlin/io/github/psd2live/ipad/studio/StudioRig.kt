package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What PSD2Live is told each part is: the web studio's `rig.js` (`rigName`, `fromName`, `MOTIONS`). */
object StudioRig {
	val motions = listOf(
		"" to "Automatic (from its name)", "head" to "Head (turns with the head)", "body" to "Body (sways with the body)",
		"front hair" to "Front hair (swings)", "back hair" to "Back hair (swings)", "arm-l" to "Left arm", "arm-r" to "Right arm",
		"object" to "Accessory (moves with the body)",
	)

	private val tags = listOf("back hair", "front hair", "headwear", "face", "irides", "eyebrow", "eyewhite", "eyelash", "eyewear", "ears", "earwear",
		"nose", "mouth", "neck", "neckwear", "topwear", "handwear", "bottomwear", "legwear", "footwear", "tail", "wings", "objects")

	fun fromName(name: String): String {
		val n = name.lowercase().trim()
		val m = Regex("^(.*?)(?:\\s+(l|r|left|right))?(?:\\s+(\\d+))?$").find(n)!!
		var stem = m.groupValues[1]
		val side = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { "-${it[0]}" } ?: ""
		val num = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
		stem = stem.replace(Regex("\\s+(under|front)\\b.*$"), "")
		val known = tags.firstOrNull { stem == it || stem == it.replace(" ", "") } ?: when {
			Regex("hood|hat|crown|cap|helmet|talisman|hairpin|ribbon on head").containsMatchIn(stem) -> "headwear"
			Regex("sleeve|arm|hand|glove|cuff").containsMatchIn(stem) -> "handwear"
			Regex("robe|dress|coat|shirt|cloak|cape|jacket|top").containsMatchIn(stem) -> "topwear"
			Regex("skirt|pants|shorts").containsMatchIn(stem) -> "bottomwear"
			Regex("boot|shoe|sock").containsMatchIn(stem) -> "footwear"
			Regex("leg|thigh").containsMatchIn(stem) -> "legwear"
			Regex("lantern|tassel|staff|wand|book|bag|object|charm").containsMatchIn(stem) -> "objects"
			Regex("ribbon|bow|collar|scarf|tie").containsMatchIn(stem) -> "neckwear"
			else -> null
		}
		return if (known != null) known + (if (known in listOf("eyewhite", "irides", "eyelash", "eyebrow", "ears", "handwear")) side else "") + num else n
	}

	/** What PSD2Live will be told this part is, or null to leave it out of the rig. */
	fun rigName(project: StudioProject, part: StudioPart): String? {
		val role = part.json.str("expressionRole")
		if (role != null) {
			if (role.startsWith("eye-close-")) return "eye close-${role.last()}"
			if (role == "mouth-open") return "mouth"
			return null
		}
		if (project.parts.any { p -> p.json.str("expressionRole") == "mouth-open" &&
				((p.json["expressionOf"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == part.id } == true) }) return "mouth close"
		if (!part.visible) return null
		when (val motion = part.json.str("rigMotion")) {
			"head" -> return "headwear"
			"body" -> return "topwear"
			"front hair", "back hair" -> return motion
			"arm-l", "arm-r" -> return "handwear-${motion.last()}"
			"object" -> return "objects"
		}
		part.json.str("seeThroughTag")?.let { return it }
		return fromName(part.name)
	}
}
