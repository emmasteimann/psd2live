package io.github.psd2live.ipad

import io.github.psd2live.core.PhysicsAuthoring
import io.github.psd2live.core.PhysicsCatalog
import io.github.psd2live.core.PhysicsFitting
import io.github.psd2live.core.PipelineConfig
import okio.Path.Companion.toPath
import org.umamo.runtime.model.Parameter

/**
 * The desktop's physics extras on the iPad: fit a group to the art (its outputs scaled so a standard head sway swings
 * each to [target] of its parameter's end - the desktop's `physics_fit`), and copy physics from another rig's
 * physics3.json (`physics_import`). Both return the edited settings; the Physics screen previews and applies them.
 */
object RigPhysicsExtras {
	class Imported(val config: PipelineConfig, val ids: List<String>, val disabled: List<String>, val missing: Map<String, List<String>>)

	fun fit(model: io.github.psd2live.core.RigPreviewModel, config: PipelineConfig, groupId: String, target: Float = 1f): PipelineConfig {
		val parameters: List<Parameter> = model.rig.puppet.parameters
		val group = PhysicsCatalog.groups(model.analysis, config, parameters.mapTo(HashSet()) { it.id.raw }).firstOrNull { it.id == groupId }
			?: throw IllegalArgumentException("Physics group not found: $groupId")
		return config.copy(rigEdits = PhysicsFitting.fit(config.rigEdits, group, parameters, target))
	}

	/** [text] (a physics3.json) as user groups, as the desktop imports it; the file's Fps becomes this rig's. */
	fun import(model: io.github.psd2live.core.RigPreviewModel, config: PipelineConfig, text: String): Imported {
		val available = model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
		val imported = PhysicsAuthoring.import(config.rigEdits, PhysicsCatalog.groups(model.analysis, config, available), text, available)
		return Imported(config.copy(rigEdits = imported.overlay, generatePhysics = true), imported.ids, imported.disabled, imported.missing)
	}

	fun readFile(path: String): String = okio.FileSystem.SYSTEM.read(path.toPath()) { readUtf8() }
}
