package io.github.psd2live.application

import io.github.psd2live.core.*
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ParameterId
import kotlin.math.PI
import kotlin.math.sin

/**
 * The swing dialog's draft work, apart from its session bookkeeping: a new swing's defaults for some targets, the rig
 * with a draft swing applied for auditioning it, and the audition's motion. The desktop's swing sessions and the
 * iPad's swing screen both build on these.
 */
object WorkspaceSwingDrafts {
    /** The swing already moving any of [targets] (or their parent deformers), if there is one. */
    fun existing(model: RigPreviewModel, overlay: RigEditOverlay, targets: List<String>): RigSwingEdit? {
        val puppet = model.rig.puppet
        val parents = targets.mapNotNull { id -> puppet.drawables.firstOrNull { it.id.raw == id }?.parentDeformerId?.raw }
        return overlay.swingEdits.firstOrNull { swing -> swing.targets.any { it in targets || it in parents } }
    }

    /** A new single-direction swing on [targets] with [preset]'s shape and pendulum, sized from the first target. */
    fun defaults(model: RigPreviewModel, overlay: RigEditOverlay, targets: List<String>, preset: SwingPreset, name: String?): RigSwingEdit {
        val puppet = model.rig.puppet; val first = targets.first()
        val targetName = puppet.deformers.firstOrNull { it.id.raw == first }?.name ?: puppet.drawables.firstOrNull { it.id.raw == first }?.name
            ?: throw IllegalArgumentException("Swing target not found: $first")
        val (id, parameters) = SwingAuthoring.freshIds(puppet, overlay, first, 1)
        val length = SwingGenerator.measure(puppet, first)?.second ?: puppet.drawables.firstOrNull { it.id.raw == first }?.let { drawable ->
            val bounds = RigGeometryTools.bounds(requireNotNull(drawable.mesh).positions)
            val scale = (puppet.deformers.firstOrNull { it.id == drawable.parentDeformerId } as? Deformer.Warp)?.let { SwingGenerator.measure(puppet, it.id.raw)?.second } ?: 1f
            maxOf(bounds[2], bounds[3]) * scale
        }
        return RigSwingEdit.single(id, name ?: "Swing $targetName", SwingKind.LATERAL, targets, parameters,
            shape = SwingPresets.shape(preset, SwingKind.LATERAL), preset = preset, physics = SwingPresets.physics(preset, SwingKind.LATERAL, length))
    }

    /** [model] with [edit] applied in place of the swing of its ID (if any), and [edit] as authoring prepares it. */
    fun prepare(model: RigPreviewModel, overlay: RigEditOverlay, edit: RigSwingEdit): Pair<RigPreviewModel, RigSwingEdit> {
        val puppet = overlay.swingEdits.firstOrNull { it.id == edit.id }?.let { SwingGenerator.strip(model.rig.puppet, it) } ?: model.rig.puppet
        val next = SwingAuthoring.put(overlay, puppet, edit)
        val prepared = next.swingEdits.single { it.id == edit.id }
        val wrapped = next.authoringJournal.drop(overlay.authoringJournal.size).fold(puppet, RigAuthoringJournal::apply)
        return model.copy(rig = model.rig.copy(puppet = SwingGenerator.apply(wrapped, listOf(prepared)))) to prepared
    }

    /** The audition's swing parameter values [elapsed] seconds in: each direction a sine, each segment a little behind. */
    fun audition(draft: RigSwingEdit, elapsed: Float): Map<ParameterId, Float> =
        draft.motions.withIndex().flatMap { (m, entry) -> entry.parameterIds.withIndex().map { (k, id) ->
            ParameterId(id) to sin(2f * PI.toFloat() * elapsed / (if (m == 0) 1.6f else 1.3f) - k * 0.7f - m * 1.3f)
        } }.toMap()
}
