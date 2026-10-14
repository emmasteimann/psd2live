package io.github.psd2live.tools

import io.github.psd2live.core.Expressions
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.views.ExpressionsPanelView
import java.io.File
import kotlin.test.Test

/**
 * Layerloom patch: the Expressions tab, empty and with the starter set, in English and Japanese.
 * PSD2LIVE_TOOLS=1 LAYERLOOM_PARITY_DIR=<folder with quill.psd> ./gradlew test --tests '*ExpressionsPanelTool'.
 */
class ExpressionsPanelTool {
	@Test fun renderPanel() {
		requireTools()
		val dir = System.getenv("LAYERLOOM_PARITY_DIR") ?: return
		val preview = PSD2LivePipeline().buildPreview(File(dir, "quill.psd").toPath())
		val out = output("expressions-panel")
		keepingLanguage {
			PSD2LiveViewModel().use { vm ->
				for (language in listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE)) {
					I18n.setLanguage(language, persist = false)
					val tag = language.name.lowercase()
					vm.setStateForTest(vm.state.value.copy(previewModel = preview, rigEdits = RigEditOverlay.Empty))
					renderPng(File(out, "$tag-empty.png"), 380, 300) { ExpressionsPanelView(vm, vm.state.value) }
					val starters = Expressions.starters(preview.rig.puppet, emptyList())
					val parts = preview.rig.puppet.parts.map { it.id.raw }
					vm.setStateForTest(vm.state.value.copy(rigEdits = RigEditOverlay(expressions = starters, poseGroups = listOf(parts.drop(1).take(2), parts.take(1)))))
					renderPng(File(out, "$tag-starters.png"), 380, 1100) { ExpressionsPanelView(vm, vm.state.value) }
					renderPng(File(out, "$tag-parameters.png"), 380, 160) { io.github.psd2live.ui.views.ParametersListView(vm.state.value, vm) }
					val head = preview.rig.puppet.parts.first()
					val isolated = preview.copy(rig = preview.rig.copy(puppet = preview.rig.puppet.copy(parts = preview.rig.puppet.parts.map {
						if (it.id == head.id) it.copy(groupMode = org.umamo.runtime.model.PartGroupMode.Isolated) else it })))
					vm.setStateForTest(vm.state.value.copy(previewModel = isolated))
					renderPng(File(out, "$tag-parts.png"), 380, 560) { io.github.psd2live.ui.views.PartsPanelView(vm, vm.state.value, head.id.raw) }
					vm.setStateForTest(vm.state.value.copy(previewModel = preview))
				}
			}
		}
	}
}
