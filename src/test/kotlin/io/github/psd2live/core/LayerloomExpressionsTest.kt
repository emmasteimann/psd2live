package io.github.psd2live.core

import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.OutputSink
import io.github.psd2live.format.model.ExpressionBlend
import io.github.psd2live.targets.cubism.Moc3Target
import io.github.psd2live.targets.cubism.VTubeStudioTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Layerloom patch (ONE-APP-PLAN S4.1): authored expressions are edited, saved and exported as Cubism exp3. */
class LayerloomExpressionsTest {
	private val wink = ExpressionEdit("expression_1", "Wink", 0.3f, 0.4f, listOf(
		ExpressionEntry("ParamEyeLOpen", ExpressionBlend.MULTIPLY, 0f),
		ExpressionEntry("ParamMouthForm", ExpressionBlend.OVERWRITE, 1f),
		ExpressionEntry("ParamBrowLY", ExpressionBlend.ADD, 0.5f)))

	@Test
	fun editsRoundTripAndNameUniquely() {
		assertEquals(wink, Expressions.fromJson(Expressions.toJson(wink)))
		val one = Expressions.edit(emptyList(), "put", buildJsonObject { put("expression", Expressions.toJson(wink)) })
		val two = Expressions.edit(one, "put", buildJsonObject { put("expression", buildJsonObject { put("name", "Wink") }) })
		assertEquals(listOf("Wink", "Wink 2"), two.map { it.name })
		assertEquals("expression_2", two[1].id)
		val renamed = Expressions.edit(two, "put", buildJsonObject { put("expression", JsonObject(Expressions.toJson(wink.copy(name = "Smirk")))) })
		assertEquals(listOf("Smirk", "Wink 2"), renamed.map { it.name })
		assertEquals(listOf("Wink 2"), Expressions.edit(renamed, "delete", buildJsonObject { put("id", "expression_1") }).map { it.name })
	}

	@Test
	fun appliesLikeARuntime() {
		val ranges = mapOf("ParamEyeLOpen" to 0f..1f, "ParamMouthForm" to -1f..1f, "ParamBrowLY" to -1f..1f)
		val out = Expressions.apply(mapOf("ParamEyeLOpen" to 1f, "ParamMouthForm" to 0f, "ParamBrowLY" to 0.8f), wink.entries, ranges)
		assertEquals(mapOf("ParamEyeLOpen" to 0f, "ParamMouthForm" to 1f, "ParamBrowLY" to 1f), out)
	}

	@Test
	fun rigJobExpressionsReadInExp3Keys() {
		val items = Json.parseToJsonElement("""[{"name": "Glasses", "fadeIn": 0.4, "parameters": [{"Id": "ParamGlasses", "Value": 1, "Blend": "Add"},
			{"Id": "ParamEyeLOpen", "Value": 0.5, "Blend": "Multiply"}]}, {"name": "Glasses", "parameters": [{"id": "ParamHat", "value": 1}]},
			{"name": "Empty", "parameters": []}]""").jsonArray
		val list = Expressions.fromJob(items)
		assertEquals(listOf("Glasses", "Glasses 2"), list.map { it.name })
		assertEquals(listOf(ExpressionBlend.ADD, ExpressionBlend.MULTIPLY), list[0].entries.map { it.blend })
		assertEquals(0.4f, list[0].fadeIn)
		assertEquals(ExpressionEntry("ParamHat", ExpressionBlend.ADD, 1f), list[1].entries.single())
	}

	/** A real rig: LAYERLOOM_PARITY_DIR holding quill.psd (skipped without it). */
	@Test
	fun cubismExportCarriesExp3AndVtsHotkeys() {
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val base = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"))
		val model = PSD2LivePipeline().buildPreview(Path.of(dir, "quill.psd"),
			base.config.copy(rigEdits = base.config.rigEdits.copy(expressions = listOf(wink, ExpressionEdit("expression_2", "Ghost", entries = listOf(
				ExpressionEntry("ParamNotThere", ExpressionBlend.ADD, 1f)))))))
		val ir = RigIrCompiler.compile(model)
		val files = HashMap<String, ByteArray>()
		VTubeStudioTarget.plan(ir, ExportOptions("quill")).write(OutputSink { path, bytes -> files[path] = bytes })
		val exp = Json.parseToJsonElement(files.getValue("quill.wink.exp3.json").decodeToString()).jsonObject
		assertEquals("Live2D Expression", exp["Type"]!!.jsonPrimitive.content)
		assertEquals(0.3f, exp["FadeInTime"]!!.jsonPrimitive.content.toFloat())
		assertEquals(listOf("Multiply", "Overwrite", "Add"), exp["Parameters"]!!.jsonArray.map { it.jsonObject["Blend"]!!.jsonPrimitive.content })
		assertTrue(files.keys.none { "ghost" in it }, "an expression with no parameters left isn't written")
		val model3 = Json.parseToJsonElement(files.getValue("quill.model3.json").decodeToString()).jsonObject
		val listed = model3["FileReferences"]!!.jsonObject["Expressions"]!!.jsonArray.map { it.jsonObject["File"]!!.jsonPrimitive.content }
		assertEquals(listOf("quill.wink.exp3.json"), listed)
		val vts = Json.parseToJsonElement(files.getValue("quill.vtube.json").decodeToString()).jsonObject
		assertTrue(vts["Hotkeys"]!!.jsonArray.any { it.jsonObject["Action"] == JsonPrimitive("ToggleExpression") && it.jsonObject["File"] == JsonPrimitive("quill.wink.exp3.json") })
	}
}
