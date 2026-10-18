package io.github.psd2live.ipad

import io.github.psd2live.core.BlendLimits
import io.github.psd2live.core.HitAreas
import io.github.psd2live.core.PoseGroups
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.ParameterId

/**
 * How a part is drawn (the desktop Inspector's statics): what clips it and whether the clip is reversed, its colour and
 * alpha blend, culling, its user data, and whether it is a hit area. Each change is the desktop's own `structure` static
 * edit (or its hit-area document edit), one undoable step, and the quick replay shows it at once.
 */
object RigPartLook {
	class Info(val id: String, val name: String, val maskedBy: List<String>, val invertMask: Boolean, val blend: BlendMode,
		val alpha: AlphaBlendMode, val culling: Boolean, val userData: String, val hitArea: String?)

	/** The colour blends offered, as the desktop lists them: the classic three, then Cubism 5.3's. */
	val blends: List<Pair<BlendMode, String>> = listOf(BlendMode.Normal to "Normal", BlendMode.Multiply to "Multiply", BlendMode.Additive to "Add",
		BlendMode.Screen to "Screen", BlendMode.Overlay to "Overlay", BlendMode.SoftLight to "Soft light", BlendMode.HardLight to "Hard light",
		BlendMode.Darken to "Darken", BlendMode.Lighten to "Lighten", BlendMode.ColorDodge to "Colour dodge", BlendMode.ColorBurn to "Colour burn",
		BlendMode.LinearBurn to "Linear burn", BlendMode.LinearLight to "Linear light", BlendMode.AdditiveGlow to "Add (glow)",
		BlendMode.Hue to "Hue", BlendMode.Color to "Colour")
	val alphas: List<Pair<AlphaBlendMode, String>> = listOf(AlphaBlendMode.Over to "Over (normal)", AlphaBlendMode.Atop to "Atop",
		AlphaBlendMode.Out to "Out", AlphaBlendMode.Conjoint to "Conjoint", AlphaBlendMode.Disjoint to "Disjoint")

	/** Whether [info] uses something only Cubism 5.3 runtimes draw (older ones fall back to normal). */
	fun needs53(info: Info) = info.blend !in setOf(BlendMode.Normal, BlendMode.Multiply, BlendMode.Additive,
		BlendMode.MultiplyPremultiplied, BlendMode.AdditivePremultiplied) || info.alpha != AlphaBlendMode.Over

	/** The blend [info] shows as: the premultiplied classic modes read as their plain names, as on the desktop. */
	fun shownBlend(info: Info): BlendMode = when (info.blend) {
		BlendMode.MultiplyPremultiplied -> BlendMode.Multiply
		BlendMode.AdditivePremultiplied -> BlendMode.Additive
		else -> info.blend
	}

	fun info(e: RigEditing, id: String): Info? {
		val d = e.puppet.drawables.firstOrNull { it.id.raw == id } ?: return null
		return Info(id, d.name, d.maskedBy.map { it.raw }, d.invertMask, d.blendMode, d.alphaBlendMode, d.culling, d.userData,
			e.model.config.rigEdits.hitAreas.firstOrNull { it.second == id }?.first)
	}

	fun name(e: RigEditing, id: String): String = e.puppet.drawables.firstOrNull { it.id.raw == id }?.name ?: id

	private fun static(e: RigEditing, id: String, field: String, value: JsonElement) = e.journal(listOf(buildJsonObject {
		put("op", "structure")
		putJsonArray("edits") { add(buildJsonObject { put("action", "static"); put("kind", "mesh"); put("id", id); put(field, value) }) }
	}))

	/** [id] clipped by [maskers] (shown only where they are drawn); none: not clipped. */
	fun masks(e: RigEditing, id: String, maskers: List<String>) {
		require(id !in maskers) { "A part can't clip itself" }
		static(e, id, "masked_by", JsonArray(maskers.distinct().map(::JsonPrimitive)))
	}
	fun invert(e: RigEditing, id: String, on: Boolean) = static(e, id, "invert_mask", JsonPrimitive(on))
	fun blend(e: RigEditing, id: String, mode: BlendMode) = static(e, id, "blend_mode", JsonPrimitive(mode.name))
	fun alpha(e: RigEditing, id: String, mode: AlphaBlendMode) = static(e, id, "alpha_blend", JsonPrimitive(mode.name))
	fun culling(e: RigEditing, id: String, on: Boolean) = static(e, id, "culling", JsonPrimitive(on))
	fun userData(e: RigEditing, id: String, text: String) = static(e, id, "user_data", JsonPrimitive(text))

	/** [id] as the hit area [name] (what an app hit-tests for taps), replacing any area it was; null or blank: not one. */
	fun hitArea(e: RigEditing, id: String, name: String?) {
		val overlay = e.model.config.rigEdits
		val areas = overlay.hitAreas.filter { it.second != id } + listOfNotNull(name?.trim()?.takeIf { it.isNotEmpty() }?.let { it to id })
		e.reconfigure(e.model.config.copy(rigEdits = overlay.copy(hitAreas = HitAreas.put(HitAreas.request(areas)))))
	}
}

/**
 * Pose groups (Cubism's pose3, the desktop's `pose_put`): parts of which one shows at a time - arm A or arm B, an
 * outfit piece or its swap. Groups of parts, the fade between them, and the parts that switch along with a member.
 */
object RigPoseGroups {
	class Part(val id: String, val name: String)

	fun parts(e: RigEditing): List<Part> = e.puppet.parts.map { Part(it.id.raw, it.name) }
	fun groups(e: RigEditing): List<List<String>> = e.model.config.rigEdits.poseGroups
	fun fade(e: RigEditing): Float = e.model.config.rigEdits.poseFadeIn
	fun links(e: RigEditing): Map<String, List<String>> = e.model.config.rigEdits.poseLinks

	/** The groups replaced by [groups] (fade [fade] seconds; [links] by member) through the desktop's own parsing. */
	fun put(e: RigEditing, groups: List<List<String>>, fade: Float = fade(e), links: Map<String, List<String>> = links(e)) {
		val request = PoseGroups.request(groups, fade, links)
		val (kept, fadeIn) = PoseGroups.put(request)
		val overlay = e.model.config.rigEdits
		e.reconfigure(e.model.config.copy(rigEdits = overlay.copy(poseGroups = kept, poseFadeIn = fadeIn, poseLinks = PoseGroups.links(request, kept))))
	}
}

/**
 * Blend-shape weight limits (the desktop's `blend_limits_put`): a curve over another parameter that caps how much a
 * blend shape applies - a smile that fades out as the mouth opens. Kept by blend-shape parameter.
 */
object RigBlendLimits {
	fun limits(e: RigEditing, parameter: String): List<BlendWeightLimit> = e.model.config.rigEdits.blendLimits[parameter].orEmpty()

	/** [parameter]'s limits replaced by [limits] (none removes them), checked by the desktop's own rules. */
	fun put(e: RigEditing, parameter: String, limits: List<BlendWeightLimit>) {
		val overlay = e.model.config.rigEdits
		val next = BlendLimits.put(overlay.blendLimits, BlendLimits.request(parameter, limits))
		e.reconfigure(e.model.config.copy(rigEdits = overlay.copy(blendLimits = next)))
	}

	/** A limit over [by]: full weight from its lowest to [fadeFrom], none by [fadeTo] (a simple fade, the common case). */
	fun fade(e: RigEditing, by: String, fadeFrom: Float, fadeTo: Float): BlendWeightLimit {
		val p = requireNotNull(e.puppet.parameters.firstOrNull { it.id.raw == by }) { "Parameter not found: $by" }
		require(fadeFrom <= fadeTo) { "The fade starts before it ends" }
		val points = listOf(p.min to 1f, fadeFrom to 1f, fadeTo to 0f, p.max to 0f).map { (v, w) -> v.coerceIn(p.min, p.max) to w }
			.sortedBy { it.first }.distinctBy { it.first }.map { (v, w) -> BlendWeightLimitPoint(v, w) }
		return BlendWeightLimit(ParameterId(by), points)
	}
}
