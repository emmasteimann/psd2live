package io.github.psd2live.ipad.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer

/** A minimal zip writer: files stored as they are (PNGs are already compressed), with CRC-32 - any unzip opens it. */
class Zip {
	private class Entry(val name: ByteArray, val crc: Int, val size: Int, val offset: Int)
	private val out = Buffer()
	private val entries = mutableListOf<Entry>()

	fun add(path: String, data: ByteArray) {
		val name = path.encodeToByteArray()
		val crc = crc32(data)
		val offset = out.size.toInt()
		out.writeIntLe(0x04034b50); out.writeShortLe(20); out.writeShortLe(0x0800); out.writeShortLe(0) // version, UTF-8 names, stored
		out.writeShortLe(0); out.writeShortLe(0x21) // time, date (1980-01-01)
		out.writeIntLe(crc); out.writeIntLe(data.size); out.writeIntLe(data.size)
		out.writeShortLe(name.size); out.writeShortLe(0); out.write(name); out.write(data)
		entries += Entry(name, crc, data.size, offset)
	}

	fun add(path: String, text: String) = add(path, text.encodeToByteArray())

	fun bytes(): ByteArray {
		val start = out.size.toInt()
		for (e in entries) {
			out.writeIntLe(0x02014b50); out.writeShortLe(20); out.writeShortLe(20); out.writeShortLe(0x0800); out.writeShortLe(0)
			out.writeShortLe(0); out.writeShortLe(0x21); out.writeIntLe(e.crc); out.writeIntLe(e.size); out.writeIntLe(e.size)
			out.writeShortLe(e.name.size); out.writeShortLe(0); out.writeShortLe(0); out.writeShortLe(0); out.writeShortLe(0)
			out.writeIntLe(0); out.writeIntLe(e.offset); out.write(e.name)
		}
		val dir = out.size.toInt() - start
		out.writeIntLe(0x06054b50); out.writeShortLe(0); out.writeShortLe(0); out.writeShortLe(entries.size); out.writeShortLe(entries.size)
		out.writeIntLe(dir); out.writeIntLe(start); out.writeShortLe(0)
		return out.readByteArray()
	}

	companion object {
		private val table = IntArray(256) { n -> var c = n; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }; c }
		fun crc32(data: ByteArray): Int { var c = -1; for (b in data) c = table[(c xor b.toInt()) and 0xFF] xor (c ushr 8); return c.inv() }
	}
}

/** The web studio's zip exports: PNG layers + manifest, the expressions pack, and an eye's blink drawing kit. */
object StudioExports {
	private fun safe(s: String) = s.replace(Regex("[^a-zA-Z0-9_-]"), "_")
	fun baseName(p: StudioProject) = safe(p.name).ifEmpty { "Layerloom" }
	private fun png(p: StudioProject, argb: IntArray) = encodePng(p.width, p.height, argb)
	private val pretty = kotlinx.serialization.json.Json { prettyPrint = true }

	private fun preflightJson(p: StudioProject): JsonElement {
		val issues = StudioChecks.preflight(p)
		return JsonObject(mapOf("version" to JsonPrimitive(1), "projectId" to JsonPrimitive(p.id), "projectName" to JsonPrimitive(p.name),
			"blockers" to JsonPrimitive(issues.count { it.blocker }), "reviewItems" to JsonPrimitive(issues.count { !it.blocker }),
			"issues" to JsonArray(issues.map { JsonObject(mapOf("code" to JsonPrimitive(it.code), "severity" to JsonPrimitive(if (it.blocker) "blocker" else "review"),
				"message" to JsonPrimitive(it.message), "partId" to (it.partId?.let { id -> JsonPrimitive(id) } ?: JsonNull))) }),
			"scope" to JsonPrimitive("Structural source-art preflight only. Not mesh, deformation, expression-preset or runtime-rig validation.")))
	}

	/** Every layer (hidden ones too) as NN_name.png, bottom to top, with manifest.json. */
	fun layersZip(p: StudioProject): Pair<String, ByteArray> {
		val zip = Zip(); val layers = StudioRender.layers(p, includeHidden = true)
		val entries = layers.mapIndexed { i, l ->
			val path = "${i.toString().padStart(2, '0')}_${safe(l.name)}.png"
			zip.add(path, png(p, l.layer.full()))
			JsonObject(mapOf("name" to JsonPrimitive(l.name), "partId" to (l.partId?.let { JsonPrimitive(it) } ?: JsonNull), "path" to JsonPrimitive(path),
				"visible" to JsonPrimitive(l.visible), "reconstructed" to JsonPrimitive(l.reconstructed)))
		}
		zip.add("manifest.json", pretty.encodeToString(JsonElement.serializer(), JsonObject(mapOf(
			"version" to JsonPrimitive(1), "name" to JsonPrimitive(p.name), "width" to JsonPrimitive(p.width), "height" to JsonPrimitive(p.height),
			"handoffChecks" to (p.json["handoffChecks"] ?: JsonObject(emptyMap())), "preflight" to preflightJson(p), "layerOrder" to JsonPrimitive("bottom-to-top"),
			"layers" to JsonArray(entries),
			"note" to JsonPrimitive("Source-art preparation for a rigging-ready Live2D handoff. Checklist entries are artist attestations, not automatic validation. Meshes, deformers, parameters, physics and expression presets remain to be created in Cubism."),
		))))
		return "${baseName(p)}_layers.zip" to zip.bytes()
	}

	private fun keyformJson(part: StudioPart, neutralPath: String, variantPaths: Map<String, String>): JsonElement? {
		val (param, neutral, keys) = StudioFace.keyformPlan(part) ?: return null
		val refs = (listOf(Triple(neutral, null as String?, "Current neutral")) + keys.map { (id, v) -> Triple(v, id, StudioFace.variants(part).firstOrNull { it.id == id }?.name ?: "?") })
			.sortedBy { it.first }
		return JsonObject(mapOf("parameterId" to JsonPrimitive(param), "issues" to JsonArray((StudioChecks.keyformIssues(part) ?: emptyList()).map { JsonPrimitive(it) }),
			"references" to JsonArray(refs.map { (v, id, name) -> JsonObject(mapOf("value" to JsonPrimitive(v), "variantId" to (id?.let { JsonPrimitive(it) } ?: JsonNull),
				"name" to JsonPrimitive(name), "path" to JsonPrimitive(if (id == null) neutralPath else variantPaths[id] ?: ""))) }),
			"status" to JsonPrimitive("Reference plan only — meshes and keyforms must be authored and tested in Cubism.")))
	}

	/** Each part with variants or a keyform plan: its neutral art, its variants, and the plans, in manifest.json. */
	fun expressionsZip(p: StudioProject): Pair<String, ByteArray> {
		val zip = Zip(); val layers = StudioRender.layers(p, includeHidden = true)
		val variantsOut = mutableListOf<JsonElement>(); val plans = mutableListOf<JsonElement>()
		for ((index, part) in p.parts.withIndex()) {
			val variants = StudioFace.variants(part)
			if (variants.isEmpty() && part.json["keyformPlan"] !is JsonObject) continue
			val prefix = "${index.toString().padStart(2, '0')}_${safe(part.name)}"
			layers.firstOrNull { it.partId == part.id }?.let { zip.add("$prefix/neutral.png", png(p, it.layer.full())) }
			val paths = mutableMapOf<String, String>()
			for ((i, v) in variants.withIndex()) {
				val path = "$prefix/${i.toString().padStart(2, '0')}_${safe(v.name)}.png"
				zip.add(path, dataUrlBytes(v.art)); paths[v.id] = path
				variantsOut += JsonObject(mapOf("partId" to JsonPrimitive(part.id), "partName" to JsonPrimitive(part.name), "id" to JsonPrimitive(v.id), "name" to JsonPrimitive(v.name), "path" to JsonPrimitive(path)))
			}
			keyformJson(part, "$prefix/neutral.png", paths)?.let { plans += JsonObject(mapOf("partId" to JsonPrimitive(part.id), "partName" to JsonPrimitive(part.name), "plan" to it)) }
		}
		if (variantsOut.isEmpty() && plans.isEmpty()) throw IllegalStateException("Add an expression variant or a keyform plan to a part first.")
		zip.add("manifest.json", pretty.encodeToString(JsonElement.serializer(), JsonObject(mapOf("version" to JsonPrimitive(1), "name" to JsonPrimitive(p.name),
			"width" to JsonPrimitive(p.width), "height" to JsonPrimitive(p.height), "variants" to JsonArray(variantsOut), "keyformPlans" to JsonArray(plans), "preflight" to preflightJson(p),
			"note" to JsonPrimitive("Aligned component artwork/reference variants, not Cubism expression presets or a rigged runtime model.")))))
		zip.add("README.txt", "All PNGs keep the full canvas size. neutral.png is the current component, not the whole character. manifest.json keyformPlans maps the artist's assignments to parameter values and PNG paths. In Cubism: make meshes, add keys at those values, author each shape from the references, then scrub and check. Not .exp3.json expressions, a .cmo3 or a finished rig.\n")
		return "${baseName(p)}_expressions.zip" to zip.bytes()
	}

	/** One eye's parts (neutral + variants), the assembled eye, a crop, the scene, and drawing instructions. */
	fun eyeKitZip(p: StudioProject, side: String): Pair<String, ByteArray> {
		val roles = StudioFace.eyeRolesOf(p, side) ?: throw IllegalStateException("Plan the eye parts first.")
		val ids = roles.mapNotNull { it.partId }.toSet()
		val layers = StudioRender.layers(p, includeHidden = true).filter { it.partId in ids }
		if (layers.isEmpty()) throw IllegalStateException("The $side eye has no art yet.")
		val zip = Zip(); val assembled = IntArray(p.width * p.height); val components = mutableListOf<JsonElement>()
		for ((i, l) in layers.withIndex()) {
			val part = p.part(l.partId)!!; val prefix = "components/${i.toString().padStart(2, '0')}"
			zip.add("$prefix/neutral.png", png(p, l.layer.full())); StudioRender.over(assembled, p.width, l.layer)
			val paths = mutableMapOf<String, String>()
			for ((vi, v) in StudioFace.variants(part).withIndex()) { val path = "$prefix/variant-$vi.png"; zip.add(path, dataUrlBytes(v.art)); paths[v.id] = path }
			components += JsonObject(mapOf("partId" to JsonPrimitive(part.id), "name" to JsonPrimitive(part.name), "roles" to JsonArray(roles.filter { it.partId == part.id }.map { JsonPrimitive(it.key) }),
				"neutralPath" to JsonPrimitive("$prefix/neutral.png"), "variants" to JsonArray(paths.map { (id, path) -> JsonObject(mapOf("id" to JsonPrimitive(id), "path" to JsonPrimitive(path))) }),
				"keyformPlan" to (keyformJson(part, "$prefix/neutral.png", paths) ?: JsonNull)))
		}
		zip.add("references/eye-neutral.png", png(p, assembled))
		FitGeometry.alphaBox(assembled, p.width, p.height)?.let { b ->
			val x0 = b.x0.toInt(); val y0 = b.y0.toInt(); val bw = (b.x1 - b.x0).toInt(); val bh = (b.y1 - b.y0).toInt()
			val dw = bw + 32; val dh = bh + 32; val d = IntArray(dw * dh)
			for (y in 0 until bh) for (x in 0 until bw) d[(y + 16) * dw + x + 16] = assembled[(y0 + y) * p.width + x0 + x]
			zip.add("references/eye-detail.png", encodePng(dw, dh, d))
		}
		zip.add("references/scene-neutral.png", png(p, StudioRender.composite(p)))
		val param = if (side == "left") "ParamEyeLOpen" else "ParamEyeROpen"
		zip.add("manifest.json", pretty.encodeToString(JsonElement.serializer(), JsonObject(mapOf("format" to JsonPrimitive("layerloom-eye-drawing-kit"), "version" to JsonPrimitive(1),
			"createdAt" to JsonPrimitive(nowIso()), "projectId" to JsonPrimitive(p.id), "side" to JsonPrimitive(side), "width" to JsonPrimitive(p.width), "height" to JsonPrimitive(p.height),
			"parameterId" to JsonPrimitive(param), "components" to JsonArray(components),
			"scope" to JsonPrimitive("Artist drawing/reference kit only. No generated blink, meshes, bound keyforms or rig.")))))
		zip.add("START-HERE.txt", "Layerloom $side eye drawing kit\n\n1. Keep components/*/neutral.png and variant PNGs on transparent ${p.width} x ${p.height} canvases. Don't trim or recentre them.\n" +
			"2. references/eye-neutral.png is the eye's parts together; eye-detail.png is a cropped reference only; scene-neutral.png is the whole character.\n" +
			"3. Draw closed / half-open versions in your drawing app on the full canvas.\n" +
			"4. In Layerloom: select the part, Face tab > Import a variant PNG. Then the keyform plan: $param open = 1, closed = 0 (half = 0.5 optional).\n" +
			"5. Build and scrub the real blink in the rig. These references don't prove the iris hides or the lids meet.\n")
		return "${baseName(p)}_${side}_eye-drawing-kit.zip" to zip.bytes()
	}
}
