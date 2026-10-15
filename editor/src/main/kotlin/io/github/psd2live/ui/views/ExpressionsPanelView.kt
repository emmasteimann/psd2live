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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.ExpressionEdit
import io.github.psd2live.core.ExpressionEntry
import io.github.psd2live.format.model.ExpressionBlend
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/**
 * Layerloom patch (ONE-APP-PLAN S4.1): the Expressions tab, Cubism's expression editor. A list of the project's
 * expressions, and for the selected one its fades and its parameters, each added to, multiplied into or overwriting
 * the value under it. Exported as exp3 with the Cubism model and as VTube Studio hotkeys. Edits are held here while a
 * slider moves and saved (one history step) when it's let go.
 */
@Composable
fun ExpressionsPanelView(viewModel: PSD2LiveViewModel, state: PSD2LiveState) {
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		ExpressionsSection(viewModel, state)
		if (state.previewModel != null) PoseGroupsSection(viewModel, state)
		if (state.previewModel != null) HitAreasSection(viewModel, state)
	}
}

@Composable
private fun ExpressionsSection(viewModel: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val expressions = state.rigEdits.expressions
	val parameters = state.previewModel?.rig?.puppet?.parameters.orEmpty()
	val byId = parameters.associateBy { it.id.raw }
	var selectedId by remember { mutableStateOf<String?>(null) }
	val selected = expressions.firstOrNull { it.id == selectedId } ?: expressions.firstOrNull()
	// The selected expression as being edited: follows the saved one, and holds a slider's values until release.
	var draft by remember(selected) { mutableStateOf(selected) }
	var preview by remember { mutableStateOf(false) }
	fun save(next: ExpressionEdit) { draft = next; viewModel.putExpression(next) }
	LaunchedEffect(preview, draft) { if (preview) draft?.let { viewModel.previewExpression(it.entries) } }

	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		if (parameters.isEmpty()) {
			Text(tr("expressions.noModel"), color = colors.textMuted, style = typography.caption)
			return@Column
		}
		Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
			CompactButton(tr("expressions.new"), { viewModel.createExpression(tr("expressions.newName")) { selectedId = it } }, isPrimary = true)
			CompactButton(tr("expressions.starters"), { viewModel.addStarterExpressions() })
		}
		if (expressions.isEmpty()) {
			Text(tr("expressions.empty"), color = colors.textMuted, style = typography.caption.copy(fontSize = 11.sp, lineHeight = 15.sp))
			return@Column
		}
		Column(Modifier.fillMaxWidth().background(colors.inputBackground, RoundedCornerShape(4.dp)).padding(2.dp)) {
			for (e in expressions) {
				val isSelected = e.id == selected?.id
				Row(Modifier.fillMaxWidth().height(24.dp)
					.background(if (isSelected) colors.selection else colors.inputBackground, RoundedCornerShape(3.dp))
					.clickable { selectedId = e.id }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
					Text(e.name, color = if (isSelected) colors.selectionText else colors.textPrimary, style = typography.body.copy(fontSize = 12.sp),
						maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
					Text(tr("expressions.count", e.entries.size), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp))
					CompactIconButton({ if (selectedId == e.id) selectedId = null; viewModel.deleteExpression(e.id) }, size = 20.dp, tooltip = tr("expressions.delete")) {
						IconTrash(Modifier.size(11.dp))
					}
				}
			}
		}
		val e = draft ?: return@Column
		CompactSectionHeader(e.name)
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			Label(tr("expressions.name"))
			var name by remember(e.id, e.name) { mutableStateOf(e.name) }
			CompactTextField(name, { name = it }, Modifier.weight(1f), onCommit = {
				val trimmed = name.trim()
				if (trimmed.isNotEmpty() && trimmed != e.name) save(e.copy(name = trimmed)) else name = e.name
			})
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			Label(tr("expressions.fadeIn"))
			CompactNumberSpinner(e.fadeIn.toDouble(), { save(e.copy(fadeIn = it.toFloat())) }, Modifier.width(70.dp), 0.0, 10.0, 0.1, 2, "s")
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			Label(tr("expressions.fadeOut"))
			CompactNumberSpinner(e.fadeOut.toDouble(), { save(e.copy(fadeOut = it.toFloat())) }, Modifier.width(70.dp), 0.0, 10.0, 0.1, 2, "s")
		}
		Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
			CompactButton(if (preview) tr("expressions.previewOn") else tr("expressions.preview"), { preview = !preview }, isPrimary = preview)
			CompactButton(tr("expressions.capture"), {
				val captured = viewModel.capturedExpressionEntries()
				save(e.copy(entries = e.entries.filter { old -> captured.none { it.parameter == old.parameter } } + captured))
			})
			CompactButton(tr("expressions.addParameter"), {
				val free = parameters.firstOrNull { p -> e.entries.none { it.parameter == p.id.raw } } ?: return@CompactButton
				save(e.copy(entries = e.entries + ExpressionEntry(free.id.raw, ExpressionBlend.ADD, 0f)))
			}, enabled = parameters.any { p -> e.entries.none { it.parameter == p.id.raw } })
		}
		if (e.entries.isEmpty()) Text(tr("expressions.noEntries"), color = colors.textMuted, style = typography.caption.copy(fontSize = 11.sp, lineHeight = 15.sp))
		e.entries.forEachIndexed { index, entry ->
			val p = byId[entry.parameter]
			fun change(next: ExpressionEntry, commit: Boolean) {
				val updated = e.copy(entries = e.entries.toMutableList().also { it[index] = next })
				if (commit) save(updated) else draft = updated
			}
			Column(Modifier.fillMaxWidth().background(colors.panelElevated, RoundedCornerShape(4.dp)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					val choices = (listOf(entry.parameter) + parameters.map { it.id.raw }.filter { id -> e.entries.none { it.parameter == id } }).distinct()
					CompactDropdown(choices, entry.parameter, { change(entry.copy(parameter = it), true) }, Modifier.weight(1f), height = 22.dp,
						itemLabel = { id -> byId[id]?.name?.takeIf { it.isNotBlank() && it != id }?.let { "$it ($id)" } ?: if (id in byId) id else tr("expressions.missing", id) })
					CompactDropdown(ExpressionBlend.entries, entry.blend, { change(entry.copy(blend = it, value = defaultValue(it)), true) },
						Modifier.width(92.dp), height = 22.dp, itemLabel = { tr("expressions.blend.${it.name.lowercase()}") })
					CompactIconButton({ save(e.copy(entries = e.entries.filterIndexed { i, _ -> i != index })) }, size = 20.dp, tooltip = tr("expressions.remove")) {
						IconClose(tint = colors.textMuted, modifier = Modifier.size(9.dp))
					}
				}
				Row(verticalAlignment = Alignment.CenterVertically) {
					val range = when (entry.blend) {
						ExpressionBlend.MULTIPLY -> 0f..2f
						ExpressionBlend.ADD -> p?.let { -(it.max - it.min)..(it.max - it.min) } ?: -1f..1f
						ExpressionBlend.OVERWRITE -> p?.let { it.min..it.max } ?: -1f..1f
					}
					CompactSlider(entry.value.coerceIn(range.start, range.endInclusive), { change(entry.copy(value = it), false) },
						onValueChangeFinished = { draft?.let(viewModel::putExpression) }, modifier = Modifier.weight(1f), valueRange = range)
					Spacer(Modifier.width(4.dp))
					Text(if (entry.blend == ExpressionBlend.MULTIPLY) "×%.2f".format(entry.value) else "%+.2f".format(entry.value),
						color = colors.textPrimary, style = typography.monoSmall.copy(fontSize = 10.sp), modifier = Modifier.width(48.dp))
				}
			}
		}
	}
}

/**
 * Layerloom patch (ONE-APP-PLAN S4.3): pose groups, Cubism's pose3. In each group one part shows at a time (arm A /
 * arm B); a runtime fades between them. Each change is saved at once (one history step).
 */
@Composable
private fun PoseGroupsSection(viewModel: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val groups = state.rigEdits.poseGroups
	val fade = state.rigEdits.poseFadeIn
	val parts = state.previewModel?.rig?.puppet?.parts.orEmpty()
	val names = parts.associate { it.id.raw to it.name }
	val links = state.rigEdits.poseLinks
	val used = groups.flatten().toSet() + links.values.flatten()
	fun save(next: List<List<String>>, nextFade: Float = fade, nextLinks: Map<String, List<String>> = links) =
		viewModel.putPoseGroups(next.filter { it.isNotEmpty() }, nextFade, nextLinks.filterKeys { k -> next.any { k in it } })
	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		CompactSectionHeader(tr("pose.title"))
		Text(tr("pose.help"), color = colors.textMuted, style = typography.caption.copy(fontSize = 11.sp, lineHeight = 15.sp))
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			val free = parts.filter { it.id.raw !in used }
			CompactButton(tr("pose.newGroup"), { save(groups + listOf(free.take(2).map { it.id.raw })) }, enabled = free.size >= 2)
			Spacer(Modifier.weight(1f))
			Label(tr("expressions.fadeIn"))
			CompactNumberSpinner(fade.toDouble(), { save(groups, it.toFloat()) }, Modifier.width(70.dp), 0.0, 10.0, 0.1, 2, "s")
		}
		groups.forEachIndexed { g, group ->
			Column(Modifier.fillMaxWidth().background(colors.panelElevated, RoundedCornerShape(4.dp)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(tr("pose.group", g + 1), color = colors.textPrimary, style = typography.body.copy(fontSize = 12.sp), modifier = Modifier.weight(1f))
					if (group.size < 2) Text(tr("pose.needsTwo"), color = colors.warning, style = typography.caption.copy(fontSize = 10.sp))
					CompactIconButton({ save(groups.filterIndexed { i, _ -> i != g }) }, size = 20.dp, tooltip = tr("pose.deleteGroup")) { IconTrash(Modifier.size(11.dp)) }
				}
				group.forEachIndexed { k, part ->
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(names[part] ?: tr("expressions.missing", part), color = if (part in names) colors.textPrimary else colors.textMuted,
							style = typography.caption.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
						CompactIconButton({ save(groups.mapIndexed { i, it -> if (i == g) it.filterIndexed { j, _ -> j != k } else it }) }, size = 18.dp, tooltip = tr("pose.removePart")) {
							IconClose(tint = colors.textMuted, modifier = Modifier.size(8.dp))
						}
					}
					// Linked parts switch with this member (pose3 Link), such as a sleeve with its arm.
					for (linked in links[part].orEmpty()) {
						Row(verticalAlignment = Alignment.CenterVertically) {
							Text("↳ " + (names[linked] ?: tr("expressions.missing", linked)), color = colors.textMuted,
								style = typography.caption.copy(fontSize = 10.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f).padding(start = 18.dp))
							CompactIconButton({ save(groups, nextLinks = links + (part to links[part].orEmpty().filter { it != linked })) }, size = 18.dp,
								tooltip = tr("pose.unlink")) { IconClose(tint = colors.textMuted, modifier = Modifier.size(8.dp)) }
						}
					}
					val linkable = parts.filter { it.id.raw !in used }
					if (linkable.isNotEmpty()) {
						CompactDropdown(listOf("") + linkable.map { it.id.raw }, "", { id ->
							if (id.isNotEmpty()) save(groups, nextLinks = links + (part to links[part].orEmpty() + id))
						}, Modifier.fillMaxWidth().padding(start = 18.dp), height = 20.dp, itemLabel = { if (it.isEmpty()) tr("pose.link") else names[it] ?: it })
					}
				}
				val addable = parts.filter { it.id.raw !in used }
				if (addable.isNotEmpty()) {
					val none = ""
					CompactDropdown(listOf(none) + addable.map { it.id.raw }, none, { id ->
						if (id.isNotEmpty()) save(groups.mapIndexed { i, it -> if (i == g) it + id else it })
					}, Modifier.fillMaxWidth(), height = 22.dp, itemLabel = { if (it.isEmpty()) tr("pose.addPart") else names[it] ?: it })
				}
			}
		}
	}
}

/** Layerloom patch: hit areas, what an app hit-tests for taps and clicks; each a name on one art mesh. */
@Composable
private fun HitAreasSection(viewModel: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val preview = state.previewModel ?: return
	val areas = state.rigEdits.hitAreas
	val meshes = preview.rig.puppet.drawables.filter { it.mesh != null }
	val names = meshes.associate { it.id.raw to it.name }
	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		CompactSectionHeader(tr("hitAreas.title"))
		Text(tr(if (areas.isEmpty()) "hitAreas.generated" else "hitAreas.help"), color = colors.textMuted,
			style = typography.caption.copy(fontSize = 11.sp, lineHeight = 15.sp))
		areas.forEachIndexed { index, (name, mesh) ->
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				var text by remember(index, name) { mutableStateOf(name) }
				CompactTextField(text, { text = it }, Modifier.width(96.dp), onCommit = {
					val trimmed = text.trim()
					if (trimmed.isNotEmpty() && trimmed != name) viewModel.putHitAreas(areas.toMutableList().also { it[index] = trimmed to mesh }) else text = name
				})
				CompactDropdown(meshes.map { it.id.raw }, mesh, { m -> viewModel.putHitAreas(areas.toMutableList().also { it[index] = name to m }) },
					Modifier.weight(1f), height = 22.dp, itemLabel = { names[it] ?: tr("expressions.missing", it) })
				CompactIconButton({ viewModel.putHitAreas(areas.filterIndexed { i, _ -> i != index }) }, size = 20.dp, tooltip = tr("hitAreas.remove")) {
					IconClose(tint = colors.textMuted, modifier = Modifier.size(9.dp))
				}
			}
		}
		CompactButton(tr("hitAreas.add"), {
			val base = if (areas.none { it.first.equals("Head", true) }) "Head" else if (areas.none { it.first.equals("Body", true) }) "Body" else "Area ${areas.size + 1}"
			meshes.firstOrNull()?.let { viewModel.putHitAreas(areas + (base to it.id.raw)) }
		}, enabled = meshes.isNotEmpty())
	}
}

/** Where a parameter starts when its blend changes: no change for Add and Multiply, fully on for Overwrite. */
private fun defaultValue(blend: ExpressionBlend): Float = when (blend) {
	ExpressionBlend.ADD -> 0f; ExpressionBlend.MULTIPLY -> 1f; ExpressionBlend.OVERWRITE -> 1f
}

@Composable
private fun Label(text: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(text, style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted, maxLines = 1, modifier = Modifier.width(92.dp))
}
