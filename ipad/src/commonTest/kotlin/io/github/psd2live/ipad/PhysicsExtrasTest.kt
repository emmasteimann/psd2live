package io.github.psd2live.ipad

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PhysicsCatalog
import io.github.psd2live.core.PipelineConfig
import okio.Path.Companion.toPath
import org.umamo.format.psd.PsdReader
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Physics fit and copy-from-another-rig on Boo's real rig (skipped without the files). */
class PhysicsExtrasTest {
	private val boo = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-boo3/boo_v3.psd"
	private val quillPhysics = "/Users/goosible/Downloads/neuro-goose/layerloom/.work/rigjob-quill3/out/quill_v3.physics3.json"

	private fun model(): io.github.psd2live.core.RigPreviewModel? {
		if (!okio.FileSystem.SYSTEM.exists(boo.toPath())) return null
		val source = PsdReader.read(okio.FileSystem.SYSTEM.read(boo.toPath()) { readByteArray() })
		return PSD2LivePipeline().buildPreview(source, PipelineConfig(exportCmo3 = false))
	}

	@Test fun fitScalesAGroupToTheTarget() {
		val model = model() ?: return
		val config = model.config
		val available = model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
		val group = PhysicsCatalog.groups(model.analysis, config, available).first { it.enabled && it.setting.outputs.isNotEmpty() }
		val soft = RigPhysicsExtras.fit(model, config, group.id, 0.5f)
		val full = RigPhysicsExtras.fit(model, config, group.id, 1f)
		fun scales(c: PipelineConfig) = PhysicsCatalog.groups(model.analysis, c, available).first { it.id == group.id }.setting.outputs.map { it.scale }
		assertNotEquals(scales(soft), scales(full), "a softer target scales the outputs differently")
		assertTrue(scales(soft).zip(scales(full)).all { (s, f) -> s <= f + 1e-4f }, "softer never swings further: ${scales(soft)} vs ${scales(full)}")
	}

	@Test fun copiesQuillsPhysicsOntoBoo() {
		val model = model() ?: return
		if (!okio.FileSystem.SYSTEM.exists(quillPhysics.toPath())) return
		val r = RigPhysicsExtras.import(model, model.config, RigPhysicsExtras.readFile(quillPhysics))
		assertTrue(r.ids.isNotEmpty(), "groups come in")
		val available = model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
		val ids = PhysicsCatalog.groups(model.analysis, r.config, available).map { it.id }
		assertTrue(r.ids.all { it in ids }, "the copied groups are in Boo's physics: ${r.ids} in $ids")
	}
}
