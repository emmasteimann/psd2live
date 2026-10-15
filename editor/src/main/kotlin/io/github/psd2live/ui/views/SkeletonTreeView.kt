package io.github.psd2live.ui.views
import io.github.psd2live.ui.EditorPointers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.BoneRole
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonRig
import io.github.psd2live.core.SkeletonSampling
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.core.SkeletonWeights
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.SkeletonPalette
import io.github.psd2live.ui.visualKind
import io.github.psd2live.ui.parameterKeyMarks
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconBone
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconMeshWireframe
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import org.umamo.runtime.model.ParameterId
import kotlin.math.roundToInt
import io.github.psd2live.ui.components.IconAdd

// Same geometry as the hierarchy tree, so the two tabs read as one tree control.
private const val ROW_HEIGHT_DP = 20
private const val INDENT_STEP_DP = 14
private const val BASE_PADDING_DP = 4
/** Horizontal center of the leading icon: 1.dp spacer + half of [ICON_SIZE_DP]. */
private const val DOT_OFFSET_DP = 7
private const val ICON_SIZE_DP = 12
private const val CHEVRON_WIDTH_DP = 10

/**
 * The skeleton tab beside the hierarchy: the bone tree with every bone's bound art meshes under it, in
 * the bone's color - the same color the canvas tints those meshes while the skeleton is being edited.
 *
 * The skeleton is a canvas target like a layer: clicking a bone here (or on the canvas in Object mode)
 * selects it, Deform mode then poses it and Edit mode reshapes it. While editing this tab is the editor:
 * pick a bone to drag its joints on the canvas or click meshes to bind, add and remove bones, tune the
 * selected joint. Leaving Edit mode keeps the edit; Cancel throws it away.
 */
@Composable
fun SkeletonTreeView(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val editor = skeletonEditor(state, viewModel)
	val draft = editor.skeletonDraft
	// The authored skeleton, switched on or not: turning it off keeps the bones, so they stay listed.
	val committed = state.rigEdits.skeleton?.takeIf { it.bones.isNotEmpty() }
		?: state.previewModel?.config?.rigEdits?.skeleton?.takeIf { it.bones.isNotEmpty() }
	val shown = draft ?: committed
	val enabled = shown?.enabled == true
	val baked = state.previewModel?.config?.rigEdits?.skeleton
	val pointCounts = remember(state.previewModel?.rig?.puppet, baked, shown) {
		if (shown != null && shown.enabled && baked == shown) {
			val marks = state.previewModel.rig.puppet.parameterKeyMarks()
			shown.bones.filterNot { it.role.anchor }.associate { bone ->
				bone.parameterId to (marks[ParameterId(bone.parameterId)]?.allKeys?.size ?: 0)
			}
		} else null
	}
	val rig = state.previewModel?.rig
	// Meshes are listed by their layer's name - what the layers panel and a split named them - rather than
	// the drawable id a split piece is given internally.
	val drawableNames = state.previewModel?.let { preview ->
		val layerNames = preview.analysis.layers.associate { it.source.id.raw to it.source.name }
		preview.rig.puppet.drawables.associate { drawable ->
			drawable.id.raw to (layerNames[preview.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw] ?: drawable.name)
		}
	}.orEmpty()
	// Bones start expanded; the map only records the ones folded away.
	val collapsed = remember { mutableStateMapOf<String, Boolean>() }

	if (state.previewModel == null) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			Text(tr("canvas.hierarchy.empty"), style = typography.caption.copy(fontSize = 11.sp),
				color = colors.textMuted, modifier = Modifier.padding(12.dp))
		}
		return
	}

	Column(Modifier.fillMaxSize()) {
		val addLabel = tr("skeleton.panel.add")
		PanelToolbar(labels = if (draft != null) listOf(addLabel) else emptyList(), iconCount = 5, reservedWidth = 96.dp) { labelsShown ->
			if (draft != null) {
				PanelToolButton(addLabel, showLabel = labelsShown > 0, onClick = { editor.addBone() },
					enabled = editor.selectedBoneId != null, tooltip = addLabel) {
					IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
				}
				PanelIconButton(
					onClick = { editor.removeSelectedBone() },
					enabled = draft.bone(editor.selectedBoneId ?: "")?.role?.limb == true,
					tooltip = tr("skeleton.panel.delete"),
				) { IconTrash(Modifier.size(11.dp)) }
				PanelToolbarSeparator()
				for (role in listOf(BoneRole.TAIL, BoneRole.WING)) {
					val present = draft.bones.any { it.role == role }
					CompactToggleChip(
						text = tr(if (role == BoneRole.TAIL) "skeleton.chain.tail" else "skeleton.chain.wing"),
						selected = present,
						onToggle = { editor.setOptionalSkeletonChain(role, !present) },
						height = PanelToolHeight,
					)
				}
			} else if (committed == null) {
				CompactButton(text = tr("skeleton.tree.create"), onClick = { editor.createSkeleton() }, enabled = !editor.skeletonCreating,
					isPrimary = true, height = PanelToolHeight)
			}
			Spacer(Modifier.weight(1f))
			if (committed != null) {
				// The whole armature goes; Skeleton mode then offers to create a new one.
				PanelIconButton(onClick = { editor.deleteSkeleton() }, tooltip = tr("skeleton.tree.delete")) {
					IconTrash(Modifier.size(11.dp), tint = colors.error)
				}
			}
			if (shown != null) {
				PanelExpandCollapseButtons(
					onExpandAll = { collapsed.clear() },
					onCollapseAll = { shown.bones.forEach { collapsed[it.id] = true } },
				)
			}
			if (draft == null && committed != null && enabled) {
				PanelResetButton(onClick = { editor.resetSkeletonPose() }, tooltip = tr("animation.resetPose"))
			}
		}

		if (draft != null) {
			Text(
				buildAnnotatedString {
					withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.SemiBold)) { append(tr("skeleton.tree.editing")) }
					append(" · ")
					append(tr(if (editor.skeletonEditSubTool == io.github.psd2live.ui.SkeletonEditSubTool.EDIT)
						"skeleton.panel.clickMesh" else editor.skeletonEditSubTool.hintKey))
				},
				color = colors.textMuted,
				style = typography.caption.copy(fontSize = 10.sp),
				modifier = Modifier.fillMaxWidth().background(colors.accent.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 3.dp),
			)
		}

		if (shown != null && !enabled) {
			Row(
				Modifier.fillMaxWidth().background(colors.textMuted.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(tr("skeleton.tree.disabled"), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp),
					modifier = Modifier.weight(1f))
				CompactButton(text = tr("skeleton.panel.enable"), onClick = { editor.setSkeletonEnabled(true) }, height = 18.dp)
			}
		}

		if (shown == null) {
			Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
				Text(tr("skeleton.tree.emptyHint"), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.5.sp),
					textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
			}
			return@Column
		}

		val selectedLayers = state.selectedLayerIds + listOfNotNull(state.selectedLayerId)
		Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
			for (bone in shown.topological()) {
				if (bone.parentId != null && lineage(shown, bone).any { collapsed[it.id] == true }) continue
				val expanded = collapsed[bone.id] != true
				BoneRow(
					spec = shown,
					bone = bone,
					expanded = expanded,
					selected = editor.skeletonSelected && bone.id in editor.selectedBoneIds,
					pointCount = pointCounts?.get(bone.parameterId),
					countPending = shown.enabled && pointCounts == null,
					editable = true,
					onToggle = { collapsed[bone.id] = expanded },
					onSelect = { additive -> if (draft != null) editor.selectBone(bone.id, additive) else editor.selectSkeleton(bone.id) },
				)
				// Anchor bones are skinned by the body rig, not by bones; their meshes stay out of the tree.
				if (!expanded || bone.role.anchor) continue
				val hasChildBones = shown.children(bone.id).isNotEmpty()
				bone.drawableIds.forEachIndexed { index, id ->
					val layerId = rig?.layerIdByDrawableId?.get(id) ?: id
					MeshRow(
						spec = shown,
						bone = bone,
						name = drawableNames[id] ?: id,
						isLast = index == bone.drawableIds.lastIndex && !hasChildBones,
						selected = layerId in selectedLayers,
						editable = draft != null,
						// While editing, a mesh row points at its bone: picking the layer would end the edit.
						onPick = { if (draft != null) editor.selectBone(bone.id) else viewModel.selectLayer(layerId) },
						onUnbind = { editor.unbindSkeletonDrawable(id) },
					)
				}
			}
		}

		if (draft != null) {
			Row(
				Modifier.fillMaxWidth().height(30.dp).background(colors.panelElevated)
					.border(BorderStroke(1.dp, colors.divider)).padding(horizontal = 6.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				if (enabled) {
					CompactButton(text = tr("skeleton.panel.disable"), onClick = { editor.setSkeletonEnabled(false) }, height = 22.dp)
				}
				Spacer(Modifier.weight(1f))
				CompactButton(text = tr("skeleton.panel.cancel"), onClick = { editor.cancelSkeletonEdit() }, height = 22.dp)
				CompactButton(text = tr("skeleton.panel.done"), onClick = { editor.finishSkeletonEdit() }, isPrimary = true, height = 22.dp)
			}
		} else {
			val meshes = shown.bones.filterNot { it.role.anchor }.flatMap { it.drawableIds }.distinct().size
			Text(
				tr("skeleton.tree.summary", shown.bones.count { !it.role.anchor }, meshes),
				color = colors.textMuted,
				style = typography.caption.copy(fontSize = 10.sp),
				maxLines = 1,
				modifier = Modifier.fillMaxWidth().background(colors.panelElevated)
					.border(BorderStroke(1.dp, colors.divider)).padding(horizontal = 8.dp, vertical = 3.dp),
			)
		}
	}
}

/** Every ancestor of [bone], nearest first. */
fun lineage(spec: SkeletonSpec, bone: SkeletonBone): List<SkeletonBone> =
	generateSequence(bone.parentId?.let(spec::bone)) { it.parentId?.let(spec::bone) }.toList()

private fun hasNextSibling(spec: SkeletonSpec, bone: SkeletonBone): Boolean =
	spec.children(bone.parentId).lastOrNull()?.id != bone.id

/**
 * Tree guides in the hierarchy's style: a trunk down each open ancestor level, an elbow into this row,
 * and a stub down from this row's icon when its children follow.
 */
private fun Modifier.treeGuides(depth: Int, openLevels: List<Boolean>, isLast: Boolean, hasChildrenBelow: Boolean, color: Color) =
	drawBehind {
		val midY = size.height * 0.5f
		val stroke = 1.2f
		fun levelX(level: Int) = (BASE_PADDING_DP + level * INDENT_STEP_DP + DOT_OFFSET_DP).dp.toPx()
		for (level in 0 until depth - 1) {
			if (openLevels.getOrElse(level) { false }) {
				drawLine(color, Offset(levelX(level), 0f), Offset(levelX(level), size.height), stroke)
			}
		}
		if (depth > 0) {
			val x = levelX(depth - 1)
			drawLine(color, Offset(x, 0f), Offset(x, if (isLast) midY else size.height), stroke)
			drawLine(color, Offset(x, midY), Offset((BASE_PADDING_DP + depth * INDENT_STEP_DP + 1).dp.toPx(), midY), stroke)
		}
		if (hasChildrenBelow) {
			val x = levelX(depth)
			drawLine(color, Offset(x, midY + (ICON_SIZE_DP / 2f).dp.toPx()), Offset(x, size.height), stroke)
		}
	}

/** One bone of the tree: bone icon in its color, fold chevron, name, and the parameter it drives. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun BoneRow(
	spec: SkeletonSpec,
	bone: SkeletonBone,
	expanded: Boolean,
	selected: Boolean,
	pointCount: Int?,
	countPending: Boolean,
	editable: Boolean,
	onToggle: () -> Unit,
	onSelect: (Boolean) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val ancestors = lineage(spec, bone)
	val depth = ancestors.size
	// Level i is open when the ancestor at that depth still has siblings below it.
	val openLevels = ancestors.asReversed().drop(1).map { hasNextSibling(spec, it) }
	val meshCount = if (bone.role.anchor) 0 else bone.drawableIds.size
	val hasChildren = meshCount > 0 || spec.children(bone.id).isNotEmpty()
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val clickable = editable
	var additive by remember { mutableStateOf(false) }
	val textColor = when {
		selected -> colors.selectionText
		bone.role.anchor -> colors.textMuted
		else -> colors.textPrimary
	}

	Row(
		Modifier.fillMaxWidth().height(ROW_HEIGHT_DP.dp)
			.background(
				when {
					selected -> colors.selection
					hovered && clickable -> colors.controlHover.copy(alpha = 0.3f)
					else -> Color.Transparent
				}
			)
			.treeGuides(depth, openLevels, !hasNextSibling(spec, bone), expanded && hasChildren, guideColor(colors.textMuted))
			.hoverable(interaction)
			.onPointerEvent(PointerEventType.Press) { additive = it.keyboardModifiers.isShiftPressed }
			.clickable(enabled = clickable, interactionSource = interaction, indication = null, onClick = { onSelect(additive) })
			.padding(start = (BASE_PADDING_DP + depth * INDENT_STEP_DP).dp, end = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Spacer(Modifier.width(1.dp))
		IconBone(tint = SkeletonPalette.color(spec, bone.id), modifier = Modifier.size(ICON_SIZE_DP.dp), kind = bone.visualKind())
		Spacer(Modifier.width(2.dp))
		Box(
			Modifier.size(CHEVRON_WIDTH_DP.dp)
				.pointerHoverIcon(EditorPointers.hand)
				.clickable(enabled = hasChildren, onClick = onToggle),
			contentAlignment = Alignment.Center,
		) {
			if (hasChildren) {
				IconChevron(expanded = expanded, modifier = Modifier.size(7.dp), tint = if (selected) colors.selectionText else colors.accent)
			}
		}
		Spacer(Modifier.width(2.dp))
		Text(
			bone.name,
			modifier = Modifier.weight(1f),
			color = textColor,
			style = typography.body.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		if (!expanded && meshCount > 0) {
			Text("×$meshCount", color = if (selected) colors.selectionText else colors.accent,
				style = typography.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold))
			Spacer(Modifier.width(4.dp))
		}
		if (!bone.role.anchor) {
			Text(bone.parameterId + when { pointCount != null -> " · $pointCount"; countPending -> " · …"; else -> "" },
				color = if (selected) colors.selectionText.copy(alpha = 0.7f) else colors.textMuted,
				style = typography.monoSmall.copy(fontSize = 9.sp), maxLines = 1)
		}
	}
}

/** A mesh bound to [bone], one level under it, in the bone's color; picks its layer, or unbinds while editing. */
@Composable
private fun MeshRow(
	spec: SkeletonSpec,
	bone: SkeletonBone,
	name: String,
	isLast: Boolean,
	selected: Boolean,
	editable: Boolean,
	onPick: () -> Unit,
	onUnbind: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val ancestors = lineage(spec, bone)
	val depth = ancestors.size + 1
	val openLevels = ancestors.asReversed().drop(1).map { hasNextSibling(spec, it) } + hasNextSibling(spec, bone)
	val color = SkeletonPalette.color(spec, bone.id)
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()

	Row(
		Modifier.fillMaxWidth().height(ROW_HEIGHT_DP.dp)
			.background(
				when {
					selected -> colors.selection
					hovered -> colors.controlHover.copy(alpha = 0.3f)
					else -> Color.Transparent
				}
			)
			.treeGuides(depth, openLevels, isLast, false, guideColor(colors.textMuted))
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null, onClick = onPick)
			.padding(start = (BASE_PADDING_DP + depth * INDENT_STEP_DP).dp, end = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Spacer(Modifier.width(1.dp))
		IconMeshWireframe(tint = color, modifier = Modifier.size(ICON_SIZE_DP.dp))
		Spacer(Modifier.width((CHEVRON_WIDTH_DP + 4).dp))
		Text(
			name,
			modifier = Modifier.weight(1f),
			color = if (selected) colors.selectionText else color,
			style = typography.body.copy(fontSize = 11.sp),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		if (editable && hovered) {
			Box(
				Modifier.size(14.dp)
					.pointerHoverIcon(EditorPointers.hand)
					.clickable(onClick = onUnbind),
				contentAlignment = Alignment.Center,
			) { IconClose(Modifier.size(7.dp), tint = if (selected) colors.selectionText else colors.textMuted) }
		}
	}
}

private fun guideColor(muted: Color) = muted.copy(alpha = 0.4f)
