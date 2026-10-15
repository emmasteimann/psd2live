package io.github.psd2live.ipad

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.ExpressionEdit
import io.github.psd2live.core.ExpressionEntry
import io.github.psd2live.core.Expressions
import io.github.psd2live.format.model.ExpressionBlend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/**
 * Expressions (Cubism exp3; a VTube Studio hotkey each): pick one, see her wear it, set each parameter it changes and
 * how (added, multiplied, overwritten). Starter expressions to begin from; Apply writes the rig's files - no re-rig.
 */
@Composable
fun ExpressionsScreen(outcome: RigOutcome, onApplied: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	val puppet = outcome.model.rig.puppet
	val params = remember(outcome) { puppet.parameters.associateBy { it.id.raw } }
	val ranges = remember(outcome) { puppet.parameters.associate { it.id.raw to (it.min..it.max) } }
	val defaults = remember(outcome) { puppet.parameters.associate { it.id.raw to it.default } }
	var list by remember(outcome) { mutableStateOf(outcome.config.rigEdits.expressions) }
	var selected by remember(outcome) { mutableStateOf(list.firstOrNull()?.id) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var adding by remember { mutableStateOf(false) }
	var applying by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val current = list.firstOrNull { it.id == selected }

	LaunchedEffect(outcome) {
		snapshotFlow { list.firstOrNull { it.id == selected }?.entries.orEmpty() }.conflate().collect { entries ->
			val pose = Expressions.apply(defaults, entries, ranges)
			image = withContext(Dispatchers.Default) { outcome.preview.render(pose, 900).toImageBitmap() }
		}
	}

	fun update(e: ExpressionEdit) { list = list.map { if (it.id == e.id) e else it } }

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Expressions for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (applying == null) onCancel() })
		}
		Text("Each expression becomes a hotkey in VTube Studio. Pick one to see her wear it.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Box(Modifier.weight(1f).height(860.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017)), contentAlignment = Alignment.Center) {
				image?.let { Image(it, contentDescription = "The rig wearing the expression", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
					?: Text("Drawing…", color = Muted)
			}
			Column(Modifier.width(380.dp).height(860.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Chip("New", false) {
						val e = ExpressionEdit(Expressions.newId(list), Expressions.uniqueName(list, "Expression"))
						list = list + e; selected = e.id
					}
					Chip("Add starters", false, enabled = Expressions.starters(puppet, list).isNotEmpty()) {
						val more = Expressions.starters(puppet, list); list = list + more; selected = selected ?: more.firstOrNull()?.id
					}
				}
				for (e in list) {
					Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (e.id == selected) Color(0xFF3A3358) else Color(0xFF24222F))
						.clickable { selected = e.id; adding = false }.padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
						Text(e.name, color = Text1, fontSize = 14.sp)
						Text("${e.entries.size} parameters", color = Muted, fontSize = 12.sp)
					}
				}
				if (current != null) {
					Text("Name", color = Muted, fontSize = 12.sp)
					BasicTextField(current.name, { name -> update(current.copy(name = Expressions.uniqueName(list, name.ifBlank { "Expression" }, current.id))) },
						textStyle = TextStyle(color = Text1, fontSize = 15.sp), cursorBrush = SolidColor(Accent), singleLine = true,
						modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B283C)).padding(10.dp))
					for (entry in current.entries) {
						val p = params[entry.parameter]
						val range = when (entry.blend) {
							ExpressionBlend.MULTIPLY -> 0f..2f
							ExpressionBlend.ADD -> ranges[entry.parameter]?.let { (it.start - it.endInclusive)..(it.endInclusive - it.start) } ?: -1f..1f
							else -> ranges[entry.parameter] ?: -1f..1f
						}
						Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
							Text("${p?.name ?: entry.parameter}  ${(entry.value * 100).roundToInt() / 100.0}", color = Text1, fontSize = 13.sp, modifier = Modifier.weight(1f))
							Chip(Expressions.blendName(entry.blend), false) {
								val next = ExpressionBlend.entries[(entry.blend.ordinal + 1) % ExpressionBlend.entries.size]
								val value = when (next) { ExpressionBlend.MULTIPLY -> 1f; ExpressionBlend.ADD -> 0f; else -> defaults[entry.parameter] ?: 0f }
								update(current.copy(entries = current.entries.map { if (it.parameter == entry.parameter) ExpressionEntry(it.parameter, next, value) else it }))
							}
							Text("✕", color = Muted, fontSize = 16.sp, modifier = Modifier.padding(start = 10.dp).clickable {
								update(current.copy(entries = current.entries.filter { it.parameter != entry.parameter })) })
						}
						Slider(entry.value.coerceIn(range), { v -> update(current.copy(entries = current.entries.map { if (it.parameter == entry.parameter) it.copy(value = v) else it })) },
							valueRange = range, colors = sliderColors(), modifier = Modifier.height(28.dp))
					}
					Chip(if (adding) "Close the parameter list" else "Add a parameter", adding) { adding = !adding }
					if (adding) for (p in puppet.parameters.filter { pp -> current.entries.none { it.parameter == pp.id.raw } }) {
						Text(p.name, color = Accent, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable {
							update(current.copy(entries = current.entries + ExpressionEntry(p.id.raw, ExpressionBlend.OVERWRITE, p.default)))
						}.padding(vertical = 5.dp))
					}
					Text("Fade in  ${(current.fadeIn * 100).roundToInt() / 100.0} s", color = Muted, fontSize = 12.sp)
					Slider(current.fadeIn, { update(current.copy(fadeIn = it)) }, valueRange = 0f..2f, colors = sliderColors(), modifier = Modifier.height(28.dp))
					Text("Fade out  ${(current.fadeOut * 100).roundToInt() / 100.0} s", color = Muted, fontSize = 12.sp)
					Slider(current.fadeOut, { update(current.copy(fadeOut = it)) }, valueRange = 0f..2f, colors = sliderColors(), modifier = Modifier.height(28.dp))
					Chip("Delete this expression", false) { list = list.filter { it.id != current.id }; selected = list.firstOrNull()?.id }
				}
				val progress = applying
				if (progress != null) {
					Text(progress.first, color = Muted, fontSize = 13.sp)
					LinearProgressIndicator(progress = progress.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent)
				} else Chip("Apply - keep these expressions", true, enabled = list != outcome.config.rigEdits.expressions) {
					applying = "Writing the runtime files" to 0.2; failure = null
					val config = outcome.config.copy(rigEdits = outcome.config.rigEdits.copy(expressions = list))
					scope.launch {
						try {
							val updated = withContext(Dispatchers.Default) {
								applyRigConfig(outcome, config) { stage, fraction -> scope.launch(Dispatchers.Main) { if (applying != null) applying = stage to fraction } }
							}
							applying = null; onApplied(updated)
						} catch (error: Throwable) { applying = null; failure = error.message ?: error::class.simpleName }
					}
				}
				failure?.let { Text("Couldn't apply: $it", color = Danger, fontSize = 13.sp) }
			}
		}
	}
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 13.sp)
	}
}
