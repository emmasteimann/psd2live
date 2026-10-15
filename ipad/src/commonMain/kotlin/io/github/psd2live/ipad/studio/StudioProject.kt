package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A studio project, the same object the web studio keeps (`src/project.js`): the drawing as `source`, and `parts`
 * bottom to top, each image a full-canvas PNG data URL. It is held as the JSON itself, so every field this app doesn't
 * know is saved back exactly as it came - a project edited on the iPad still opens on the Mac with nothing lost.
 * Edits make a new project; nothing here changes in place.
 */
class StudioProject(val json: JsonObject) {
	val id: String get() = json.str("id") ?: ""
	val name: String get() = json.str("name") ?: "Untitled"
	val width: Int get() = json["width"]?.jsonPrimitive?.intOrNull ?: 0
	val height: Int get() = json["height"]?.jsonPrimitive?.intOrNull ?: 0
	val source: String get() = json.str("source") ?: ""
	val parts: List<StudioPart> by lazy { (json["parts"] as? JsonArray)?.map { StudioPart(it.jsonObject) } ?: emptyList() }
	val showRemaining: Boolean? get() = (json["showRemaining"] as? JsonPrimitive)?.booleanOrNull
	val historyCount: Int get() = (json["history"] as? JsonArray)?.size ?: 0

	/** Made of generated pieces (scripts/gensnap-project.py): the drawing is only the reference. */
	val isGeneratedParts: Boolean get() = (json["generatedParts"] as? JsonPrimitive)?.booleanOrNull == true ||
		((json["history"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("event")?.startsWith("Generated parts") == true

	fun part(id: String?): StudioPart? = parts.firstOrNull { it.id == id }

	/** This project with [changes] set at the top level (a null value stores JSON null) and [event] in its history. */
	fun with(changes: Map<String, JsonElement>, event: String? = null, partId: String? = null): StudioProject {
		val next = json.toMutableMap()
		next.putAll(changes)
		next["updatedAt"] = JsonPrimitive(nowIso())
		if (event != null) {
			val entry = buildMap { put("at", JsonPrimitive(nowIso())); if (partId != null) put("partId", JsonPrimitive(partId)); put("event", JsonPrimitive(event)) }
			next["history"] = JsonArray(((json["history"] as? JsonArray) ?: JsonArray(emptyList())).plus(JsonObject(entry)).takeLast(250))
		}
		return StudioProject(JsonObject(next))
	}

	fun withParts(parts: List<StudioPart>, event: String? = null, partId: String? = null) =
		with(mapOf("parts" to JsonArray(parts.map { it.json })), event, partId)

	/**
	 * The web's `updatePart`: [changes] merged into part [id]; the hand-off checklist is cleared (the art changed) and
	 * [event] goes in the history.
	 */
	fun updatePart(id: String, changes: Map<String, JsonElement?>, event: String): StudioProject =
		with(mapOf("parts" to JsonArray(parts.map { if (it.id == id) it.with(changes).json else it.json }), "handoffChecks" to JsonObject(emptyMap())), event, id)

	/** The `.layerloom.json` text the web studio's "Save project" writes. */
	fun fileText(): String = JsonObject(json.toMutableMap().apply {
		remove("checkpointAt"); remove("replacedAt"); remove("selectedExportId")
		put("format", JsonPrimitive("layerloom-project")); put("version", JsonPrimitive(1))
	}).toString()

	/** The file name the web studio gives a saved project. */
	val fileName: String get() = (name.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "Layerloom" }) + ".layerloom.json"

	companion object {
		private val json = Json { ignoreUnknownKeys = true }

		/**
		 * A project from a `.layerloom.json` file (or a slot), checked the way the web's Open project checks it: the
		 * format and version, a PNG drawing, 1-100 parts with ids and names, a size under 16 megapixels, PNG images.
		 */
		fun parse(text: String, requireFormat: Boolean = true): StudioProject {
			val root = try { json.parseToJsonElement(text).jsonObject } catch (e: Throwable) { throw IllegalArgumentException("This isn't a Layerloom project file.") }
			if (requireFormat && (root.str("format") != "layerloom-project" || root["version"]?.jsonPrimitive?.intOrNull != 1))
				throw IllegalArgumentException("This isn't a Layerloom project file (format layerloom-project, version 1).")
			val project = StudioProject(JsonObject(root - "format"))
			require(project.source.startsWith("data:image/png;base64,")) { "The project's drawing is missing or isn't a PNG." }
			require(project.width > 0 && project.height > 0 && project.width.toLong() * project.height <= 16_000_000L) { "The project's size isn't valid." }
			val parts = root["parts"] as? JsonArray
			require(parts != null && parts.size in 1..100) { "The project has no parts." }
			for (p in project.parts) {
				require(p.json.str("id") != null && p.json.str("name") != null && p.json.str("group") != null) { "A part is missing its id, name or group." }
				for (field in listOf("mask", "cutout", "extractionMask")) p.json.str(field)?.let { require(it.startsWith("data:image/png;base64,")) { "${p.name}'s $field isn't a PNG." } }
			}
			return project
		}

		/** The parts a new project starts with (the web's `templates`). */
		val templates = listOf(
			"Front Bangs" to "Hair", "Side Bangs (L)" to "Hair", "Side Bangs (R)" to "Hair", "Face Skin" to "Face",
			"Left Eye" to "Eyes", "Right Eye" to "Eyes", "Eyebrows" to "Eyes", "Mouth" to "Face", "Left Sleeve" to "Body",
			"Right Sleeve" to "Body", "Front Skirt" to "Clothing", "Left Leg" to "Body", "Right Leg" to "Body",
		)

		/** A new project around the drawing [sourcePng] (PNG bytes), with the template parts - the web's `newProject`. */
		fun create(name: String, sourcePng: ByteArray, width: Int, height: Int): StudioProject = StudioProject(JsonObject(mapOf(
			"version" to JsonPrimitive(1), "id" to JsonPrimitive(uuid()), "name" to JsonPrimitive(name),
			"source" to JsonPrimitive(pngDataUrl(sourcePng)), "width" to JsonPrimitive(width), "height" to JsonPrimitive(height),
			"parts" to JsonArray(templates.map { (n, g) -> StudioPart.blank(n, g).json }),
			"history" to JsonArray(emptyList()), "updatedAt" to JsonPrimitive(nowIso()),
		)))
	}
}

/** One part of a project (a layer to separate): the web's part object, kept whole. */
class StudioPart(val json: JsonObject) {
	val id: String get() = json.str("id") ?: ""
	val name: String get() = json.str("name") ?: ""
	val group: String get() = json.str("group") ?: "Custom"
	val visible: Boolean get() = (json["visible"] as? JsonPrimitive)?.booleanOrNull != false
	/** The painted selection, a full-canvas PNG data URL. */
	val mask: String? get() = json.str("mask")
	/** The part's art. */
	val cutout: String? get() = json.str("cutout")
	/** What the part took out of the drawing ("Remaining artwork" shows the rest). */
	val extractionMask: String? get() = json.str("extractionMask")
	val feather: Int get() = json["feather"]?.jsonPrimitive?.intOrNull ?: 0
	val imported: Boolean get() = (json["imported"] as? JsonPrimitive)?.booleanOrNull == true
	val repairs: List<JsonObject> get() = (json["repairs"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

	fun with(changes: Map<String, JsonElement?>): StudioPart = StudioPart(JsonObject(json.toMutableMap().apply {
		for ((k, v) in changes) put(k, v ?: JsonNull)
	}))

	companion object {
		fun blank(name: String, group: String) = StudioPart(JsonObject(mapOf(
			"id" to JsonPrimitive(uuid()), "name" to JsonPrimitive(name), "group" to JsonPrimitive(group),
			"visible" to JsonPrimitive(true), "mask" to JsonNull, "repairs" to JsonArray(emptyList()),
		)))
		val groups = listOf("Hair", "Face", "Eyes", "Body", "Clothing", "Accessories", "Custom")
	}
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

@OptIn(ExperimentalEncodingApi::class)
fun pngDataUrl(png: ByteArray): String = "data:image/png;base64," + Base64.encode(png)

/** The image bytes inside a `data:` URL. */
@OptIn(ExperimentalEncodingApi::class)
fun dataUrlBytes(url: String): ByteArray = Base64.decode(url, url.indexOf(',') + 1)

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun uuid(): String = kotlin.uuid.Uuid.random().toString()

@OptIn(kotlin.time.ExperimentalTime::class)
fun nowIso(): String = kotlin.time.Clock.System.now().toString()
