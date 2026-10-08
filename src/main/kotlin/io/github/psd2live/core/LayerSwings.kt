package io.github.psd2live.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Layerloom patch: swings asked for by PSD layer name ([PipelineConfig.layerSwings], `--swings`), turned into
 * PSD2Live's own swings (the same ones the editor's swing tool makes) on the built rig. Each layer's settings are
 * `swing_put` arguments without id/name/targets: kind or motions, fulcrum, offset_along (pivot, in lengths from the
 * pinned edge), magnitude, softness, lift, parallel, preset, physics {length, mobility, delay, acceleration,
 * output_scale}; without physics the pendulum is sized from the layer.
 */
internal object LayerSwings {
	fun apply(rig: BuiltRig, config: PipelineConfig, layers: List<ClassifiedLayer>): PipelineConfig {
		if (config.layerSwings.isEmpty()) return config
		val nameById = layers.associate { it.source.id.raw to it.source.name }
		val drawablesByName = rig.layerIdByDrawableId.entries.groupBy({ nameById[it.value] }, { it.key })
		var overlay = config.rigEdits
		for ((name, settings) in config.layerSwings) {
			val drawables = drawablesByName[name].orEmpty().sorted()
			if (drawables.isEmpty()) { System.err.println("Warning: swing: no layer named \"$name\""); continue }
			val id = "Layer" + name.filter { it.isLetterOrDigit() }.replaceFirstChar { it.uppercase() }
			overlay = runCatching {
				val request = SwingAuthoring.request(JsonObject(settings + mapOf(
					"id" to JsonPrimitive(id), "name" to JsonPrimitive("Swing: $name"),
					"targets" to JsonArray(drawables.map(::JsonPrimitive)),
				)))
				SwingAuthoring.put(overlay, rig.withRigEdits(overlay).puppet, request.edit, request.estimatePhysics)
			}.getOrElse { System.err.println("Warning: swing \"$name\": ${it.message}"); overlay }
		}
		return config.copy(rigEdits = overlay)
	}
}
