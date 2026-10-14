package io.github.psd2live.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.edit.withPartComposite
import org.umamo.edit.withPartGroupMode
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/**
 * Layerloom patch (ONE-APP-PLAN S4.5): a part's drawing settings as static structure edits: `group_mode`
 * (pass_through, grouped = Cubism's "group by draw order", isolated = 5.3 offscreen drawing) and the offscreen
 * composite's `composite_blend`, `composite_alpha`, `composite_opacity` and `composite_invert_mask`.
 */
internal object PartStatics {
	val keys = setOf("group_mode", "composite_blend", "composite_alpha", "composite_opacity", "composite_invert_mask")

	fun modeName(mode: PartGroupMode): String = when (mode) {
		PartGroupMode.PassThrough -> "pass_through"; PartGroupMode.Grouped -> "grouped"; PartGroupMode.Isolated -> "isolated"
	}

	private fun mode(text: String): PartGroupMode = when (text.lowercase()) {
		"pass_through" -> PartGroupMode.PassThrough; "grouped" -> PartGroupMode.Grouped; "isolated" -> PartGroupMode.Isolated
		else -> throw IllegalArgumentException("group_mode is pass_through, grouped or isolated: $text")
	}

	fun apply(model: PuppetModel, id: PartId, edit: JsonObject): PuppetModel {
		var next = model
		edit["group_mode"]?.jsonPrimitive?.contentOrNull?.let { next = next.withPartGroupMode(id, mode(it)) }
		val part = next.parts.single { it.id == id }
		var composite = part.composite
		edit["composite_blend"]?.jsonPrimitive?.contentOrNull?.let { v ->
			composite = composite.copy(blendMode = BlendMode.entries.firstOrNull { it.name.equals(v, true) } ?: throw IllegalArgumentException("Unknown blend mode: $v"))
		}
		edit["composite_alpha"]?.jsonPrimitive?.contentOrNull?.let { v ->
			composite = composite.copy(alphaBlendMode = AlphaBlendMode.entries.firstOrNull { it.name.equals(v, true) } ?: throw IllegalArgumentException("Unknown alpha blend: $v"))
		}
		edit["composite_opacity"]?.jsonPrimitive?.floatOrNull?.let { v -> composite = composite.copy(opacity = v.coerceIn(0f, 1f)) }
		edit["composite_invert_mask"]?.jsonPrimitive?.booleanOrNull?.let { v -> composite = composite.copy(invertMask = v) }
		return if (composite == part.composite) next else next.withPartComposite(id, composite)
	}

	fun isNoOp(model: PuppetModel, id: String, edit: JsonObject): Boolean {
		val part = model.parts.firstOrNull { it.id.raw == id } ?: return false
		if (edit.keys.none { it in keys } || edit.keys.any { it !in keys && it !in setOf("action", "kind", "id") }) return false
		return runCatching { apply(model, part.id, edit) }.getOrNull()?.parts?.firstOrNull { it.id == part.id } == part
	}
}
