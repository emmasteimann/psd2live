package io.github.psd2live.ui.views

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.psd2live.core.ParameterKeyEdits
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.ModalPanel
import io.github.psd2live.ui.components.ModalTone
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.InlineEditorRegions
import io.github.psd2live.ui.components.LocalInlineEditorRegions
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.edit.freshParameterId
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterRange
import org.umamo.edit.withParameterRenamed
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ParameterDefinitionDialog(
    parameter: Parameter?,
    state: PSD2LiveState,
    viewModel: PSD2LiveViewModel,
    parentGroupId: String? = null,
    lockValue: Float? = null,
    onDismiss: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val focusManager = LocalFocusManager.current
    val baseModel = remember { state.previewModel?.rig?.puppet }
    val expectedState = remember { viewModel.currentWorkspaceState() }
    var copying by remember { mutableStateOf(false) }
    val creating = parameter == null || copying
    var id by remember { mutableStateOf(parameter?.id?.raw ?: baseModel?.freshParameterId()?.raw.orEmpty()) }
    var name by remember { mutableStateOf(parameter?.name ?: tr("parameters.newName")) }
    var min by remember { mutableStateOf((parameter?.min ?: -1f).toString()) }
    var default by remember { mutableStateOf((parameter?.default ?: 0f).toString()) }
    var max by remember { mutableStateOf((parameter?.max ?: 1f).toString()) }
    var kind by remember { mutableStateOf(parameter?.kind ?: ParameterKind.NORMAL) }
    var repeat by remember { mutableStateOf(parameter?.repeat ?: false) }
    // Layerloom patch: blend-shape weight limits, as the project keeps them, else as an imported rig has them.
    val initialLimits = remember {
        val pid = parameter?.id?.raw
        pid?.let { state.rigEdits.blendLimits[it] } ?: pid?.let { id0 ->
            baseModel?.let { m -> (m.drawables.flatMap { it.blendShapes } + m.deformers.flatMap { d ->
                (d as? org.umamo.runtime.model.Deformer.Warp)?.blendShapes ?: (d as? org.umamo.runtime.model.Deformer.Rotation)?.blendShapes.orEmpty() })
                .firstOrNull { it.parameterId.raw == id0 && it.limits.isNotEmpty() }?.limits }
        }.orEmpty()
    }
    var limits by remember { mutableStateOf(initialLimits) }
    var deleting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var keyEdits by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var undone by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    val low = min.toFloatOrNull()
    val neutral = default.toFloatOrNull()
    val high = max.toFloatOrNull()
    val valid = id.isNotBlank() && name.isNotBlank() &&
        (!creating || baseModel?.parameters.orEmpty().none { it.id.raw == id.trim() }) &&
        low != null && neutral != null && high != null && low.isFinite() && neutral.isFinite() && high.isFinite() &&
        low < high && neutral in low..high
    val normalizedKeyEdits = keyEdits.map { JsonObject(it + ("parameter" to JsonPrimitive(id.trim()))) }
    val staged = remember(baseModel, id, name, low, neutral, high, valid, keyEdits, creating, kind) {
        runCatching {
            check(valid)
            val base = requireNotNull(baseModel)
            val paramId = ParameterId(id.trim())
            val definition = if (creating) base.withParameterCreated(paramId, name.trim(), kind)
            else base.withParameterRenamed(paramId, name.trim())
            normalizedKeyEdits.fold(definition.withParameterRange(paramId, low, neutral, high), ParameterKeyEdits::apply)
        }
    }
    val fallback = remember(baseModel, parameter?.id, keyEdits) {
        val base = baseModel ?: return@remember null
        val existing = parameter ?: return@remember null
        if (keyEdits.isEmpty()) base else runCatching {
            keyEdits.map { JsonObject(it + ("parameter" to JsonPrimitive(existing.id.raw))) }
                .fold(base, ParameterKeyEdits::apply)
        }.getOrNull() ?: base
    }
    val draftModel = staged.getOrNull()
    val axisModel = draftModel ?: if (creating) null else fallback
    val axisParameter = when {
        draftModel != null -> draftModel.parameters.find { it.id.raw == id.trim() }
        else -> axisModel?.parameters?.find { it.id == parameter?.id }
    }
    val busy = state.canvasEditBusy
    fun submit(action: String) {
        // Flush any open inline editors before reading dialog state.
        focusManager.clearFocus(force = true)
        failure = null
        viewModel.saveParameterDefinition(
            action,
            id.trim(),
            name.trim(),
            low ?: 0f,
            neutral ?: 0f,
            high ?: 1f,
            parameterKind = kind,
            keyEdits = if (action == "delete") emptyList() else normalizedKeyEdits,
            expectedState = expectedState,
            parentGroupId = if (creating) parentGroupId else null,
            repeat = if (kind == ParameterKind.NORMAL) repeat else null,
            blendLimits = if (kind == ParameterKind.BLEND_SHAPE && limits != initialLimits) limits else null,
        ) { if (it == null) onDismiss() else failure = it }
    }
    val editorRegions = remember { InlineEditorRegions() }
    val focusState = rememberUpdatedState(focusManager)
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        CompositionLocalProvider(LocalInlineEditorRegions provides editorRegions) {
            ModalPanel(
                modifier = Modifier
                    .width(440.dp)
                    .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) {
                        editorRegions.pressedInside = false
                    }
                    .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Final) {
                        if (!editorRegions.pressedInside) focusState.value.clearFocus(force = true)
                    },
                title = tr(when {
                    deleting -> "parameters.delete"
                    copying -> "parameters.duplicate"
                    parameter == null -> "parameters.create"
                    else -> "parameters.properties"
                }),
                tone = if (deleting) ModalTone.WARNING else ModalTone.NONE,
                onDismiss = { if (deleting) deleting = false else onDismiss() },
                dismissible = !busy,
                scrollable = false,
                bodySpacing = 8.dp,
                footerStart = {
                    if (!creating && !deleting) {
                        CompactButton(tr("parameters.delete"), { deleting = true }, enabled = !busy, danger = true)
                        CompactButton(tr("parameters.copy"), {
                            copying = true
                            deleting = false
                            keyEdits = emptyList()
                            undone = emptyList()
                            failure = null
                            id = baseModel?.freshParameterId()?.raw.orEmpty()
                            name = tr("parameters.copyName", parameter.name)
                        }, enabled = !busy)
                    }
                },
                footer = {
                    CompactButton(tr("parameters.cancel"), { if (deleting) deleting = false else onDismiss() }, enabled = !busy)
                    CompactButton(
                        tr(if (deleting) "parameters.delete" else "parameters.save"),
                        { submit(if (deleting) "delete" else if (creating) "create" else "update") },
                        enabled = !busy && (deleting || (valid && staged.isSuccess)),
                        isPrimary = !deleting,
                        danger = deleting,
                    )
                },
            ) {
                if (deleting) {
                    Text(
                        tr("parameters.deleteWarning", parameter?.name.orEmpty()),
                        style = typography.body,
                        color = colors.textPrimary,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DefinitionField("ID", id, creating && !busy) { id = it }
                        DefinitionField(tr("parameters.fieldName"), name, !busy) { name = it }
                    }
                    if (parameter != null && !copying) {
                        // Locking pins the preview value only; it is not part of the definition.
                        CompactCheckbox(
                            checked = parameter.id in state.lockedParameters,
                            onCheckedChange = { viewModel.toggleParameterLock(parameter.id, lockValue) },
                            label = tr("parameters.lock"),
                        )
                    }
                    if (creating) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactButton(tr("parameters.kindNormal"), {
                                kind = ParameterKind.NORMAL
                            }, enabled = !busy, isPrimary = kind == ParameterKind.NORMAL, height = 22.dp)
                            CompactButton(tr("parameters.kindBlend"), {
                                kind = ParameterKind.BLEND_SHAPE
                                if (min == "-1.0" || min == "-1") min = "0"
                                if (max == "1.0" || max == "1") max = "1"
                                if (default == "0.0" || default == "0") default = "0"
                                if (low == -1f && high == 1f && neutral == 0f) {
                                    min = "0"
                                    default = "0"
                                    max = "1"
                                }
                            }, enabled = !busy, isPrimary = kind == ParameterKind.BLEND_SHAPE, height = 22.dp)
                        }
                    }
                    // Layerloom patch: Cubism 5.3 repeat, for parameters that go round (a wheel, a spinning prop).
                    if (kind == ParameterKind.NORMAL) {
                        CompactCheckbox(checked = repeat, onCheckedChange = { repeat = it }, label = tr("parameters.repeat"), enabled = !busy)
                        if (repeat) Text(tr("parameters.repeatHint"), style = typography.caption, color = colors.textMuted)
                    }
                    if (copying) {
                        Text(tr("parameters.duplicateHint"), style = typography.caption, color = colors.textMuted)
                    }
                    if (!valid) {
                        Text(tr("parameters.invalidDefinition"), style = typography.caption, color = colors.error)
                    }
                    if (axisModel != null && axisParameter != null) {
                        ParameterKeysEditor(
                            parameter = axisParameter,
                            model = axisModel,
                            enabled = !busy && valid && staged.isSuccess,
                            canUndo = keyEdits.isNotEmpty(),
                            canRedo = undone.isNotEmpty(),
                            onUndo = {
                                if (keyEdits.isNotEmpty()) {
                                    undone = undone + keyEdits.last()
                                    keyEdits = keyEdits.dropLast(1)
                                    failure = null
                                }
                            },
                            onRedo = {
                                if (undone.isNotEmpty()) {
                                    keyEdits = keyEdits + undone.last()
                                    undone = undone.dropLast(1)
                                    failure = null
                                }
                            },
                            onRange = { nextMin, nextDefault, nextMax ->
                                min = formatAxisValue(nextMin)
                                default = formatAxisValue(nextDefault)
                                max = formatAxisValue(nextMax)
                            },
                        ) { command ->
                            try {
                                require(keyEdits.size < 127) { tr("parameters.axisEditLimit") }
                                val next = ParameterKeyEdits.apply(draftModel ?: axisModel, command)
                                if (next === (draftModel ?: axisModel)) return@ParameterKeysEditor false
                                keyEdits = keyEdits + command
                                undone = emptyList()
                                failure = null
                                true
                            } catch (error: IllegalArgumentException) {
                                failure = error.message
                                false
                            } catch (error: IllegalStateException) {
                                failure = error.message
                                false
                            }
                        }
                    }
                    if (kind == ParameterKind.BLEND_SHAPE && baseModel != null) {
                        BlendLimitsEditor(limits, baseModel.parameters.filter { it.id.raw != id.trim() && it.kind != ParameterKind.BLEND_SHAPE },
                            enabled = !busy) { limits = it }
                    }
                    if (valid && staged.isFailure) {
                        Text(staged.exceptionOrNull()?.message.orEmpty(), style = typography.caption, color = colors.error)
                    }
                }
                failure?.let { Text(it, style = typography.caption, color = colors.error) }
            }
        }
    }
}

@Composable
private fun RowScope.DefinitionField(label: String, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = typography.caption, color = colors.textMuted)
        CompactTextField(value, onValue, modifier = Modifier.fillMaxWidth(), enabled = enabled, height = 22.dp)
    }
}

/**
 * Layerloom patch (ONE-APP-PLAN S4.6): Cubism's blend-shape weight limits. Each limit is a curve over another
 * parameter capping this blend shape's weight (0 = off, 1 = full); with several, the lowest wins.
 */
@Composable
fun BlendLimitsEditor(
    limits: List<org.umamo.runtime.model.BlendWeightLimit>,
    parameters: List<Parameter>,
    enabled: Boolean,
    onChange: (List<org.umamo.runtime.model.BlendWeightLimit>) -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val byId = parameters.associateBy { it.id.raw }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("blendLimits.title"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textPrimary)
        Text(tr("blendLimits.help"), style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = colors.textMuted)
        limits.forEachIndexed { index, limit ->
            val p = byId[limit.parameterId.raw]
            fun replace(next: org.umamo.runtime.model.BlendWeightLimit) = onChange(limits.toMutableList().also { it[index] = next })
            Column(Modifier.fillMaxWidth().background(colors.panelElevated, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val used = limits.map { it.parameterId.raw }.toSet()
                    val choices = (listOf(limit.parameterId.raw) + parameters.map { it.id.raw }.filter { it !in used }).distinct()
                    io.github.psd2live.ui.components.CompactDropdown(choices, limit.parameterId.raw, { id ->
                        val q = byId[id]
                        replace(org.umamo.runtime.model.BlendWeightLimit(org.umamo.runtime.model.ParameterId(id),
                            if (q == null) limit.points else defaultPoints(q)))
                    }, Modifier.weight(1f), enabled = enabled, height = 22.dp, itemLabel = { byId[it]?.name?.let { n -> "$n ($it)" } ?: it })
                    CompactButton(tr("blendLimits.remove"), { onChange(limits.filterIndexed { i, _ -> i != index }) }, enabled = enabled, height = 22.dp)
                }
                if (p != null) LimitCurve(limit, p)
                limit.points.forEachIndexed { k, point ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tr("blendLimits.at"), style = typography.caption, color = colors.textMuted, modifier = Modifier.width(40.dp))
                        io.github.psd2live.ui.components.CompactNumberSpinner(point.value.toDouble(), { v ->
                            replace(limit.copy(points = limit.points.toMutableList().also { it[k] = point.copy(value = v.toFloat()) }.sortedBy { it.value }))
                        }, Modifier.width(96.dp), (p?.min ?: -1000f).toDouble(), (p?.max ?: 1000f).toDouble(), 0.1, 2, enabled = enabled)
                        Text(tr("blendLimits.weight"), style = typography.caption, color = colors.textMuted, modifier = Modifier.width(48.dp))
                        io.github.psd2live.ui.components.CompactNumberSpinner(point.weight.toDouble(), { w ->
                            replace(limit.copy(points = limit.points.toMutableList().also { it[k] = point.copy(weight = w.toFloat().coerceIn(0f, 1f)) }))
                        }, Modifier.width(80.dp), 0.0, 1.0, 0.05, 2, enabled = enabled)
                        if (limit.points.size > 1) CompactButton("×", { replace(limit.copy(points = limit.points.filterIndexed { i, _ -> i != k })) },
                            enabled = enabled, height = 20.dp)
                    }
                }
                CompactButton(tr("blendLimits.addPoint"), {
                    val last = limit.points.lastOrNull()
                    val v = ((last?.value ?: p?.default ?: 0f) + ((p?.max ?: 1f) - (p?.min ?: 0f)) * 0.25f).coerceAtMost(p?.max ?: Float.MAX_VALUE)
                    replace(limit.copy(points = (limit.points + org.umamo.runtime.model.BlendWeightLimitPoint(v, 1f)).sortedBy { it.value }.distinctBy { it.value }))
                }, enabled = enabled, height = 20.dp)
            }
        }
        val free = parameters.firstOrNull { q -> limits.none { it.parameterId == q.id } }
        CompactButton(tr("blendLimits.add"), {
            if (free != null) onChange(limits + org.umamo.runtime.model.BlendWeightLimit(free.id, defaultPoints(free)))
        }, enabled = enabled && free != null, height = 22.dp)
    }
}

/** A new limit's curve: full weight at the parameter's default, none at its far end. */
private fun defaultPoints(p: Parameter): List<org.umamo.runtime.model.BlendWeightLimitPoint> {
    val far = if (p.max - p.default >= p.default - p.min) p.max else p.min
    return listOf(org.umamo.runtime.model.BlendWeightLimitPoint(p.default, 1f), org.umamo.runtime.model.BlendWeightLimitPoint(far, 0f)).sortedBy { it.value }
}

/** The limit's curve over its parameter's range: weight 1 at the top, 0 at the bottom. */
@Composable
private fun LimitCurve(limit: org.umamo.runtime.model.BlendWeightLimit, p: Parameter) {
    val colors = LocalToolColors.current
    val line = colors.accent; val grid = colors.divider
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(48.dp).background(colors.inputBackground)) {
        val span = (p.max - p.min).coerceAtLeast(1e-6f)
        fun x(v: Float) = (v - p.min) / span * size.width
        fun y(w: Float) = (1f - w) * (size.height - 4f) + 2f
        drawLine(grid, androidx.compose.ui.geometry.Offset(x(p.default), 0f), androidx.compose.ui.geometry.Offset(x(p.default), size.height))
        val steps = 64
        var prev: androidx.compose.ui.geometry.Offset? = null
        for (i in 0..steps) {
            val v = p.min + span * i / steps
            val w = io.github.psd2live.core.BlendLimits.cap(listOf(limit), mapOf(p.id.raw to v), emptyMap())
            val pt = androidx.compose.ui.geometry.Offset(x(v), y(w))
            prev?.let { drawLine(line, it, pt, strokeWidth = 2f) }
            prev = pt
        }
        limit.points.forEach { drawCircle(line, 3f, androidx.compose.ui.geometry.Offset(x(it.value), y(it.weight))) }
    }
}

