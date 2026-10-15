package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.SkeletonAuthoring
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonSampling
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.BoneHandles
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.SkeletonPalette
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.IkGuideColor
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconBone
import io.github.psd2live.ui.parameterKeyMarks
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.visualKind
import org.umamo.runtime.model.ParameterId
import kotlin.math.roundToInt

private val RowHeight = 23.dp

/**
 * The settings of the selected bone, laid out like the other inspectors: titled sections of label-and-field
 * rows. What has a shape is also a handle on the canvas - the angle limits, the blend band, the symmetry
 * axis, the IK target - and the fields here are the exact values of the same things, so either can be used.
 */
@Composable
fun BoneInspector(editor: CanvasEditor, viewModel: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val spec = editor.currentSkeleton
	val draft = editor.skeletonDraft
	val bone = spec?.bone(editor.selectedBoneId ?: "")
	if (spec == null || bone == null) {
		Text(tr("skeleton.inspector.empty"), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.5.sp),
			modifier = Modifier.padding(12.dp))
		return
	}
	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		InspectorSectionBox(tr("skeleton.section.bone")) { BoneSection(editor, spec, bone, editable = draft != null) }
		if (draft != null && !bone.role.anchor) InspectorSectionBox(tr("skeleton.section.joint")) { JointSection(editor, draft, bone) }
		InspectorSectionBox(tr("skeleton.section.ik")) { IkSection(editor, spec, bone) }
		if (draft != null && editor.skeletonEditSubTool.editsBones) {
			InspectorSectionBox(tr("skeleton.section.transform")) { TransformSection(editor) }
			InspectorSectionBox(tr("skeleton.section.structure")) { StructureSection(editor, draft, bone) }
		}
		if (draft == null) {
			val baked = state.previewModel?.config?.rigEdits?.skeleton
			val pointCounts = state.previewModel?.takeIf { spec.enabled && baked == spec }?.let { preview ->
				val marks = preview.rig.puppet.parameterKeyMarks()
				spec.bones.filterNot { it.role.anchor }.associate { it.parameterId to (marks[ParameterId(it.parameterId)]?.allKeys?.size ?: 0) }
			}
			InspectorSectionBox(tr("skeleton.sampling.title")) { SamplingSection(spec, pointCounts, bone.id, viewModel) }
		}
	}
}

/** Name, ID, kind, parent and the two toggles that shape the chain: the bone's identity in the tree. */
@Composable
private fun BoneSection(editor: CanvasEditor, spec: SkeletonSpec, bone: SkeletonBone, editable: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var name by remember(bone.id, bone.name) { mutableStateOf(bone.name) }
	InspectorFormRow(tr("inspector.name")) {
		CompactTextField(value = name, onValueChange = { name = it }, placeholder = tr("skeleton.author.name"), enabled = editable,
			modifier = Modifier.fillMaxWidth(), height = RowHeight,
			onCommit = { editor.renameBone(bone.id, name) }, onFocusLost = { editor.renameBone(bone.id, name) })
	}
	InspectorFormRow(tr("inspector.id")) {
		CompactTextField(value = bone.parameterId, onValueChange = {}, enabled = false, modifier = Modifier.fillMaxWidth(), height = RowHeight)
	}
	InspectorFormRow(tr("skeleton.row.kind")) {
		IconBone(tint = SkeletonPalette.color(spec, bone.id), modifier = Modifier.size(12.dp), kind = bone.visualKind())
		Spacer(Modifier.width(6.dp))
		Text(tr("skeleton.kind." + bone.visualKind().name.lowercase()), color = colors.textMuted, style = typography.caption.copy(fontSize = 11.sp))
	}
	if (!editable) return
	InspectorFormRow(tr("skeleton.row.parent")) {
		val excluded = spec.descendants(bone.id) + bone.id
		val parents = listOf("" to tr("skeleton.author.noParent")) + spec.bones.filterNot { it.id in excluded }.map { it.id to it.name }
		CompactDropdown(items = parents, selectedItem = parents.first { it.first == (bone.parentId ?: "") },
			onItemSelected = { editor.setSelectedBoneParent(it.first.takeIf(String::isNotEmpty)) }, itemLabel = { it.second },
			modifier = Modifier.fillMaxWidth(), height = RowHeight)
	}
	InspectorFormRow(tr("skeleton.row.connect")) {
		CompactCheckbox(checked = spec.isConnected(bone.id), enabled = bone.parentId != null, label = tr("skeleton.check.connect"),
			onCheckedChange = { editor.setSelectedBoneParent(bone.parentId, it) })
	}
	if (bone.role.limb) {
		InspectorFormRow(tr("skeleton.row.flex")) {
			CompactCheckbox(checked = bone.flex, label = tr("skeleton.check.flex"), onCheckedChange = { editor.setBonesFlex(it) })
		}
	}
	if (bone.role.limb && bone.flex) {
		val chain = generateSequence(bone) { b -> b.parentId?.let(spec::bone)?.takeIf { it.flex } }.last()
		val straight = (spec.descendants(chain.id) + chain.id).any { spec.bone(it)?.drawnBend?.let { d -> d != 0f } == true }
		InspectorFormRow(tr("skeleton.row.chain")) {
			CompactButton(tr(if (straight) "skeleton.author.unstraighten" else "skeleton.author.straighten"), { editor.straightenSelectedChain(!straight) },
				modifier = Modifier.fillMaxWidth(), height = RowHeight)
		}
	}
}

/** How far the bone may turn each way and how wide its blend band is: the fan and bracket on the canvas, as numbers. */
@Composable
private fun JointSection(editor: CanvasEditor, spec: SkeletonSpec, bone: SkeletonBone) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	InspectorFormRow(tr("skeleton.panel.min")) {
		CompactNumberSpinner(bone.minAngle.toDouble(), { editor.setBoneLimits(it.toFloat().coerceIn(-180f, 0f), bone.maxAngle) },
			modifier = Modifier.fillMaxWidth(), min = -180.0, max = 0.0, decimals = 0, unit = "°", height = RowHeight)
	}
	InspectorFormRow(tr("skeleton.panel.max")) {
		CompactNumberSpinner(bone.maxAngle.toDouble(), { editor.setBoneLimits(bone.minAngle, it.toFloat().coerceIn(0f, 180f)) },
			modifier = Modifier.fillMaxWidth(), min = 0.0, max = 180.0, decimals = 0, unit = "°", height = RowHeight)
	}
	BoneHandles.blendRange(spec, bone)?.let { (shown, limit) ->
		InspectorFormRow(tr("skeleton.panel.blend")) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				CompactNumberSpinner(shown.toDouble(), { editor.setBoneBlendWidth(it.toFloat().coerceIn(0f, limit)) },
					modifier = Modifier.weight(1f), min = 0.0, max = limit.toDouble(), decimals = 0, unit = "px", height = RowHeight)
				Spacer(Modifier.width(4.dp))
				CompactToggleChip(text = tr("skeleton.panel.auto"), selected = bone.blendWidth == null, onToggle = { editor.setBoneBlendWidth(null) },
					showCheckWhenSelected = false, height = 22.dp)
			}
		}
	}
	Text(tr("skeleton.inspector.handlesHint"), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp))
}

/** The bone's IK: which way it bends, how the solver is tuned, and the fixed target the tip is pinned to. */
@Composable
private fun IkSection(editor: CanvasEditor, spec: SkeletonSpec, bone: SkeletonBone) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val settings = bone.ik
	// What the pink drawing on the canvas means, in its own color.
	Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
		for ((mark, key) in listOf("■" to "skeleton.ik.legend.pivot", "◎" to "skeleton.ik.legend.tip", "››" to "skeleton.ik.legend.chain")) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(mark, color = IkGuideColor, style = typography.caption.copy(fontSize = 12.sp))
				Spacer(Modifier.width(3.dp))
				Text(tr(key), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp))
			}
		}
	}
	InspectorFormRow(tr("skeleton.ik.bend")) {
		val bends = listOf(0 to tr("skeleton.ik.bend.auto"), 1 to tr("skeleton.ik.bend.clockwise"), -1 to tr("skeleton.ik.bend.counterclockwise"))
		CompactDropdown(bends, bends.first { it.first == settings.bendDirection }, { editor.setSelectedBoneIk(settings.copy(bendDirection = it.first)) },
			itemLabel = { it.second }, modifier = Modifier.fillMaxWidth(), height = RowHeight)
	}
	InspectorFormRow(tr("skeleton.ik.chain")) {
		CompactNumberSpinner(settings.chainLength.toDouble(), { editor.setSelectedBoneIk(settings.copy(chainLength = it.toInt())) },
			modifier = Modifier.fillMaxWidth(), min = 1.0, max = 32.0, height = RowHeight)
	}
	InspectorFormRow(tr("skeleton.ik.iterations")) {
		CompactNumberSpinner(settings.iterations.toDouble(), { editor.setSelectedBoneIk(settings.copy(iterations = it.toInt())) },
			modifier = Modifier.fillMaxWidth(), min = 1.0, max = 256.0, height = RowHeight)
	}
	InspectorFormRow(tr("skeleton.ik.tolerance")) {
		CompactNumberSpinner(settings.tolerancePx.toDouble(), { editor.setSelectedBoneIk(settings.copy(tolerancePx = it.toFloat())) },
			modifier = Modifier.fillMaxWidth(), min = 0.001, max = 10.0, step = 0.01, decimals = 3, unit = "px", height = RowHeight)
	}
	if (editor.skeletonDraft != null) return
	val target = spec.ikTargets[bone.id]
	if (target == null) {
		InspectorFormRow(tr("skeleton.ik.target")) {
			CompactButton(tr("skeleton.ik.pin"), { editor.pinSelectedBone() }, enabled = !bone.role.anchor, modifier = Modifier.fillMaxWidth(), height = RowHeight)
		}
	} else {
		InspectorFormRow(tr("skeleton.ik.target")) {
			Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
				CompactToggleChip(tr("skeleton.ik.enabled"), target.enabled, { editor.updateIkTarget(bone.id, target.copy(enabled = !target.enabled)) }, height = 22.dp)
				CompactButton(tr("skeleton.ik.release"), { editor.updateIkTarget(bone.id, null) }, height = 22.dp)
			}
		}
		InspectorFormRow("X") {
			CompactNumberSpinner(target.x.toDouble(), { editor.updateIkTarget(bone.id, target.copy(x = it.toFloat())) },
				modifier = Modifier.fillMaxWidth(), min = -100000.0, decimals = 1, unit = "px", height = RowHeight)
		}
		InspectorFormRow("Y") {
			CompactNumberSpinner(target.y.toDouble(), { editor.updateIkTarget(bone.id, target.copy(y = it.toFloat())) },
				modifier = Modifier.fillMaxWidth(), min = -100000.0, decimals = 1, unit = "px", height = RowHeight)
		}
		val posed = editor.posedBones().firstOrNull { it.bone.id == bone.id }
		val error = posed?.let { kotlin.math.hypot(it.tailX - target.x, it.tailY - target.y) } ?: 0f
		Text(tr("skeleton.ik.error", "%.2f".format(error)), color = if (error > settings.tolerancePx) colors.warning else colors.textMuted,
			style = typography.caption.copy(fontSize = 10.sp))
	}
}

/** Moving, turning and scaling the selection by exact amounts; dragging a bone on the canvas moves it by eye. */
@Composable
private fun TransformSection(editor: CanvasEditor) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var dx by remember(editor.selectedBoneIds) { mutableStateOf(0.0) }
	var dy by remember(editor.selectedBoneIds) { mutableStateOf(0.0) }
	var angle by remember(editor.selectedBoneIds) { mutableStateOf(0.0) }
	var scale by remember(editor.selectedBoneIds) { mutableStateOf(1.0) }
	Text(tr("skeleton.transform.selected", editor.selectedBoneIds.size), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp))
	InspectorFormRow("X") {
		CompactNumberSpinner(dx, { dx = it }, modifier = Modifier.fillMaxWidth(), min = -100000.0, decimals = 1, unit = "px", height = RowHeight)
	}
	InspectorFormRow("Y") {
		CompactNumberSpinner(dy, { dy = it }, modifier = Modifier.fillMaxWidth(), min = -100000.0, decimals = 1, unit = "px", height = RowHeight)
	}
	InspectorFormRow(tr("skeleton.transform.rotate")) {
		CompactNumberSpinner(angle, { angle = it }, modifier = Modifier.fillMaxWidth(), min = -360.0, max = 360.0, decimals = 1, unit = "°", height = RowHeight)
	}
	InspectorFormRow(tr("skeleton.transform.scale")) {
		CompactNumberSpinner(scale, { scale = it }, modifier = Modifier.fillMaxWidth(), min = 0.01, max = 100.0, step = 0.1, decimals = 2, unit = "×", height = RowHeight)
	}
	InspectorFormRow("") {
		CompactToggleChip(tr("skeleton.transform.children"), selected = editor.transformBoneDescendants,
			onToggle = { editor.transformBoneDescendants = !editor.transformBoneDescendants }, height = 22.dp)
	}
	CompactButton(tr("skeleton.transform.apply"), enabled = editor.selectedBoneIds.isNotEmpty(), modifier = Modifier.fillMaxWidth(), height = RowHeight,
		onClick = { editor.transformSelectedBones(dx.toFloat(), dy.toFloat(), angle.toFloat(), scale.toFloat()); dx = 0.0; dy = 0.0; angle = 0.0; scale = 1.0 })
}

/** What can be done to the selection: widen it, copy or mirror it, split a bone or merge it away. */
@Composable
private fun StructureSection(editor: CanvasEditor, spec: SkeletonSpec, bone: SkeletonBone) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var segments by remember { mutableStateOf(2.0) }
	Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		CompactButton(tr("skeleton.transform.all"), onClick = { editor.selectBones(spec.bones.mapTo(linkedSetOf()) { it.id }) },
			modifier = Modifier.weight(1f), height = RowHeight)
		CompactButton(tr("skeleton.transform.chain"), onClick = {
			val ids = editor.selectedBoneIds
			editor.selectBones(ids + ids.flatMap { spec.descendants(it) })
		}, enabled = editor.selectedBoneIds.isNotEmpty(), modifier = Modifier.weight(1f), height = RowHeight)
	}
	Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		CompactButton(tr("skeleton.structure.copy"), onClick = { editor.duplicateSelectedBones() },
			enabled = editor.selectedBoneIds.isNotEmpty(), modifier = Modifier.weight(1f), height = RowHeight)
		CompactButton(tr("skeleton.structure.mirror"), onClick = { editor.duplicateSelectedBones(mirror = true) },
			enabled = editor.selectedBoneIds.isNotEmpty(), modifier = Modifier.weight(1f), height = RowHeight)
	}
	InspectorFormRow(tr("skeleton.structure.axis")) {
		CompactNumberSpinner((spec.symmetryAxisX ?: editor.model.canvasWidth / 2f).toDouble(), { editor.setBoneSymmetryAxis(it.toFloat()) },
			modifier = Modifier.fillMaxWidth(), min = -100000.0, decimals = 1, unit = "px", height = RowHeight)
	}
	InspectorFormRow("") {
		CompactToggleChip(tr("skeleton.structure.symmetric"), selected = editor.editBonesSymmetrically,
			onToggle = { editor.editBonesSymmetrically = !editor.editBonesSymmetrically }, height = 22.dp)
	}
	InspectorFormRow("") {
		CompactToggleChip(tr("skeleton.structure.transfer"), selected = editor.transferCopiedBoneBindings,
			onToggle = { editor.transferCopiedBoneBindings = !editor.transferCopiedBoneBindings }, height = 22.dp)
	}
	val mirrors = editor.boneMirrorDrawables()
	val selected = editor.selectedBoneIds + if (editor.transformBoneDescendants) editor.selectedBoneIds.flatMap(spec::descendants) else emptyList()
	val matched = spec.bones.filter { it.id in selected }.flatMap { it.drawableIds }.count { it in mirrors }
	Text(tr("skeleton.structure.mirrorHint", matched), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp))
	InspectorFormRow(tr("skeleton.structure.subdivide")) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			CompactNumberSpinner(segments, { segments = it }, modifier = Modifier.weight(1f), min = 2.0, max = 16.0, height = RowHeight)
			Spacer(Modifier.width(4.dp))
			CompactButton(tr("skeleton.structure.apply"), onClick = { editor.subdivideSelectedBone(segments.toInt()) }, height = RowHeight,
				enabled = bone.role.limb && bone.length / segments >= 1f)
		}
	}
	CompactButton(tr("skeleton.structure.dissolve"), onClick = { editor.dissolveSelectedBone() }, modifier = Modifier.fillMaxWidth(), height = RowHeight,
		enabled = SkeletonAuthoring.canDissolve(spec, bone.id))
}

/** Project-wide key sampling of the skeleton's rig, with the point count it produces. */
@Composable
private fun SamplingSection(spec: SkeletonSpec, pointCounts: Map<String, Int>?, selectedBoneId: String?, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val sampling = spec.sampling
	fun change(next: SkeletonSampling) {
		val current = viewModel.state.value.rigEdits.skeleton ?: spec
		if (current.sampling != next) viewModel.setSkeleton(current.copy(sampling = next))
	}
	@Composable
	fun SliderRow(key: String, value: Float, shown: String, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
		InspectorFormRow(tr(key)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				CompactSlider(value = value, onValueChange = onChange, onValueChangeStarted = { viewModel.beginEditorField(key) },
					onValueChangeFinished = { viewModel.endEditorField(key) }, valueRange = range, modifier = Modifier.weight(1f))
				Text(shown, color = colors.textPrimary, style = typography.monoSmall.copy(fontSize = 10.sp), textAlign = TextAlign.End,
					maxLines = 1, modifier = Modifier.width(44.dp))
			}
		}
	}
	SliderRow("skeleton.sampling.jointMesh", sampling.jointMeshSegments.toFloat(), sampling.jointMeshSegments.toString(), 4f..32f) {
		change(sampling.copy(jointMeshSegments = ((it / 2f).roundToInt() * 2).coerceIn(4, 32)))
	}
	SliderRow("skeleton.sampling.tolerance", sampling.tolerancePx, "%.2fpx".format(sampling.tolerancePx), SkeletonSampling.TOLERANCE_RANGE) {
		change(sampling.copy(tolerancePx = ((it * 20f).roundToInt() / 20f).coerceIn(SkeletonSampling.TOLERANCE_RANGE)))
	}
	SliderRow("skeleton.sampling.step", sampling.minimumStepDegrees, "%.1f°".format(sampling.minimumStepDegrees), SkeletonSampling.STEP_RANGE) {
		change(sampling.copy(minimumStepDegrees = ((it * 2f).roundToInt() / 2f).coerceIn(SkeletonSampling.STEP_RANGE)))
	}
	SliderRow("skeleton.sampling.max", sampling.maxMeshKeyforms.toFloat(), sampling.maxMeshKeyforms.toString(),
		SkeletonSampling.MESH_LIMIT_RANGE.first.toFloat()..SkeletonSampling.MESH_LIMIT_RANGE.last.toFloat()) {
		change(sampling.copy(maxMeshKeyforms = ((it / 10f).roundToInt() * 10).coerceIn(SkeletonSampling.MESH_LIMIT_RANGE)))
	}
	val countText = when {
		!spec.enabled -> tr("skeleton.sampling.disabled")
		pointCounts == null -> tr("skeleton.sampling.calculating")
		else -> {
			val total = pointCounts.values.sum()
			val selected = selectedBoneId?.let(spec::bone)?.let { pointCounts[it.parameterId] }
			if (selected == null) tr("skeleton.sampling.points", total) else tr("skeleton.sampling.pointsSelected", total, selected)
		}
	}
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(countText, color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp), modifier = Modifier.weight(1f))
		CompactButton(text = tr("skeleton.sampling.reset"), onClick = { change(SkeletonSampling()) }, enabled = sampling != SkeletonSampling(), height = 20.dp)
	}
}

/** The canvas editor that holds the skeleton's selection: the skeleton is drawn and edited on an edit canvas, and a focused preview canvas hands over to one. */
fun skeletonEditor(state: PSD2LiveState, viewModel: PSD2LiveViewModel): CanvasEditor {
	val canvas = state.activeCanvas.takeIf { it.mode == CanvasMode.EDIT }
		?: state.activeWorkspace.canvases.firstOrNull { it.mode == CanvasMode.EDIT }
		?: state.activeCanvas
	return viewModel.canvasEditorFor(canvas.id)
}
