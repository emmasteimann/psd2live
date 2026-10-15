package io.github.psd2live.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import org.umamo.format.art.SourceArt
import java.nio.file.Files
import java.nio.file.Path

/**
 * Layerloom patch: a Layerloom rig job, everything Layerloom's studio asks of a rig beyond the PSD. It's written beside
 * the PSD as `NAME.layerloom.json`, so the CLI, the editor's Import PSD and the agent's import all build the same rig
 * (docs: layerloom/docs/ONE-APP-PLAN.md S1/S2).
 *
 * - [rig]: hair anchors, glue and turn depth. These are build inputs, saved with a project and applied by every build.
 * - [toggles], [skeleton]/[armJoints] and [swings]: one-time setup. They become the editor's own data (layer
 *   overrides, the skeleton, swing edits) and are then the owner's to edit; they aren't re-applied.
 *
 * The file uses the same keys as the CLI option files:
 * `{"hairAnchors": {...}, "glues": {...}, "swings": {...}, "toggles": {layer: param}, "turnDepths": {...},
 * "turnDepthsY": {...}, "hairTurnDepth": 0.5, "hairTurnDepthY": 0.5, "skeleton": "arms", "armJoints": {...},
 * "expressions": [{"name": "Glasses", "parameters": [{"Id": "ParamGlasses", "Value": 1, "Blend": "Add"}]}]}`.
 */
data class LayerloomJob(
	val rig: LayerloomRigOptions = LayerloomRigOptions(),
	val toggles: Map<String, String> = emptyMap(),
	val skeleton: String? = null,
	val armJoints: Map<String, Map<String, List<Float>>> = emptyMap(),
	val swings: Map<String, JsonObject> = emptyMap(),
	/** Expressions (the studio's toggle expressions); they become the project's own, editable in Expressions. */
	val expressions: List<ExpressionEdit> = emptyList(),
) {
	val isEmpty: Boolean get() = this == LayerloomJob()

	/** [base] with this job's build inputs and settings (the one-time setup is applied by [setUp]). */
	fun configure(base: PipelineConfig): PipelineConfig = rig.appliedTo(base).copy(
		layerToggles = toggles, autoSkeleton = skeleton, armJoints = armJoints, layerSwings = swings,
		rigEdits = if (expressions.isEmpty() || base.rigEdits.expressions.isNotEmpty()) base.rigEdits else base.rigEdits.copy(expressions = expressions))

	companion object {
		const val SUFFIX = ".layerloom.json"

		/** The job beside [psd] (`quill.psd` → `quill.layerloom.json`), if there is one. */
		fun besidePsd(psd: Path): Path? = psd.resolveSibling(psd.fileName.toString().substringBeforeLast('.') + SUFFIX)
			.takeIf { Files.isRegularFile(it) }

		fun read(path: Path): LayerloomJob = parse(Json.parseToJsonElement(Files.readString(path)) as JsonObject)

		fun parse(root: JsonObject): LayerloomJob {
			fun obj(key: String) = root[key] as? JsonObject
			fun num(e: JsonElement?) = (e as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }
			val rig = io.github.psd2live.project.WorkspaceSettingsCodec.decodeLayerloom(JsonObject(buildMap {
				obj("hairAnchors")?.let { put("hairAnchors", it) }
				obj("glues")?.let { put("glues", it) }
				obj("turnDepths")?.let { put("turnDepths", it) }
				obj("turnDepthsY")?.let { put("turnDepthsY", it) }
				root["hairTurnDepth"]?.let { put("hairTurnDepth", it) }
				root["hairTurnDepthY"]?.let { put("hairTurnDepthY", it) }
			}))
			return LayerloomJob(
				rig = rig,
				toggles = obj("toggles")?.mapNotNull { (layer, param) ->
					(param as? JsonPrimitive)?.contentOrNull?.filter { it.isLetterOrDigit() || it == '_' }?.take(64)
						?.takeIf { it.isNotEmpty() }?.let { layer to it }
				}?.toMap().orEmpty(),
				skeleton = (root["skeleton"] as? JsonPrimitive)?.contentOrNull?.takeIf { it == "arms" || it == "full" },
				armJoints = obj("armJoints")?.mapNotNull { (side, points) ->
					(points as? JsonObject)?.mapNotNull { (joint, p) ->
						(p as? JsonArray)?.mapNotNull(::num)?.takeIf { it.size == 2 }?.let { joint to it }
					}?.toMap()?.let { side to it }
				}?.toMap().orEmpty(),
				swings = obj("swings")?.mapNotNull { (layer, settings) -> (settings as? JsonObject)?.let { layer to it } }?.toMap().orEmpty(),
				expressions = (root["expressions"] as? JsonArray)?.let(Expressions::fromJob).orEmpty(),
			)
		}

		/** Named toggles as the editor's per-layer toggle overrides, keeping the tag each name gives. */
		fun toggleOverrides(source: SourceArt, toggles: Map<String, String>): Map<String, LayerClassificationOverride> =
			if (toggles.isEmpty()) emptyMap() else source.layers.mapNotNull { layer ->
				val param = toggles[layer.name] ?: return@mapNotNull null
				val semantic = LayerClassifier.classify(layer.name)
				layer.id.raw to LayerClassificationOverride(LayerType.TOGGLE, semantic.tag, semantic.side, param, 0)
			}.toMap()

		/**
		 * The one-time setup on a built model: propose and build in the auto skeleton (as the editor's Skeleton tool
		 * does), then turn per-layer swings into swing edits. [rebuild] builds the model again under a config; the
		 * result is the model and the rig edits it carries, ready to store.
		 */
		suspend fun setUp(model: RigPreviewModel, requested: PipelineConfig, rebuild: suspend (PipelineConfig) -> RigPreviewModel): RigPreviewModel {
			var config = requested
			var built = model
			val mode = config.autoSkeleton
			if (mode != null && config.rigEdits.skeleton == null) {
				val spec = LayerSkeleton.propose(built.analysis, built.baseRig, mode, config.armJoints)
				if (spec.bones.any { it.drawableIds.isNotEmpty() }) {
					config = config.copy(rigEdits = config.rigEdits.copy(skeleton = spec))
					built = rebuild(config)
				} else System.err.println("Warning: skeleton: no limb layers to bind (arms need left/right handwear)")
			}
			if (config.layerSwings.isNotEmpty()) {
				val swung = LayerSwings.apply(built.baseRig, config, built.analysis.layers)
				if (swung != config) built = rebuild(swung)
			}
			return built
		}
	}
}
