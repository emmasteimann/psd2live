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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.core.MotionPresetSettings
import io.github.psd2live.core.MotionPresets
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.SkeletonMotions
import io.github.psd2live.format.compile.ClipSampler
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Card = Color(0xFF1C1C27)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/**
 * The rig's motions (idle, blink, nod, shake and the skeleton's presets): pick one and it plays in the preview, with
 * the physics running; switch each on or off and tune its knobs. Apply writes the motion files - no re-rig.
 */
@Composable
fun MotionsScreen(outcome: RigOutcome, onApplied: (RigOutcome) -> Unit, onCancel: () -> Unit) {
	var config by remember(outcome) { mutableStateOf(outcome.config) }
	var playing by remember { mutableStateOf<String?>("Idle") }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
	var applying by remember { mutableStateOf<Pair<String, Double>?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()
	val basics = listOf("Idle", "Blink", "Nod", "Shake")
	val skeletonPresets = remember(outcome) { if (outcome.config.rigEdits.skeleton != null) SkeletonMotions.presets.map { it.name } else emptyList() }

	LaunchedEffect(config, playing) {
		// The selected motion played on a loop with physics, as VTube Studio would play it.
		val name = playing ?: return@LaunchedEffect
		withContext(Dispatchers.Default) {
			val ir = RigIrCompiler.compile(outcome.model.copy(config = config))
			val clip = ir.clips.firstOrNull { it.name == name } ?: return@withContext
			val session = IrFrameRenderer.open(ir, true)
			val spec = FrameSpec.canvas(ir, 720)
			var t = 0f
			try {
				while (isActive) {
					val values = ClipSampler.valuesAt(clip.copy(loop = true), t)
					val bitmap = session.render(values, 1f / 30f, spec, null).toImageBitmap()
					withContext(Dispatchers.Main) { image = bitmap }
					t += 1f / 30f
					delay(25)
				}
			} finally { session.close() }
		}
	}

	fun preset(name: String, change: (MotionPresetSettings) -> MotionPresetSettings): PipelineConfig {
		val settings = change(config.rigEdits.motionPresets[name] ?: MotionPresetSettings())
		return config.copy(rigEdits = config.rigEdits.copy(motionPresets = config.rigEdits.motionPresets + (name to settings)))
	}
	fun enabled(name: String): Boolean = when (name) {
		"Idle" -> config.motionIdle; "Blink" -> config.motionBlink; "Nod" -> config.motionNod; "Shake" -> config.motionShake
		else -> config.rigEdits.motionPresets[name]?.disabled != true
	}
	fun setEnabled(name: String, on: Boolean) {
		config = when (name) {
			"Idle" -> config.copy(motionIdle = on); "Blink" -> config.copy(motionBlink = on)
			"Nod" -> config.copy(motionNod = on); "Shake" -> config.copy(motionShake = on)
			else -> preset(name) { it.copy(disabled = !on) }
		}
	}

	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text("Motions for ${outcome.name}", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
			Text("Back without keeping changes", color = Accent, fontSize = 15.sp, modifier = Modifier.clickable { if (applying == null) onCancel() })
		}
		Text("Tap a motion to watch it. The idle plays when nothing else does; the others are one-shots you can trigger.", color = Muted, fontSize = 13.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Box(Modifier.weight(1f).height(860.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017)), contentAlignment = Alignment.Center) {
				image?.let { Image(it, contentDescription = "The motion playing", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
					?: Text("Starting…", color = Muted)
			}
			Column(Modifier.width(370.dp).height(860.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				for (name in basics + skeletonPresets) {
					val on = enabled(name)
					Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (name == playing) Color(0xFF3A3358) else Color(0xFF24222F))
						.clickable { playing = name }.padding(horizontal = 12.dp, vertical = 9.dp),
						horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
						Text(name, color = if (on) Text1 else Muted, fontSize = 14.sp)
						Chip(if (on) "On" else "Off", on) { setEnabled(name, !on) }
					}
					if (name == playing) {
						val settings = config.rigEdits.motionPresets[name] ?: MotionPresetSettings()
						for (knob in MotionPresets.knobs(name)) {
							val value = settings.value(knob)
							if (knob.toggle) {
								Chip("${tr("animation.knob.${knob.label}")}: ${if (value >= 0.5f) "on" else "off"}", value >= 0.5f) {
									config = preset(name) { it.copy(values = it.values + (knob.id to if (value >= 0.5f) 0f else 1f)) }
								}
							} else {
								Text("${tr("animation.knob.${knob.label}")}  ${if (knob.integer) value.roundToInt().toString() else ((value * 100).roundToInt() / 100.0).toString()}",
									color = Muted, fontSize = 12.sp)
								Slider(value, { v -> config = preset(name) { it.copy(values = it.values + (knob.id to (if (knob.integer) v.roundToInt().toFloat() else v))) } },
									valueRange = knob.min..knob.max, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
									modifier = Modifier.height(28.dp))
							}
						}
					}
				}
				val progress = applying
				if (progress != null) {
					Text(progress.first, color = Muted, fontSize = 13.sp)
					LinearProgressIndicator(progress = progress.second.toFloat(), modifier = Modifier.fillMaxWidth(), color = Accent)
				} else Chip("Apply - keep these motions", true, enabled = config != outcome.config) {
					applying = "Writing the runtime files" to 0.2; failure = null
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
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 13.sp)
	}
}
