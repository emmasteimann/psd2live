package io.github.psd2live.application

import io.github.psd2live.core.ExpressionEdit
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerClassifier
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigGeometryTools
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.canvasGeometryCommand
import kotlinx.coroutines.runBlocking
import org.umamo.format.psd.PsdReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layerloom patch (ONE-APP-PLAN S3b): updating a project's art keeps its edits. Needs LAYERLOOM_ART_DIR with v1.psd and
 * v2.psd (Quill with stable layer ids; in v2 a layer is renamed, one moved, one shifted and one removed); skipped without.
 */
class LayerloomArtUpdateTest {
	@Test
	fun editsFollowLayersThroughRenamesAndRemovals() = runBlocking {
		val dir = System.getenv("LAYERLOOM_ART_DIR") ?: return@runBlocking
		val v1 = PsdReader.read(Files.readAllBytes(Path.of(dir, "v1.psd")))
		val v2 = PsdReader.read(Files.readAllBytes(Path.of(dir, "v2.psd")))
		assertTrue(v1.layers.all { it.id.raw.startsWith("lyid:") }, "the PSD's layer ids are read")
		val renamed = "lyid:1035"; val removed = "lyid:1000"
		assertEquals("front hair fringe", v2.layers.single { it.id.raw == renamed }.name)
		fun override(id: String, source: org.umamo.format.art.SourceArt): LayerClassificationOverride {
			val c = LayerClassifier.classify(source.layers.single { it.id.raw == id }.name)
			return LayerClassificationOverride(LayerType.TOGGLE, c.tag, c.side, "ParamTestToggle", 0)
		}
		val config = PipelineConfig()
		val base = WorkspaceDocument(v1, emptyMap(), emptySet(), mapOf(removed to override(removed, v1)), emptyMap(),
			RigEditOverlay(expressions = listOf(ExpressionEdit("expression_1", "Smile"))), WorkspaceSettingsCodec.encode(config))
		val builder = WorkspacePreviewBuilder()
		val first = builder.build(base)
		// A hand edit on the mesh of the layer that will be renamed: its points nudged at rest.
		val mesh = first.rig.layerIdByDrawableId.entries.first { it.value == renamed }.key
		val puppet = first.rig.puppet
		val defaults = puppet.parameters.associate { it.id.raw to it.default }
		val rest = RigGeometryTools.geometry(puppet, "mesh", mesh, defaults)
		val nudged = rest.points.copyOf().also { for (i in it.indices step 2) it[i] += 3f }
		val key = rest.axes.associate { it.parameterId.raw to (defaults[it.parameterId.raw] ?: 0f) }
		// And one on the mesh of the layer that will be removed.
		val doomed = first.rig.layerIdByDrawableId.entries.first { it.value == removed }.key
		val doomedRest = RigGeometryTools.geometry(puppet, "mesh", doomed, defaults)
		val doomedKey = doomedRest.axes.associate { it.parameterId.raw to (defaults[it.parameterId.raw] ?: 0f) }
		val entries = RigAuthoringJournal.compile(puppet, kotlinx.serialization.json.JsonArray(listOf(
			canvasGeometryCommand(EditHierarchyMode.DEFORM, "mesh", mesh, key, nudged),
			canvasGeometryCommand(EditHierarchyMode.DEFORM, "mesh", doomed, doomedKey, doomedRest.points.copyOf().also { it[1] += 5f })))).second
		val edited = base.copy(rigEdits = base.rigEdits.copy(authoringJournal = entries))
		val before = builder.build(edited)
		val editedPoints = RigGeometryTools.geometry(before.rig.puppet, "mesh", mesh, defaults).points
		assertTrue(abs(editedPoints[0] - rest.points[0] - 3f) < 0.01f, "the edit took")

		val (updated, _) = WorkspaceSourceImporter.artUpdated(edited, v2, null)
		// As Update art builds it: against the current model, so the edits merge onto the new generation.
		val merged = builder.build(updated, before)
		val cleaned = WorkspaceSourceImporter.withGoneLayersDeleted(merged, v2, merged.config.rigEdits)
		val after = builder.build(updated.copy(rigEdits = cleaned), merged)
		assertTrue(after.config.rigEdits.authoringJournal.any(io.github.psd2live.core.RigCheckpoint::isRecord), "the merge is checkpointed")
		val record = after.config.rigEdits.authoringJournal.last(io.github.psd2live.core.RigCheckpoint::isRecord)
		println("merge issues: " + io.github.psd2live.core.RigCheckpoint.issues(record).joinToString { "${it.kind}:${it.target}" })
		val orphans = after.rig.layerIdByDrawableId.filterValues { id -> v2.layers.none { it.id.raw == id.substringBefore("::") } }
		println("orphans: $orphans")
		assertTrue(orphans.isEmpty(), "the removed layer's mesh goes with it, even edited")
		assertTrue(removed !in updated.layerOverrides, "settings for the removed layer are dropped")
		assertEquals(listOf("Smile"), updated.rigEdits.expressions.map { it.name }, "rig edits are kept")
		val afterMesh = after.rig.layerIdByDrawableId.entries.first { it.value == renamed }.key
		assertEquals(mesh, afterMesh, "the renamed layer keeps its mesh")
		val afterPoints = RigGeometryTools.geometry(after.rig.puppet, "mesh", afterMesh, after.rig.puppet.parameters.associate { it.id.raw to it.default }).points
		val drift = editedPoints.indices.maxOf { abs(afterPoints[it] - editedPoints[it]) }
		println("layers ${v1.layers.size} -> ${v2.layers.size}; meshes ${puppet.drawables.size} -> ${after.rig.puppet.drawables.size}; hand edit drift $drift")
		assertTrue(drift < 0.01f, "the hand edit survives the rename, the reorder and the removal")
	}
}
