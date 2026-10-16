package io.github.psd2live.ipad

import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side

/**
 * What each layer is (the desktop's `layer_classify`): the rigger reads a layer's role from its name - eyebrow, front
 * hair, mouth - and rigs it for that role. When it guesses wrong, set the role, side and kind here; the rig is generated
 * again with it (a layer's role decides its deformers and motion, so this is a full rebuild).
 */
object RigLayers {
	/** A layer as the rigger understands it. [chosen] when the role was set by hand (not guessed from the name). */
	class Layer(val id: String, val name: String, val tag: SemanticTag, val side: Side, val type: LayerType,
		val parameter: String, val switchId: Int, val confidence: Float, val chosen: Boolean)

	/** Every layer the rigger read, top of the stack first. */
	fun list(e: RigEditing): List<Layer> {
		val overrides = e.model.config.layerOverrides
		val order = e.source?.layers?.associate { it.id.raw to it.order }.orEmpty()
		return e.model.analysis.layers.filter { it.source !is io.github.psd2live.core.MouthLipLayer }.map { l ->
			val s = l.semantic
			Layer(l.source.id.raw, l.source.name, s.tag, s.side, s.type, s.parameter, s.switchId, s.confidence, l.source.id.raw in overrides)
		}.sortedBy { order[it.id] ?: Int.MAX_VALUE }
	}

	/**
	 * [layerId] set to [tag], [side] and [type] (a toggle or switch shows by [parameter]; a switch at [switchId]), the rig
	 * built again as one undoable step - exactly the desktop's classify (an unchanged classification changes nothing).
	 * Blocking.
	 */
	fun classify(e: RigEditing, layerId: String, tag: SemanticTag, side: Side, type: LayerType = LayerType.PRESET,
		parameter: String = "", switchId: Int = 0) {
		require(switchId >= 0) { "The switch number can't be negative" }
		val current = e.model.analysis.layers.firstOrNull { it.source.id.raw == layerId }?.semantic ?: throw IllegalArgumentException("Layer not found")
		if (current.type == type && current.tag == tag && current.side == side && current.parameter == parameter && current.switchId == switchId) return
		val config = e.model.config
		e.rebuild(config.copy(layerOverrides = config.layerOverrides + (layerId to LayerClassificationOverride(type, tag, side, parameter, switchId))))
	}

	/** [layerId] back to what its name says (the role set by hand forgotten), as one undoable step. Blocking. */
	fun automatic(e: RigEditing, layerId: String) {
		val config = e.model.config
		if (layerId !in config.layerOverrides) return
		e.rebuild(config.copy(layerOverrides = config.layerOverrides - layerId))
	}

	/** The roles, grouped as the rigger groups them, with plain names. */
	val roles: List<Pair<String, List<Pair<SemanticTag, String>>>> = listOf(
		"Hair and head" to listOf(SemanticTag.BACK_HAIR to "Back hair", SemanticTag.FRONT_HAIR to "Front hair", SemanticTag.HEADWEAR to "Headwear",
			SemanticTag.EARS to "Ears", SemanticTag.EARWEAR to "Earwear"),
		"Face" to listOf(SemanticTag.FACE to "Face", SemanticTag.FACE_DETAIL to "Face detail (blush, marks)", SemanticTag.NOSE to "Nose",
			SemanticTag.EYEWEAR to "Eyewear"),
		"Eyes" to listOf(SemanticTag.EYEBROW to "Eyebrow", SemanticTag.EYEWHITE to "Eye white", SemanticTag.IRIDES to "Iris",
			SemanticTag.EYELASH to "Eyelash / lid", SemanticTag.EYE_CLOSE to "Closed eye"),
		"Mouth" to listOf(SemanticTag.MOUTH to "Mouth", SemanticTag.MOUTH_OPEN to "Open mouth", SemanticTag.MOUTH_CLOSE to "Closed mouth",
			SemanticTag.TONGUE to "Tongue", SemanticTag.TOOTH_T to "Upper teeth", SemanticTag.TOOTH_B to "Lower teeth"),
		"Body" to listOf(SemanticTag.NECK to "Neck", SemanticTag.NECKWEAR to "Neckwear", SemanticTag.TOPWEAR to "Top / body",
			SemanticTag.HANDWEAR to "Arms / sleeves", SemanticTag.BOTTOMWEAR to "Skirt / trousers", SemanticTag.LEGWEAR to "Legs",
			SemanticTag.FOOTWEAR to "Feet / shoes"),
		"Extras" to listOf(SemanticTag.TAIL to "Tail", SemanticTag.WINGS to "Wings", SemanticTag.OBJECTS to "Object / accessory",
			SemanticTag.UNKNOWN to "Not sure (moves with what's around it)"),
	)

	fun roleName(tag: SemanticTag): String = roles.flatMap { it.second }.firstOrNull { it.first == tag }?.second ?: tag.canonicalName
}
