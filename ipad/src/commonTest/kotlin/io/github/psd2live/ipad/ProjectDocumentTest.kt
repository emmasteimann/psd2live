package io.github.psd2live.ipad

import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.project.config
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a saved iPad project reads back: every per-layer setting the iPad can make survives the document. */
class ProjectDocumentTest {
	private object EmptyArt : org.umamo.format.art.SourceArt { override val layers = emptyList<org.umamo.format.art.SourceLayer>(); override val widthPx = 1; override val heightPx = 1 }

	@Test fun perLayerSettingsSurviveTheDocument() {
		val config = PipelineConfig(exportCmo3 = false,
			meshOverrides = mapOf("layer-1" to MeshSettings()), drawOrderOverrides = mapOf("layer-1" to 3f),
			layerVisibility = mapOf("layer-2" to false), parentOverrides = mapOf("layer-3" to "layer-1"),
			deletedLayerIds = setOf("layer-4"), layerOverrides = mapOf("layer-5" to LayerClassificationOverride()))
		val back = documentOf(EmptyArt, config).config(PipelineConfig(exportCmo3 = false))
		assertEquals(config.meshOverrides.keys, back.meshOverrides.keys, "mesh detail per layer")
		assertEquals(config.drawOrderOverrides, back.drawOrderOverrides, "draw order")
		assertEquals(config.layerVisibility, back.layerVisibility, "visibility")
		assertEquals(config.parentOverrides, back.parentOverrides, "parents")
		assertEquals(config.deletedLayerIds, back.deletedLayerIds, "deleted layers")
		assertEquals(config.layerOverrides.keys, back.layerOverrides.keys, "classifications")
	}
}
