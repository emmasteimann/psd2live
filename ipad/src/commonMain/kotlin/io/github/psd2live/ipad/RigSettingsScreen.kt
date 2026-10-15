package io.github.psd2live.ipad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.RigTuning
import io.github.psd2live.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/** The values worth reaching for first, shown above the groups whatever "advanced" says. */
private val Pinned = listOf("irisShift")

/**
 * The rig's generation settings (the desktop's Rig values): how far the face turns, the eyes, brows, mouth, hair and
 * body move. Apply re-rigs with them; hand tweaks are kept, as they replay over every re-rig.
 */
@Composable
fun RigSettingsScreen(outcome: RigOutcome, onApplied: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	var config by remember(outcome) { mutableStateOf(outcome.config) }
	val tuning = config.rigTuning
	var advanced by remember { mutableStateOf(false) }
	var meshes by remember { mutableStateOf(false) }
	var applying by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val changed = config != outcome.config

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Rig settings for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back to the rig", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (applying == null) onCancel() })
		}
		Text("How far each part moves when the rig is generated. Apply re-rigs with them (about 10 s); your hand tweaks stay.",
			color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
			Chip(if (advanced) "Hide advanced values" else "Show advanced values", advanced) { advanced = !advanced }
			Chip("Defaults", false) { config = config.copy(rigTuning = RigTuning(), headTurnStrength = 1f, bodyStrength = 1f,
				hairTurnDepth = 0f, hairTurnDepthY = 0f, physicsFrontHair = true, physicsBackHair = true) }
			val progress = applying
			if (progress != null) {
				Column(Modifier.width(320.dp)) {
					Text(progress.first, color = Muted, fontSize = 13.sp)
					LinearProgressIndicator(progress = progress.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent)
				}
			} else Chip("Apply and re-rig", true, enabled = changed) {
				applying = "Starting" to 0.0; failure = null
				scope.launch {
					try {
						val updated = withContext(Dispatchers.Default) {
							applyRigConfig(outcome, config) { stage, fraction ->
								scope.launch(Dispatchers.Main) { if (applying != null) applying = stage to fraction }
							}
						}
						applying = null; onApplied(updated)
					} catch (error: Throwable) { applying = null; failure = error.message ?: error::class.simpleName }
				}
			}
		}
		failure?.let { Text("Couldn't re-rig: $it", color = Danger, fontSize = 13.sp) }

		Section("Depth, hair and range")
		ValueRow("Hair wraps round the head", "On a head turn the hair turns round a cylinder on the head: the far side squeezes, " +
			"the near side widens, so it stops looking like a flat cut-out. 0 = flat; 1 = a 45° turn rotates the hair 22.5°.",
			config.hairTurnDepth, 0f..2f, 0.05f) { config = config.copy(hairTurnDepth = it) }
		ValueRow("Hair wraps up and down", "The same on looking up and down: rows bunch at one end and spread at the other.",
			config.hairTurnDepthY, 0f..2f, 0.05f) { config = config.copy(hairTurnDepthY = it) }
		ValueRow("Head turn range", "Scales how far the head turns at the ends of Angle X and Y. 1 = as generated.",
			config.headTurnStrength, 0.3f..2f, 0.05f) { config = config.copy(headTurnStrength = it) }
		ValueRow("Body range", "Scales how far the body moves at the ends of Body X, Y and Z. 1 = as generated.",
			config.bodyStrength, 0.3f..2f, 0.05f) { config = config.copy(bodyStrength = it) }
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Chip(if (config.physicsFrontHair) "Front hair sways: on" else "Front hair sways: off", config.physicsFrontHair) {
				config = config.copy(physicsFrontHair = !config.physicsFrontHair) }
			Chip(if (config.physicsBackHair) "Back hair sways: on" else "Back hair sways: off", config.physicsBackHair) {
				config = config.copy(physicsBackHair = !config.physicsBackHair) }
		}

		Section("Mesh detail per layer")
		Text("A part that bends like stiff paper may need more triangles. Finer re-meshes the layer; hand tweaks on it are " +
			"carried onto the new mesh.", color = Muted, fontSize = 12.sp)
		Chip(if (meshes) "Hide the layers" else "Show the layers", meshes) { meshes = !meshes }
		if (meshes) {
			val vertices = remember(outcome) {
				outcome.model.rig.layerIdByDrawableId.entries.groupBy({ it.value }, { it.key }).mapValues { (_, ids) ->
					ids.sumOf { id -> outcome.model.rig.puppet.drawables.firstOrNull { it.id.raw == id }?.mesh?.vertexCount ?: 0 } }
			}
			for (layer in paintableLayers(outcome)) {
				val id = layer.id.raw
				val current = config.meshOverrides[id]
				val detail = current?.let { detailOf(it.interiorDensity) } ?: detailOf(config.meshInteriorDensity)
				val changedHere = current != outcome.config.meshOverrides[id]
				ValueRow("${layer.name}  (${vertices[id] ?: 0} vertices${if (changedHere) ", changed" else ""})",
					"Coarser ← → finer", detail, 0f..1f, 0.05f) { t ->
					val base = current ?: io.github.psd2live.core.MeshSettings(interiorDensity = config.meshInteriorDensity, maxEdgeDistance = config.meshMaxEdgeDistance)
					config = config.copy(meshOverrides = config.meshOverrides + (id to base.copy(interiorDensity = spacingOf(t), maxEdgeDistance = edgeOf(t))))
				}
			}
		}

		Section(tr("settings.rigTuning.group.eyes") + " - eye contact")
		for (id in Pinned) RigTuning.fieldById[id]?.let { field -> FieldRow(field, tuning) { config = config.copy(rigTuning = it) } }

		for (group in RigTuning.Group.entries) {
			val fields = RigTuning.fields.filter { it.group == group && it.id !in Pinned && (advanced || !it.advanced) }
			if (fields.isEmpty()) continue
			Section(tr("settings.rigTuning.group.${group.name.lowercase()}"))
			for (field in fields) FieldRow(field, tuning) { config = config.copy(rigTuning = it) }
		}
	}
}

@Composable
private fun Section(title: String) {
	Text(title, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun FieldRow(field: RigTuning.Field, tuning: RigTuning, onChange: (RigTuning) -> Unit) {
	val value = field.get(tuning)
	val unit = when (field.unit) { RigTuning.Unit.DEGREES -> "°"; RigTuning.Unit.PERCENT -> "%"; RigTuning.Unit.TORSO_LENGTHS -> "" }
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Text("${tr("settings.rigTuning.${field.id}")}  ${(value * 10).roundToInt() / 10.0}$unit", color = Text1, fontSize = 14.sp)
			Text(tr("settings.rigTuning.${field.id}.hint", field.default), color = Muted, fontSize = 12.sp)
		}
		Slider(value, { v -> onChange(tuning.with(field.id, (v / field.step).roundToInt() * field.step)) }, valueRange = field.range,
			colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent), modifier = Modifier.width(380.dp).height(32.dp))
	}
}

@Composable
private fun ValueRow(label: String, hint: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, onChange: (Float) -> Unit) {
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Text("$label  ${(value * 100).roundToInt() / 100.0}", color = Text1, fontSize = 14.sp)
			Text(hint, color = Muted, fontSize = 12.sp)
		}
		Slider(value, { v -> onChange(((v / step).roundToInt() * step).coerceIn(range)) }, valueRange = range,
			colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent), modifier = Modifier.width(380.dp).height(32.dp))
	}
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}

/** Mesh detail 0 (coarse) .. 1 (fine) to the interior point spacing, px: 70 .. 10; 0.5 is the default 40. */
private fun spacingOf(detail: Float) = 70f - 60f * detail
private fun detailOf(spacing: Float) = ((70f - spacing) / 60f).coerceIn(0f, 1f)
/** ... and the outline's point spacing, px: 9 .. 3; 0.5 is the default 6. */
private fun edgeOf(detail: Float) = 9f - 6f * detail
