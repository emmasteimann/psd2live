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
	var tuning by remember(outcome) { mutableStateOf(outcome.config.rigTuning) }
	var advanced by remember { mutableStateOf(false) }
	var applying by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val changed = tuning != outcome.config.rigTuning

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Rig settings for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back to the rig", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (applying == null) onCancel() })
		}
		Text("How far each part moves when the rig is generated. Apply re-rigs with them (about 10 s); your hand tweaks stay.",
			color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
			Chip(if (advanced) "Hide advanced values" else "Show advanced values", advanced) { advanced = !advanced }
			Chip("Defaults", false) { tuning = RigTuning() }
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
							buildRig(outcome.name, outcome.source, outcome.config.copy(rigTuning = tuning), RigFiles.nowSeconds()) { stage, fraction ->
								scope.launch(Dispatchers.Main) { if (applying != null) applying = stage to fraction }
							}
						}
						applying = null; onApplied(updated)
					} catch (error: Throwable) { applying = null; failure = error.message ?: error::class.simpleName }
				}
			}
		}
		failure?.let { Text("Couldn't re-rig: $it", color = Danger, fontSize = 13.sp) }

		Section(tr("settings.rigTuning.group.eyes") + " - eye contact")
		for (id in Pinned) RigTuning.fieldById[id]?.let { field -> FieldRow(field, tuning) { tuning = it } }

		for (group in RigTuning.Group.entries) {
			val fields = RigTuning.fields.filter { it.group == group && it.id !in Pinned && (advanced || !it.advanced) }
			if (fields.isEmpty()) continue
			Section(tr("settings.rigTuning.group.${group.name.lowercase()}"))
			for (field in fields) FieldRow(field, tuning) { tuning = it }
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
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}
