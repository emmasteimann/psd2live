package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartGroupMode

/**
 * Layerloom patch (ONE-APP-PLAN S4.5): the Parts tab, Cubism's parts palette. The part tree, and for the selected
 * part its name, visibility and how it draws: with everything else (pass through), as one block in the draw order
 * ("group by draw order"), or offscreen as one layer with its own blending and opacity (Cubism 5.3).
 */
@Composable
internal fun PartsPanelView(viewModel: PSD2LiveViewModel, state: PSD2LiveState, initialSelection: String? = null) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val puppet = state.previewModel?.rig?.puppet
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		if (puppet == null || puppet.parts.isEmpty()) {
			Text(tr("parts.empty"), color = colors.textMuted, style = typography.caption)
			return@Column
		}
		val byId = puppet.parts.associateBy { it.id }
		val childIds = puppet.parts.flatMap { p -> p.children.filterIsInstance<OrgChild.Part>().map { it.id } }.toSet()
		val rows = ArrayList<Pair<Part, Int>>()
		fun walk(p: Part, depth: Int) {
			rows += p to depth
			p.children.filterIsInstance<OrgChild.Part>().mapNotNull { byId[it.id] }.forEach { walk(it, depth + 1) }
		}
		puppet.parts.filter { it.id !in childIds }.forEach { walk(it, 0) }
		var selectedId by remember { mutableStateOf(initialSelection) }
		val selected = rows.firstOrNull { it.first.id.raw == selectedId }?.first
		Column(Modifier.fillMaxWidth().background(colors.inputBackground, RoundedCornerShape(4.dp)).padding(2.dp)) {
			for ((part, depth) in rows) {
				val isSelected = part.id == selected?.id
				Row(Modifier.fillMaxWidth().height(22.dp)
					.background(if (isSelected) colors.selection else colors.inputBackground, RoundedCornerShape(3.dp))
					.clickable { selectedId = part.id.raw }.padding(start = (4 + depth * 12).dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
					CompactIconButton({ viewModel.applyRigStructure("visibility", "part", part.id.raw, buildJsonObject { put("visible", !part.isVisible) }) },
						size = 18.dp, tooltip = tr("parts.visible")) {
						IconEye(visible = part.isVisible, modifier = Modifier.size(11.dp), tint = if (part.isVisible) colors.textPrimary else colors.textMuted)
					}
					Text(part.name, color = if (isSelected) colors.selectionText else colors.textPrimary, style = typography.body.copy(fontSize = 12.sp),
						maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
					when (part.groupMode) {
						PartGroupMode.Grouped -> Text(tr("parts.badge.grouped"), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp))
						PartGroupMode.Isolated -> Text(tr("parts.badge.offscreen"), color = colors.accent, style = typography.caption.copy(fontSize = 10.sp))
						PartGroupMode.PassThrough -> Unit
					}
				}
			}
		}
		val part = selected ?: run {
			Text(tr("parts.pick"), color = colors.textMuted, style = typography.caption.copy(fontSize = 11.sp))
			return@Column
		}
		fun static(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) = viewModel.applyRigStaticNow("part", part.id.raw, *fields)
		CompactSectionHeader(part.name)
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			PartLabel(tr("expressions.name"))
			var name by remember(part.id, part.name) { mutableStateOf(part.name) }
			CompactTextField(name, { name = it }, Modifier.weight(1f), onCommit = {
				val trimmed = name.trim()
				if (trimmed.isNotEmpty() && trimmed != part.name) viewModel.applyRigStructure("rename", "part", part.id.raw, buildJsonObject { put("name", trimmed) })
				else name = part.name
			})
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			PartLabel(tr("parts.drawing"))
			for ((mode, key) in listOf(PartGroupMode.PassThrough to "parts.mode.passThrough", PartGroupMode.Grouped to "parts.mode.grouped", PartGroupMode.Isolated to "parts.mode.offscreen")) {
				CompactToggleChip(tr(key), part.groupMode == mode, { static("group_mode" to JsonPrimitive(io.github.psd2live.core.PartStatics.modeName(mode))) },
					showCheckWhenSelected = false, height = 22.dp)
			}
		}
		Text(tr(when (part.groupMode) {
			PartGroupMode.PassThrough -> "parts.mode.passThrough.help"
			PartGroupMode.Grouped -> "parts.mode.grouped.help"
			PartGroupMode.Isolated -> "parts.mode.offscreen.help"
		}), color = colors.textMuted, style = typography.caption.copy(fontSize = 11.sp, lineHeight = 15.sp))
		if (part.groupMode == PartGroupMode.Isolated) {
			val composite = part.composite
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				PartLabel(tr("inspector.blendMode"))
				CompactDropdown(BlendMode.entries.filter { !it.name.endsWith("Premultiplied") }, composite.blendMode,
					{ static("composite_blend" to JsonPrimitive(it.name)) }, Modifier.weight(1f), height = 22.dp,
					itemLabel = { tr("inspector.blendMode.${it.name.replaceFirstChar(Char::lowercase)}") })
			}
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				PartLabel(tr("inspector.alphaBlend"))
				CompactDropdown(AlphaBlendMode.entries, composite.alphaBlendMode, { static("composite_alpha" to JsonPrimitive(it.name)) },
					Modifier.weight(1f), height = 22.dp, itemLabel = { tr("inspector.alphaBlend.${it.name.lowercase()}") })
			}
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				PartLabel(tr("parts.opacity"))
				var opacity by remember(part.id, composite.opacity) { mutableStateOf(composite.opacity) }
				CompactSlider(opacity, { opacity = it }, onValueChangeFinished = { static("composite_opacity" to JsonPrimitive(opacity)) },
					modifier = Modifier.weight(1f), valueRange = 0f..1f)
				Text("%.0f%%".format(opacity * 100), color = colors.textPrimary, style = typography.monoSmall.copy(fontSize = 10.sp), modifier = Modifier.width(36.dp))
			}
			CompactCheckbox(composite.invertMask, { static("composite_invert_mask" to JsonPrimitive(it)) }, label = tr("parts.invertMask"))
			Text(tr("inspector.blendMode.cubism53"), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp))
		}
		Spacer(Modifier.height(4.dp))
	}
}

@Composable
private fun PartLabel(text: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(text, style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted, maxLines = 1, modifier = Modifier.width(92.dp))
}
