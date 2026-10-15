package io.github.psd2live.core

import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/**
 * Layerloom patch (ONE-APP-PLAN S4.8): a motion's part-opacity tracks (Cubism motion3 PartOpacity), so a motion can
 * fade a part in and out. They are ordinary motion curves whose id is `part:<part id>` (a colon is never in a parameter
 * id), valued 0 (hidden) to 1 (shown), so the timeline, key edits and copy/paste treat them like any track. The
 * export writes them as PartOpacity curves; the preview multiplies the part's meshes' opacity by them.
 */
object PartTracks {
	const val PREFIX = "part:"

	fun isPart(trackId: String): Boolean = trackId.startsWith(PREFIX)
	fun partOf(trackId: String): String = trackId.removePrefix(PREFIX)
	fun trackOf(partId: String): String = PREFIX + partId

	/** The value range every part track keys in, by track id, for the parts [model] has. */
	fun ranges(model: PuppetModel): Map<String, ClosedFloatingPointRange<Float>> = model.parts.associate { trackOf(it.id.raw) to 0f..1f }

	/** [opacity] (by drawable) with each mesh under an animated part scaled by that part's track value in [pose]. */
	fun <K> applied(model: PuppetModel, pose: Map<ParameterId, Float>, opacity: Map<K, Float>, key: (String) -> K, base: (String) -> Float): Map<K, Float> {
		val animated = pose.filterKeys { isPart(it.raw) }
		if (animated.isEmpty()) return opacity
		val byId = model.parts.associateBy { it.id }
		val factor = HashMap<String, Float>()
		fun walk(part: PartId, scale: Float, seen: Set<PartId>) {
			val p = byId[part] ?: return
			if (part in seen) return
			val here = scale * (animated[ParameterId(trackOf(part.raw))]?.coerceIn(0f, 1f) ?: 1f)
			for (child in p.children) when (child) {
				is OrgChild.Drawable -> factor[child.id.raw] = here
				is OrgChild.Part -> walk(child.id, here, seen + part)
			}
		}
		val childParts = model.parts.flatMap { p -> p.children.filterIsInstance<OrgChild.Part>().map { it.id } }.toSet()
		model.parts.filter { it.id !in childParts }.forEach { walk(it.id, 1f, emptySet()) }
		if (factor.values.all { it == 1f }) return opacity
		val out = opacity.toMutableMap()
		for ((drawable, f) in factor) if (f != 1f) { val k = key(drawable); out[k] = (opacity[k] ?: base(drawable)) * f }
		return out
	}
}
