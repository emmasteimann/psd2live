package io.github.psd2live.core

import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.targets.cubism.Moc3Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Layerloom patch: the Cubism model carries hit areas and per-mesh user data (LAYERLOOM_PARITY_DIR; skipped without it). */
class CubismExtrasExportTest {
	@Test
	fun model3HasHitAreasAndUserData() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val compiled = RigIrCompiler.compile(PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd")))
		val tagged = compiled.meshes.first().id
		val ir = compiled.copy(meshes = compiled.meshes.map { if (it.id == tagged) it.copy(userData = "tap: wave") else it })
		val files = Moc3Target.bundle(ir, ExportOptions("quill")).files.associate { it.name to it.bytes.decodeToString() }
		val model3 = Json.parseToJsonElement(files.getValue("quill.model3.json")).jsonObject
		val areas = model3["HitAreas"]!!.jsonArray.map { it.jsonObject }
		val meshIds = ir.meshes.mapTo(HashSet()) { it.id }
		assertEquals(setOf("Head", "Body"), areas.mapTo(HashSet()) { it["Name"]!!.jsonPrimitive.content }, "the generated head and body areas")
		assertTrue(areas.all { it["Id"]!!.jsonPrimitive.content in meshIds }, "each area names an art mesh of the model")
		assertEquals("quill.userdata3.json", model3["FileReferences"]!!.jsonObject["UserData"]!!.jsonPrimitive.content)
		val userData = Json.parseToJsonElement(files.getValue("quill.userdata3.json")).jsonObject
		val entry = userData["UserData"]!!.jsonArray.single().jsonObject
		assertEquals(listOf("ArtMesh", tagged, "tap: wave"), listOf("Target", "Id", "Value").map { entry[it]!!.jsonPrimitive.content })
		assertEquals(9, userData["Meta"]!!.jsonObject["TotalUserDataSize"]!!.jsonPrimitive.content.toInt())
	}

	@Test
	fun poseGroupsExportAsPose3() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val base = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"))
		val parts = base.rig.puppet.parts.map { it.id.raw }
		assertTrue(parts.size >= 3, "Quill has parts to group: $parts")
		val groups = listOf(listOf(parts[1], parts[2], "PartNotThere"), listOf(parts[0]))
		val model = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"),
			base.config.copy(rigEdits = base.config.rigEdits.copy(poseGroups = groups, poseFadeIn = 0.3f)))
		val files = Moc3Target.bundle(RigIrCompiler.compile(model), ExportOptions("quill")).files.associate { it.name to it.bytes.decodeToString() }
		val model3 = Json.parseToJsonElement(files.getValue("quill.model3.json")).jsonObject
		assertEquals("quill.pose3.json", model3["FileReferences"]!!.jsonObject["Pose"]!!.jsonPrimitive.content)
		val pose = Json.parseToJsonElement(files.getValue("quill.pose3.json")).jsonObject
		assertEquals("Live2D Pose", pose["Type"]!!.jsonPrimitive.content)
		assertEquals(0.3f, pose["FadeInTime"]!!.jsonPrimitive.content.toFloat())
		val written = pose["Groups"]!!.jsonArray.map { g -> g.jsonArray.map { it.jsonObject["Id"]!!.jsonPrimitive.content } }
		assertEquals(listOf(listOf(parts[1], parts[2])), written, "missing parts and one-part groups are dropped")
	}

	/** The Inspector's 5.3 blend edits replay into the model; an export for Cubism 5.0 falls back and says so. */
	@Test
	fun extendedBlendModesReplayAndFallBack() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val puppet = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd")).rig.puppet
		val id = puppet.drawables.first().id.raw
		val edit = kotlinx.serialization.json.buildJsonObject {
			put("action", kotlinx.serialization.json.JsonPrimitive("static")); put("kind", kotlinx.serialization.json.JsonPrimitive("mesh"))
			put("id", kotlinx.serialization.json.JsonPrimitive(id)); put("blend_mode", kotlinx.serialization.json.JsonPrimitive("Screen"))
			put("alpha_blend", kotlinx.serialization.json.JsonPrimitive("Atop"))
		}
		val edited = RigStructureEdits.apply(puppet, listOf(edit))
		val drawable = edited.drawables.first { it.id.raw == id }
		assertEquals(org.umamo.runtime.model.BlendMode.Screen, drawable.blendMode)
		assertEquals(org.umamo.runtime.model.AlphaBlendMode.Atop, drawable.alphaBlendMode)
		val command = kotlinx.serialization.json.buildJsonObject {
			put("op", kotlinx.serialization.json.JsonPrimitive("structure")); put("edits", kotlinx.serialization.json.JsonArray(listOf(edit)))
		}
		assertTrue(RigCommandDelta.isNoOp(edited, command), "the same edit again changes nothing")
		assertTrue(!RigCommandDelta.isNoOp(puppet, command), "on the original it is a change")
	}

	/** The Parts tab's settings replay, and a model with an offscreen part still exports. */
	@Test
	fun offscreenPartReplaysAndExports() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val preview = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"))
		val puppet = preview.rig.puppet
		val part = puppet.parts.first { p -> p.children.any { it is org.umamo.runtime.model.OrgChild.Drawable } }
		fun p(v: String) = kotlinx.serialization.json.JsonPrimitive(v)
		val edit = kotlinx.serialization.json.JsonObject(mapOf("action" to p("static"), "kind" to p("part"), "id" to p(part.id.raw),
			"group_mode" to p("isolated"), "composite_blend" to p("Screen"), "composite_opacity" to kotlinx.serialization.json.JsonPrimitive(0.5f)))
		val edited = RigStructureEdits.apply(puppet, listOf(edit))
		val after = edited.parts.first { it.id == part.id }
		assertEquals(org.umamo.runtime.model.PartGroupMode.Isolated, after.groupMode)
		assertEquals(org.umamo.runtime.model.BlendMode.Screen, after.composite.blendMode)
		assertEquals(0.5f, after.composite.opacity)
		val command = kotlinx.serialization.json.JsonObject(mapOf("op" to p("structure"), "edits" to kotlinx.serialization.json.JsonArray(listOf(edit))))
		assertTrue(RigCommandDelta.isNoOp(edited, command))
		assertTrue(!RigCommandDelta.isNoOp(puppet, command))
		val ir = io.github.psd2live.targets.cubism.PuppetIr.toIr(edited).let { RigIrCompiler.compile(preview).copy(parts = it.parts) }
		val files = Moc3Target.bundle(ir, ExportOptions("quill")).files
		assertTrue(files.any { it.name == "quill.moc3" && it.bytes.size > 0 }, "the moc exports with an offscreen part")
	}

	@Test
	fun authoredHitAreasReplaceTheGeneratedOnes() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val base = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"))
		val mesh = base.rig.puppet.drawables.first().id.raw
		val model = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"),
			base.config.copy(rigEdits = base.config.rigEdits.copy(hitAreas = listOf("Tap" to mesh, "Gone" to "NoSuchMesh"))))
		val files = Moc3Target.bundle(RigIrCompiler.compile(model), ExportOptions("quill")).files.associate { it.name to it.bytes.decodeToString() }
		val areas = Json.parseToJsonElement(files.getValue("quill.model3.json")).jsonObject["HitAreas"]!!.jsonArray.map { it.jsonObject }
		assertEquals(listOf("Tap" to mesh), areas.map { it["Name"]!!.jsonPrimitive.content to it["Id"]!!.jsonPrimitive.content })
	}
}