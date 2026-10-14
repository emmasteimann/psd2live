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
}