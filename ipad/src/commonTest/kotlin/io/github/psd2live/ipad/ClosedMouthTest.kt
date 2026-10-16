package io.github.psd2live.ipad

import io.github.psd2live.core.PipelineConfig
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Painted closed-mouth art ("mouth close") smiles and frowns with Mouth Form. When it really draws a line, it is the
 * only mouth at rest - the mouth art beside it and its generated lip line fade in only as the mouth opens. Faint
 * closed art (a few specks) leaves the generated line as the mouth at rest. Boo's and Quill's art (skipped without them).
 */
class ClosedMouthTest {
	private val boo = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"
	private val quill = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-quill3/quill_v3.psd"

	/** The mouth region of [path]'s rig with [hidden] mouth layers hidden, rendered at a pose. */
	private class Mouth(path: String, hidden: (String) -> Boolean) {
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
		private val layers = source.layers.filter { it.name.contains("mouth", true) }
		val outcome = buildRig("closed-mouth-test", source, PipelineConfig(exportCmo3 = false)
			.copy(layerVisibility = layers.filter { hidden(it.name) }.associate { it.id.raw to false }), RigFiles.nowSeconds()) { _, _ -> }
		private val b = layers.map { it.bounds }
		private val l = b.minOf { it.left } - 20f; private val t = b.minOf { it.top } - 20f
		private val w = b.maxOf { it.left + it.width } + 20f - l; private val h = b.maxOf { it.top + it.height } + 20f - t
		private val rest = outcome.preview.parameters.associate { it.id to it.default }
		fun at(pose: Map<String, Float> = emptyMap()) = outcome.preview.render(rest + pose, l, t, w, h, (w * 4).toInt(), (h * 4).toInt()).argb
	}

	/** The mouth art's drawables and their lip ribbons, and whether each fades in on Mouth Open (an opacity grid on it). */
	private fun fades(m: Mouth): List<Boolean> {
		val rig = m.outcome.model.rig
		val mouthIds = m.source.layers.filter { it.name.contains("mouth", true) && !isClose(it.name) }.map { it.id.raw }.toSet()
		val drawables = rig.puppet.drawables.filter { d ->
			val layer = rig.layerIdByDrawableId[d.id.raw]
			layer in mouthIds || mouthIds.any { d.id.raw.startsWith(it) && "_lip_" in d.id.raw } || (layer != null && mouthIds.any { layer.startsWith(it) })
		}
		return drawables.map { d -> d.channelGrids.gridsByChannel[org.umamo.runtime.model.FormChannel.OPACITY]?.axes?.any { it.parameterId.raw == "ParamMouthOpenY" } == true }
	}

	private fun dark(px: IntArray) = px.count { (it ushr 24) > 128 && ((it shr 16 and 255) + (it shr 8 and 255) + (it and 255)) < 450 }
	private fun moved(a: IntArray, z: IntArray) = a.indices.count { a[it] != z[it] }
	private fun isClose(name: String) = name.contains("close", true)

	@Test fun aDrawnClosedMouthIsTheOnlyLineAtRestAndSmiles() {
		if (!okio.FileSystem.SYSTEM.exists(boo.toPath())) return
		// The mouth art and its generated lip line fade in with opening: at rest Boo's drawn closed mouth is the only line.
		val whole = Mouth(boo) { false }
		val f = fades(whole)
		assertTrue(f.isNotEmpty() && f.all { it }, "Boo's mouth art and lip lines fade on Mouth Open: $f")
		// Only the painted closed mouth: Mouth Form bends it (it used to stay put while only the generated line moved).
		val painted = Mouth(boo) { !isClose(it) }
		val still = painted.at()
		val line = dark(still)
		assertTrue(line > 20, "the painted closed mouth draws: $line dark px")
		assertTrue(moved(still, painted.at(mapOf("ParamMouthForm" to 1f))) > line / 3, "a smile moves the painted line")
		assertTrue(moved(still, painted.at(mapOf("ParamMouthForm" to -1f))) > line / 3, "so does a frown")
	}

	@Test fun faintClosedArtKeepsTheGeneratedMouthAtRest() {
		if (!okio.FileSystem.SYSTEM.exists(quill.toPath())) return
		// quill_v3's "mouth close" is a few light specks: the generated line must still draw her mouth at rest.
		val f = fades(Mouth(quill) { false })
		assertTrue(f.isNotEmpty() && f.none { it }, "Quill's generated mouth stays at rest: $f")
	}
}
