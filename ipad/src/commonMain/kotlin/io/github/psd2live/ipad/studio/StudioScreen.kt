package io.github.psd2live.ipad.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.ipad.CanvasTouch
import io.github.psd2live.ipad.CanvasView
import io.github.psd2live.ipad.Exchange
import io.github.psd2live.ipad.RigFiles
import io.github.psd2live.ipad.WebStudioCopy
import io.github.psd2live.ipad.canvasTouch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private val Panel = Color(0xFF1C1C27)
private val Field = Color(0xFF24222F)
private val Text1 = Color(0xFFF2ECFD)
private val Muted = Color(0xFF9793A7)
private val Accent = Color(0xFFC9B7FF)
private val Danger = Color(0xFFFF9A9A)

/** The mask tools, as the web studio's toolbar: brush, edge brush, polygon, pick a piece, pan. */
private enum class Tool(val label: String) { BRUSH("Brush"), EDGE("Edge brush"), POLYGON("Polygon"), SMART("Smart mask"), PICK("Pick piece"), PAN("Pan") }

/**
 * The studio, native: the web studio's project (same files, same slots), drawn and painted with the iPad canvas -
 * fingers pan and pinch, the Pencil paints with pressure, two fingers tap to undo. The first time it opens it copies the
 * web studio's saved projects across.
 */
@OptIn(ExperimentalLayoutApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun NativeStudio(modifier: Modifier, onSendToRig: (psdName: String) -> Unit = {}, onOpenWebStudio: () -> Unit) {
	var project by remember { mutableStateOf<StudioProject?>(null) }
	var loading by remember { mutableStateOf<String?>("Opening the studio…") }
	var selectedId by remember { mutableStateOf<String?>(null) }
	var composite by remember { mutableStateOf<IntArray?>(null) }
	var mask by remember { mutableStateOf<MaskEdit?>(null) }
	var maskTick by remember { mutableStateOf(0L) }
	var tool by remember { mutableStateOf(Tool.BRUSH) }
	var erasing by remember { mutableStateOf(false) }
	var brush by remember { mutableStateOf(70f) }
	var softness by remember { mutableStateOf(0f) }
	var pressure by remember { mutableStateOf(true) }
	var pencilOnly by remember { mutableStateOf(false) }
	var pencilSeen by remember { mutableStateOf(false) }
	var tolerance by remember { mutableStateOf(35f) }
	var showMask by remember { mutableStateOf(true) }
	var panels by remember { mutableStateOf(true) }
	var view by remember { mutableStateOf(CanvasView()) }
	var frame by remember { mutableStateOf<StudioFrame?>(null) }
	var box by remember { mutableStateOf(0f to 0f) }
	var cursor by remember { mutableStateOf<Pair<Offset, Float>?>(null) }
	val polygon = remember { mutableStateListOf<Offset>() }
	var status by remember { mutableStateOf("") }
	var saved by remember { mutableStateOf("") }
	var busy by remember { mutableStateOf<String?>(null) }
	var hasCheckpoint by remember { mutableStateOf(false) }
	var hasPrevious by remember { mutableStateOf(false) }
	val scope = rememberCoroutineScope()
	// Painting and edits, one at a time in order; PNG encoding runs beside them so a stroke never waits on a save.
	val worker = remember { Dispatchers.Default.limitedParallelism(1) }
	val encoder = remember { Dispatchers.Default.limitedParallelism(1) }
	val safe = remember { CoroutineExceptionHandler { _, e -> scope.launch(Dispatchers.Main) { busy = null; status = "Something went wrong: ${e.message ?: e::class.simpleName}" } } }
	var pendingCommit by remember { mutableStateOf<Job?>(null) }
	// The part being fitted by hand (Fit mode takes the whole screen), or null.
	var fitting by remember { mutableStateOf<String?>(null) }
	// Peel and Split apart: the new piece's name, the islands found, their names, and the last split (for Join back).
	var peelName by remember { mutableStateOf("") }
	var splitOpen by remember { mutableStateOf(false) }
	var splitGap by remember { mutableStateOf(12f) }
	var islands by remember { mutableStateOf<StudioPeel.Islands?>(null) }
	var islandsPreview by remember { mutableStateOf<ImageBitmap?>(null) }
	var islandNames by remember { mutableStateOf<List<String>>(emptyList()) }
	var lastSplit by remember { mutableStateOf<Triple<String, StudioPart, List<String>>?>(null) }
	// Peel with the AI: mode, repaint, what's underneath / what to reveal, signed in, and a result waiting for review.
	var peelMode by remember { mutableStateOf("split") }
	var peelRepaint by remember { mutableStateOf(false) }
	var peelBehind by remember { mutableStateOf("") }
	var peelWhat by remember { mutableStateOf("") }
	var aiSignedIn by remember { mutableStateOf(false) }
	var peelPending by remember { mutableStateOf<Triple<String, (StudioProject) -> StudioProject, String?>?>(null) }
	// The inspector's tab; a picture the Check panels show on the canvas instead of the art; clone points.
	var tab by remember { mutableStateOf(InspectorTab.PART) }
	var canvasPreview by remember { mutableStateOf<Pair<IntArray, String>?>(null) }
	var cloneSource by remember { mutableStateOf<Pt?>(null) }
	var cloneDest by remember { mutableStateOf<Pt?>(null) }
	var picking by remember { mutableStateOf<String?>(null) }
	var aiPending by remember { mutableStateOf<StudioRepairAI.Candidate?>(null) }
	// Smart mask: tap mode, points (x, y, 1 keep / 0 exclude), region corners, candidates and the one shown.
	var smartMode by remember { mutableStateOf(1) } // 1 keep, 0 exclude, 2 region
	val smartPoints = remember { mutableStateListOf<Triple<Double, Double, Int>>() }
	val smartCorners = remember { mutableStateListOf<Pt>() }
	var smartCands by remember { mutableStateOf<List<StudioSmartMask.Candidate>>(emptyList()) }
	var smartSel by remember { mutableStateOf(0) }

	val projectNow by rememberUpdatedState(project)
	val maskNow by rememberUpdatedState(mask)
	// The mask as the worker sees it: set on the worker as a part's mask is made, so strokes begun while it loads
	// (pick a part, start drawing at once) queue behind it and land, instead of being dropped.
	val workerMask = remember { arrayOfNulls<MaskEdit>(1) }
	val settings by rememberUpdatedState(BrushSettings(tool, erasing, brush, softness, pressure, tolerance))

	fun bump() { scope.launch(Dispatchers.Main) { maskTick++ } }
	fun change(next: StudioProject) { project = next }

	/** Saves the painted mask into the project a moment after the last stroke (encoding a big PNG takes a while). */
	fun commitMaskSoon(edit: MaskEdit, wait: Long = 700) {
		pendingCommit?.cancel()
		pendingCommit = scope.launch(safe) {
			delay(wait)
			val copy = withContext(worker) { MaskEdit.Snapshot(edit) }
			val url = withContext(encoder) { copy.dataUrl() }
			projectNow?.let { p -> if (p.part(edit.partId) != null && p.part(edit.partId)?.mask != url) change(StudioEdits.setMask(p, edit.partId, url)) }
		}
	}

	suspend fun flushMask() {
		val job = pendingCommit ?: return
		if (job.isActive) {
			job.cancel()
			val edit = maskNow ?: return
			val copy = withContext(worker) { MaskEdit.Snapshot(edit) }
			val url = withContext(encoder) { copy.dataUrl() }
			projectNow?.let { p -> if (p.part(edit.partId)?.mask != url) change(StudioEdits.setMask(p, edit.partId, url)) }
		}
	}

	fun refreshSlots() { hasCheckpoint = StudioStore.exists(StudioStore.Slot.RECOVERY); hasPrevious = StudioStore.exists(StudioStore.Slot.PREVIOUS) }

	/** Replaces the open project, keeping the old one as "previous" (the web's `keepPrevious`). */
	suspend fun replaceWith(next: StudioProject, message: String) {
		flushMask()
		val old = projectNow
		withContext(Dispatchers.Default) { if (old != null && old.historyCount > 0) StudioStore.save(StudioEdits.stamped(old, "replacedAt"), StudioStore.Slot.PREVIOUS) }
		selectedId = null; mask = null; view = CanvasView(); polygon.clear()
		project = next
		status = message
		refreshSlots()
	}

	// First open: copy the web studio's projects across once, then open the current one.
	LaunchedEffect(Unit) {
		if (!StudioStore.copiedFromWeb) {
			loading = "Copying your projects from the web studio…"
			val (slots, error) = suspendCancellableCoroutine { done -> WebStudioCopy.read { s, e -> done.resume(s to e) } }
			withContext(Dispatchers.Default) {
				for ((slot, text) in slots) {
					val target = when (slot) { "current" -> StudioStore.Slot.CURRENT; "recovery" -> StudioStore.Slot.RECOVERY; "previous" -> StudioStore.Slot.PREVIOUS; else -> null }
					// The native studio's own work wins: a slot it already saved is not overwritten.
					if (target != null && !StudioStore.exists(target)) StudioStore.saveRaw(text, target)
				}
				if (error == null) StudioStore.copiedFromWeb = true
			}
			if (error != null) status = "Couldn't copy the web studio's projects yet ($error). They are still there in Web studio."
			else if (slots.isNotEmpty()) status = "Copied your web studio project${if (slots.size > 1) "s" else ""} across."
		}
		loading = "Opening your project…"
		project = withContext(Dispatchers.Default) { StudioStore.load(StudioStore.Slot.CURRENT) }
		refreshSlots()
		loading = null
	}

	// Autosave (the web's 400 ms debounce) and the composite, whenever the project changes.
	LaunchedEffect(Unit) {
		snapshotFlow { project }.collectLatest { p ->
			if (p == null) return@collectLatest
			composite = withContext(worker) { StudioRender.composite(p) }
			delay(400)
			saved = "Saving…"
			withContext(Dispatchers.Default) { StudioStore.save(p, StudioStore.Slot.CURRENT) }
			saved = "Saved on this iPad"
		}
	}

	// The selected part's mask, ready to paint.
	LaunchedEffect(Unit) {
		snapshotFlow { selectedId }.collectLatest { id ->
			flushMask()
			val p = projectNow ?: return@collectLatest
			val part = p.part(id)
			mask = withContext(worker) { (if (part == null) null else MaskEdit(part.id, p.width, p.height, part.mask)).also { workerMask[0] = it } }
			polygon.clear(); bump()
			canvasPreview = null; picking = null
			if (part != null && status.startsWith("Choose a part")) status = ""
		}
	}

	fun layout(w: Float, h: Float, v: CanvasView): FloatArray {
		val p = projectNow ?: return floatArrayOf(1f, 0f, 0f)
		return v.layout(w, h, p.width.toFloat(), p.height.toFloat())
	}

	// What's on screen, at screen resolution. Moving and zooming draw the last frame moved and scaled right away (no
	// re-render per frame); a sharp render of the visible region follows once the view rests. Paint redraws at once.
	LaunchedEffect(Unit) {
		var lastView: CanvasView? = null
		snapshotFlow { listOf(composite, maskTick, view, box, showMask, mask, canvasPreview) }.collectLatest {
			val p = projectNow ?: return@collectLatest
			// Right after a project of another size opens (Open, Upscale 2×), the composite is still the old one's until it's
			// redone: wait for it rather than read past its end.
			val art = canvasPreview?.first?.takeIf { it.size == p.width * p.height } ?: composite?.takeIf { it.size == p.width * p.height } ?: return@collectLatest
			val edit0 = mask
			if (edit0 != null && (edit0.width != p.width || edit0.height != p.height)) return@collectLatest
			val (w, h) = box
			if (w < 2f || h < 2f) return@collectLatest
			val viewOnly = lastView != null && lastView != view
			lastView = view
			if (viewOnly) delay(120)
			val l = layout(w, h, view)
			val edit = mask; val withMask = showMask && edit != null && canvasPreview == null
			frame = withContext(Dispatchers.Default) {
				val scale = l[0]; val left = l[1]; val top = l[2]
				val x0 = maxOf(0f, -left / scale); val y0 = maxOf(0f, -top / scale)
				val x1 = minOf(p.width.toFloat(), (w - left) / scale); val y1 = minOf(p.height.toFloat(), (h - top) / scale)
				if (x1 - x0 < 1f || y1 - y0 < 1f) return@withContext null
				var outW = ((x1 - x0) * scale).roundToInt().coerceAtLeast(1); var outH = ((y1 - y0) * scale).roundToInt().coerceAtLeast(1)
				val shrink = minOf(1f, kotlin.math.sqrt(2_400_000f / (outW.toFloat() * outH)))
				outW = (outW * shrink).roundToInt().coerceAtLeast(1); outH = (outH * shrink).roundToInt().coerceAtLeast(1)
				StudioFrame(x0, y0, x1, y1,
					argbRegionBitmap(art, p.width, p.height, x0, y0, x1 - x0, y1 - y0, outW, outH),
					if (withMask) argbRegionBitmap(edit!!.pixels, p.width, p.height, x0, y0, x1 - x0, y1 - y0, outW, outH) else null)
			}
		}
	}

	Column(modifier.background(Color(0xFF14141C))) {
		val p = project
		// ---- Top bar: the project and its files. ----
		Row(Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			if (p != null) {
				var name by remember(p.id) { mutableStateOf(p.name) }
				BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = Text1, fontSize = 17.sp, fontWeight = FontWeight.Bold),
					cursorBrush = SolidColor(Accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
					keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank() && name != p.name) change(p.with(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name.trim())), "Renamed the project")) }),
					modifier = Modifier.width(220.dp).clip(RoundedCornerShape(6.dp)).background(Field).padding(horizontal = 10.dp, vertical = 8.dp))
				Text(saved, color = Muted, fontSize = 12.sp)
			}
			Chip("Open project") {
				Exchange.pick(setOf("json"), multiple = false) { names ->
					val file = names.firstOrNull() ?: return@pick
					scope.launch(safe) {
						busy = "Opening $file"
						val opened = withContext(Dispatchers.Default) { StudioProject.parse(RigFiles.read(file).decodeToString()) }
						replaceWith(opened, "Opened $file."); busy = null
					}
				}
			}
			if (p != null) Chip("Save project") {
				scope.launch(safe) {
					flushMask(); val now = projectNow ?: return@launch
					busy = "Writing the project file"
					val path = withContext(Dispatchers.Default) { StudioStore.export(now.fileName, now.fileText().encodeToByteArray()) }
					busy = null; Exchange.share(listOf(path))
				}
			}
			Chip("Import artwork") {
				Exchange.pick(setOf("png", "jpg", "jpeg", "webp"), multiple = false) { names ->
					val file = names.firstOrNull() ?: return@pick
					scope.launch(safe) {
						busy = "Importing $file"
						val made = withContext(Dispatchers.Default) {
							val bytes = RigFiles.read(file)
							require(bytes.size <= 50 * 1024 * 1024) { "Use an image smaller than 50 MB." }
							val pixels = decodeImage(bytes) ?: throw IllegalArgumentException("Choose a PNG, JPEG, or WebP image.")
							require(pixels.width.toLong() * pixels.height <= 16_000_000L) { "Use artwork under 16 megapixels for this first version." }
							StudioProject.create(file.substringBeforeLast('.'), encodePng(pixels.width, pixels.height, pixels.argb), pixels.width, pixels.height)
						}
						replaceWith(made, "Imported $file. Choose a part and paint over it."); busy = null
					}
				}
			}
			Chip("Import PSD") {
				Exchange.pick(setOf("psd"), multiple = false) { names ->
					val file = names.firstOrNull() ?: return@pick
					scope.launch(safe) {
						busy = "Importing the layers of $file"
						val made = withContext(Dispatchers.Default) { StudioPsd.import(RigFiles.read(file), file) }
						replaceWith(made, "Imported ${made.parts.size} layers from $file."); busy = null
					}
				}
			}
			if (p != null) Chip("Send to Rig", true) {
				scope.launch(safe) {
					flushMask(); val now = projectNow ?: return@launch
					busy = "Writing the PSD for the Rig tab"
					// Its own name, so it never replaces an original PSD of the same character (boo.psd...).
					val name = (now.name.replace(Regex("[^a-zA-Z0-9_ -]"), "_").trim().ifEmpty { "Layerloom" }) + " - Studio.psd"
					// The rigger's PSD (parts named by their rig role, with stable ids) and its rig job beside it (glue, swing,
					// turn depths, toggles, arms): the Mac's Studio-to-PSD2Live hand-off, on the iPad.
					val handoff = withContext(Dispatchers.Default) {
						StudioRigHandoff.build(now).also {
							io.github.psd2live.ipad.RigFiles.putPsd(name, it.psd)
							io.github.psd2live.ipad.RigFiles.putPsd(name.removeSuffix(".psd") + ".layerloom.json", it.job.toString().encodeToByteArray())
						}
					}
					busy = null
					if (handoff.layers.isEmpty()) { status = "Nothing to rig yet: extract some parts first."; return@launch }
					if (handoff.skipped.isNotEmpty()) status = "Left out of the rig: ${handoff.skipped.joinToString()}"
					onSendToRig(name)
				}
			}
			if (p != null) Chip("Export PSD") {
				scope.launch(safe) {
					flushMask(); val now = projectNow ?: return@launch
					busy = "Writing the PSD"
					val path = withContext(Dispatchers.Default) {
						StudioStore.export((now.name.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "Layerloom" }) + ".psd", StudioPsd.export(now))
					}
					busy = null; Exchange.share(listOf(path))
				}
			}
			if (p != null) Chip("Export PNG layers") {
				scope.launch(safe) {
					flushMask(); val now = projectNow ?: return@launch
					busy = "Writing the PNG layers"
					val path = withContext(Dispatchers.Default) { val (name, bytes) = StudioExports.layersZip(now); StudioStore.export(name, bytes) }
					busy = null; Exchange.share(listOf(path))
				}
			}
			if (p != null) {
				Chip("Save recovery checkpoint") {
					scope.launch(safe) {
						flushMask(); val now = projectNow ?: return@launch
						withContext(Dispatchers.Default) { StudioStore.save(StudioEdits.stamped(now, "checkpointAt"), StudioStore.Slot.RECOVERY) }
						refreshSlots(); status = "Recovery checkpoint saved."
					}
				}
				Chip("Restore checkpoint", enabled = hasCheckpoint) {
					scope.launch(safe) {
						flushMask(); val now = projectNow ?: return@launch
						val back = withContext(Dispatchers.Default) {
							val cp = StudioStore.load(StudioStore.Slot.RECOVERY) ?: return@withContext null
							StudioStore.save(StudioEdits.stamped(now, "checkpointAt"), StudioStore.Slot.RECOVERY)
							StudioProject(kotlinx.serialization.json.JsonObject(cp.json - "checkpointAt"))
						}
						if (back != null) { selectedId = null; mask = null; project = back; status = "Checkpoint restored. The project you had is now the checkpoint." }
					}
				}
				Chip("Back to previous project", enabled = hasPrevious) {
					scope.launch(safe) {
						flushMask(); val now = projectNow ?: return@launch
						val back = withContext(Dispatchers.Default) {
							val prev = StudioStore.load(StudioStore.Slot.PREVIOUS) ?: return@withContext null
							StudioStore.save(StudioEdits.stamped(now, "replacedAt"), StudioStore.Slot.PREVIOUS)
							StudioProject(kotlinx.serialization.json.JsonObject(prev.json - "replacedAt"))
						}
						if (back != null) { selectedId = null; mask = null; view = CanvasView(); project = back; status = "Back to ${back.name}. The other project is kept as previous." }
					}
				}
			}
			Chip("Web studio") { scope.launch { flushMask(); onOpenWebStudio() } }
		}
		busy?.let {
			Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
				Text(it, color = Muted, fontSize = 12.sp)
				LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
			}
		}
		if (p == null) {
			Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
				Spacer(Modifier.height(120.dp))
				Text(loading ?: "No project yet", color = Text1, fontSize = 20.sp)
				if (loading == null) Text("Import artwork or open a .layerloom.json project from the bar above.", color = Muted, fontSize = 14.sp)
				if (status.isNotEmpty()) Text(status, color = Muted, fontSize = 13.sp)
			}
			return@Column
		}
		val fitId = fitting
		if (fitId != null && p.part(fitId) != null) {
			FitScreen(p, fitId, pencilOnly, onPencilOnly = { pencilOnly = it; if (it) pencilSeen = true },
				onApply = { id, changes, event, stay ->
					projectNow?.let { change(it.updatePart(id, changes, event)) }
					if (!stay) fitting = null
					status = "$event. Saved into the layer."
				},
				onSelect = { id -> fitting = id; selectedId = id },
				onClose = { fitting = null })
			return@Column
		}
		val selected = p.part(selectedId)
		// Read on every stroke, so undo, redo and Extract follow the mask.
		@Suppress("UNUSED_VARIABLE") val strokes = maskTick
		// ---- Toolbar: the mask tools. ----
		FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp)) {
			for (t in Tool.entries) Chip(t.label, tool == t) { tool = t; if (t != Tool.POLYGON) polygon.clear() }
			Chip(if (erasing) "Erasing mask" else "Painting mask", erasing) { erasing = !erasing }
			Chip("Undo", enabled = mask?.canUndo == true) { scope.launch(worker + safe) { maskNow?.let { it.undo(); bump(); commitMaskSoon(it, 300) } } }
			Chip("Redo", enabled = mask?.canRedo == true) { scope.launch(worker + safe) { maskNow?.let { it.redo(); bump(); commitMaskSoon(it, 300) } } }
			Chip("Clear mask", enabled = mask != null) { scope.launch(worker + safe) { maskNow?.let { it.clear(); bump(); commitMaskSoon(it, 0) } } }
			Chip("Fit view") { view = CanvasView() }
			Chip(if (showMask) "Mask shown" else "Mask hidden", showMask) { showMask = !showMask }
			Chip(if (pencilOnly) "Pencil only · fingers pan" else "Fingers draw", pencilOnly) { pencilOnly = !pencilOnly }
			Chip(if (pressure) "Pressure size on" else "Pressure size off", pressure) { pressure = !pressure }
			Chip(if (panels) "Hide panels" else "Show panels") { panels = !panels }
		}
		Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Text("Brush ${brush.roundToInt()} px", color = Muted, fontSize = 12.sp, modifier = Modifier.width(92.dp))
			Slider(brush, { brush = it }, valueRange = 2f..400f, colors = sliderColors(), modifier = Modifier.weight(1f).height(30.dp))
			if (tool == Tool.EDGE) {
				Text("Tolerance ${tolerance.roundToInt()}", color = Muted, fontSize = 12.sp, modifier = Modifier.width(92.dp))
				Slider(tolerance, { tolerance = it }, valueRange = 5f..100f, colors = sliderColors(), modifier = Modifier.weight(1f).height(30.dp))
			} else {
				Text("Softness ${softness.roundToInt()}%", color = Muted, fontSize = 12.sp, modifier = Modifier.width(92.dp))
				Slider(softness, { softness = it }, valueRange = 0f..100f, colors = sliderColors(), modifier = Modifier.weight(1f).height(30.dp))
			}
		}
		if (tool == Tool.SMART) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Chip("Keep point", smartMode == 1) { smartMode = 1 }; Chip("Exclude point", smartMode == 0) { smartMode = 0 }; Chip("Region corners", smartMode == 2) { smartMode = 2 }
			Chip("Undo point", enabled = smartPoints.isNotEmpty()) { smartPoints.removeAt(smartPoints.lastIndex); smartCands = emptyList(); canvasPreview = null }
			Chip("Clear", enabled = smartPoints.isNotEmpty() || smartCorners.isNotEmpty()) { smartPoints.clear(); smartCorners.clear(); smartCands = emptyList(); canvasPreview = null }
			Chip("Suggest masks", true, enabled = busy == null && smartPoints.any { it.third == 1 } && smartCorners.size != 1) {
				val pts = smartPoints.toList()
				val region = if (smartCorners.size == 2) { val a = smartCorners[0]; val b = smartCorners[1]
					val x = minOf(a.x, b.x).toInt(); val y = minOf(a.y, b.y).toInt(); intArrayOf(x, y, kotlin.math.abs(a.x - b.x).toInt() + 1, kotlin.math.abs(a.y - b.y).toInt() + 1) } else null
				scope.launch(safe) {
					val now = projectNow ?: return@launch
					busy = "Running the smart mask on this iPad (the first time reads the picture: a few seconds)"
					try {
						val c = withContext(Dispatchers.Default) { StudioSmartMask.suggest(now, pts, region) }
						smartCands = c; smartSel = 0
						canvasPreview = withContext(Dispatchers.Default) { smartPreview(now, c[0].mask) } to "Candidate 1 of 3 (not applied yet)"
						status = "Compare the candidates, then Replace mask or Add to mask. Scores are the model's confidence, not edge quality."
					} catch (e: IllegalStateException) { status = e.message ?: "The smart mask didn't work." } finally { busy = null }
				}
			}
			smartCands.forEachIndexed { i, cand -> Chip("${i + 1} · ${(cand.score * 100).roundToInt()}%", smartSel == i) {
				smartSel = i; val now = projectNow ?: return@Chip
				scope.launch(safe) { canvasPreview = withContext(Dispatchers.Default) { smartPreview(now, cand.mask) } to "Candidate ${i + 1} of 3 (not applied yet)" }
			} }
			if (smartCands.isNotEmpty()) {
				fun apply(add: Boolean) {
					val id = selectedId ?: run { status = "Choose the part this mask is for, on the left."; return }
					val cand = smartCands[smartSel]
					scope.launch(safe) {
						flushMask(); val now = projectNow ?: return@launch
						val url = withContext(encoder) { pngDataUrl(encodePng(now.width, now.height, if (add) StudioSmartMask.union(now, now.part(id)?.mask, cand.mask) else cand.mask)) }
						projectNow?.let { change(StudioEdits.setMask(it, id, url)) }
						mask = withContext(worker) { MaskEdit(id, now.width, now.height, url).also { workerMask[0] = it } }; bump()
						canvasPreview = null; smartCands = emptyList(); smartPoints.clear(); smartCorners.clear(); tool = Tool.BRUSH
						status = "Smart mask applied to ${now.part(id)?.name}. Refine it with the brush before extracting."
					}
				}
				Chip("Replace mask", true, enabled = selectedId != null) { apply(false) }
				Chip("Add to mask", enabled = selectedId != null) { apply(true) }
			}
		}
		if (tool == Tool.POLYGON) Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			Text("Tap the corners of the area (${polygon.size} so far).", color = Muted, fontSize = 13.sp)
			Chip(if (erasing) "Clear inside" else "Fill inside", true, enabled = polygon.size >= 3 && mask != null) {
				val points = polygon.map { it.x to it.y }; val erase = erasing; polygon.clear()
				scope.launch(worker + safe) { maskNow?.let { it.polygon(points, erase); bump(); commitMaskSoon(it, 0) } }
			}
			Chip("Remove last", enabled = polygon.isNotEmpty()) { polygon.removeAt(polygon.lastIndex) }
			Chip("Cancel", enabled = polygon.isNotEmpty()) { polygon.clear() }
		}
		Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			// ---- Parts, front first (the project keeps them back to front). ----
			if (panels) Column(Modifier.width(230.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Panel).padding(10.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					Chip("Add part") { val (next, id) = StudioEdits.addPart(p); change(next); selectedId = id }
				}
				Text("${p.parts.size} parts · front first", color = Muted, fontSize = 12.sp)
				LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					items(p.parts.asReversed(), key = { it.id }) { part ->
						val active = part.id == selectedId
						Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (active) Color(0xFF3A3358) else Field)
							.clickable { selectedId = part.id }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
							Column(Modifier.weight(1f)) {
								Text(part.name, color = if (part.visible) Text1 else Muted, fontSize = 14.sp)
								Text("${part.group} · ${when { part.cutout != null -> "Extracted"; part.mask != null -> "Masked"; else -> "Needs mask" }}", color = Muted, fontSize = 11.sp)
							}
							Text(if (part.visible) "●" else "○", color = if (part.visible) Accent else Muted, fontSize = 16.sp,
								modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { change(StudioEdits.visible(p, part.id, !part.visible)) }.padding(6.dp))
						}
					}
				}
				Text("Add slots", color = Muted, fontSize = 12.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					for (kind in StudioEdits.componentTemplates.keys) Chip(kind) { change(StudioEdits.addComponents(p, kind)) }
				}
			}
			// ---- The canvas. ----
			BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Color(0xFF101017))) {
				val density = LocalDensity.current
				val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
				if (box != (w to h)) box = w to h
				Canvas(Modifier.fillMaxSize().canvasTouch(listOf(p.id, w, h), fingersPan = { pencilOnly || settings.tool == Tool.PAN },
					onStylus = { if (!pencilSeen) { pencilSeen = true; pencilOnly = true; status = "Apple Pencil found: it paints, and fingers now pan and pinch (tap \"Pencil only\" to draw with a finger)." } }) {
					object : CanvasTouch {
						private var last: Offset? = null
						private var lastEdge: Offset? = null
						private fun toCanvas(o: Offset): Offset { val l = layout(w, h, view); return Offset((o.x - l[1]) / l[0], (o.y - l[2]) / l[0]) }
						private fun diameter(pr: Float, s: BrushSettings) = if (s.pressure) maxOf(1f, s.brush * (0.15f + 0.85f * kotlin.math.sqrt(pr))) else s.brush
						/** A part is chosen (its mask may still be loading: strokes wait for it on the worker). */
						private fun ready(): String? = selectedId.also { if (it == null) status = "Choose a part on the left first, then paint over it." }
						/** Runs [block] on the worker with the mask of part [id], once it's there. */
						private fun onMask(id: String?, block: (MaskEdit) -> Unit) {
							scope.launch(worker + safe) { val edit = workerMask[0]?.takeIf { it.partId == id } ?: return@launch; block(edit); bump() }
						}
						private var strokePart: String? = null
						override fun start(at: Offset, pressure: Float) {
							val s = settings; val c = toCanvas(at); val proj = projectNow ?: return
							picking?.let { which ->
								val pt = Pt(c.x.toDouble().coerceIn(0.0, proj.width - 1.0), c.y.toDouble().coerceIn(0.0, proj.height - 1.0))
								if (which == "source") cloneSource = pt else cloneDest = pt
								picking = null; return
							}
							when (s.tool) {
								Tool.PAN -> {}
								Tool.PICK -> scope.launch(worker + safe) {
									val id = StudioEdits.partAt(proj, c.x.toInt(), c.y.toInt())
									scope.launch(Dispatchers.Main) { if (id != null) selectedId = id else status = "No piece there." }
								}
								Tool.POLYGON -> if (ready() != null) polygon.add(c)
								Tool.SMART -> {
									val pt = Pt(c.x.toDouble().coerceIn(0.0, proj.width - 1.0), c.y.toDouble().coerceIn(0.0, proj.height - 1.0))
									smartCands = emptyList(); canvasPreview = null
									if (smartMode == 2) { if (smartCorners.size == 2) smartCorners.clear(); smartCorners.add(pt) }
									else if (smartPoints.size < 16) smartPoints.add(Triple(pt.x, pt.y, smartMode))
								}
								Tool.EDGE -> {
									val id = ready() ?: return
									strokePart = id
									val r = diameter(pressure, s) / 2; last = c; lastEdge = c; cursor = at to r
									onMask(id) { it.edge(StudioImages.layer(proj.source, proj.width, proj.height), c.x, c.y, r, s.tolerance.roundToInt(), s.erasing) }
								}
								Tool.BRUSH -> {
									val id = ready() ?: return
									strokePart = id
									pendingCommit?.cancel()
									val r = diameter(pressure, s) / 2; last = c; cursor = at to r
									onMask(id) { it.segment(c.x, c.y, c.x, c.y, r, 1f - s.softness / 100f, s.erasing) }
								}
							}
						}
						override fun move(at: Offset, pressure: Float) {
							val from = last ?: return; val s = settings; val c = toCanvas(at); val r = diameter(pressure, s) / 2
							val proj = projectNow ?: return; val id = strokePart
							cursor = at to r
							if (s.tool == Tool.EDGE) {
								// Spatially throttled, as the web's: a new fill every few pixels of travel.
								val le = lastEdge
								if (le != null && kotlin.math.hypot(c.x - le.x, c.y - le.y) < maxOf(2f, r / 3)) return
								lastEdge = c; last = c
								onMask(id) { it.edge(StudioImages.layer(proj.source, proj.width, proj.height), c.x, c.y, r, s.tolerance.roundToInt(), s.erasing) }
								return
							}
							last = c
							onMask(id) { it.segment(from.x, from.y, c.x, c.y, r, 1f - s.softness / 100f, s.erasing) }
						}
						override fun end() {
							cursor = null
							if (last == null) return
							last = null; lastEdge = null
							val s = settings; val id = strokePart
							onMask(id) { it.endStroke(if (s.tool == Tool.EDGE) "Edge brush" else "Mask"); commitMaskSoon(it) }
						}
						override fun cancel() { cursor = null; last = null; lastEdge = null; val id = strokePart; onMask(id) { it.cancelStroke() } }
						override fun undo() { scope.launch(worker + safe) { maskNow?.let { it.undo(); bump(); commitMaskSoon(it, 300) } }; status = "Undone (two-finger tap). The artwork is unchanged." }
						override fun redo() { scope.launch(worker + safe) { maskNow?.let { it.redo(); bump(); commitMaskSoon(it, 300) } } }
						override fun view() = view
						override fun setView(view: CanvasView) { setStudioView(view) }
						override fun layout(view: CanvasView) = layout(w, h, view)
						private fun setStudioView(v: CanvasView) { view = v }
					}
				}) {
					val l = layout(w, h, view)
					val cw = p.width * l[0]; val ch = p.height * l[0]
					clipRect(maxOf(0f, l[1]), maxOf(0f, l[2]), minOf(size.width, l[1] + cw), minOf(size.height, l[2] + ch)) {
						val cell = 24f
						var y = maxOf(0f, l[2]); var row = 0
						while (y < minOf(size.height, l[2] + ch)) {
							var x = maxOf(0f, l[1]); var col = 0
							while (x < minOf(size.width, l[1] + cw)) {
								drawRect(if ((row + col) % 2 == 0) Color(0xFFEDE9E3) else Color(0xFFF8F6F2), Offset(x, y), Size(cell, cell))
								x += cell; col++
							}
							y += cell; row++
						}
					}
					frame?.let { f ->
						// The frame where its canvas region sits now, however the view moved since it was drawn.
						val dst = IntOffset((l[1] + f.x0 * l[0]).roundToInt(), (l[2] + f.y0 * l[0]).roundToInt())
						val dsz = IntSize(((f.x1 - f.x0) * l[0]).roundToInt().coerceAtLeast(1), ((f.y1 - f.y0) * l[0]).roundToInt().coerceAtLeast(1))
						drawImage(f.art, dstOffset = dst, dstSize = dsz)
						f.mask?.let { drawImage(it, dstOffset = dst, dstSize = dsz, alpha = 0.55f) }
					}
					// Smart mask points (green keep, coral exclude) and its region.
					if (tool == Tool.SMART) {
						for ((x, y, k) in smartPoints) { val c = Offset(l[1] + x.toFloat() * l[0], l[2] + y.toFloat() * l[0]); drawCircle(Color(0xFF171720), 11f, c); drawCircle(if (k == 1) Color(0xFF55DD99) else Color(0xFFFF8D84), 8f, c) }
						if (smartCorners.size == 2) { val a = smartCorners[0]; val b = smartCorners[1]
							val o = Offset(l[1] + minOf(a.x, b.x).toFloat() * l[0], l[2] + minOf(a.y, b.y).toFloat() * l[0])
							drawRect(Color(0xFFC4AAFF), o, Size(kotlin.math.abs(a.x - b.x).toFloat() * l[0], kotlin.math.abs(a.y - b.y).toFloat() * l[0]), style = Stroke(3f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 8f)))) }
						else if (smartCorners.size == 1) { val a = smartCorners[0]; drawCircle(Color(0xFFC4AAFF), 8f, Offset(l[1] + a.x.toFloat() * l[0], l[2] + a.y.toFloat() * l[0])) }
					}
					// The clone points: source green, where it lands red.
					for ((pt, colour) in listOf(cloneSource to Color(0xFF3DD68C), cloneDest to Color(0xFFFF5C7A))) pt?.let {
						val c = Offset(l[1] + it.x.toFloat() * l[0], l[2] + it.y.toFloat() * l[0])
						drawCircle(Color.Black.copy(alpha = 0.6f), 13f, c, style = Stroke(5f)); drawCircle(colour, 13f, c, style = Stroke(3f))
						drawLine(colour, c - Offset(20f, 0f), c + Offset(20f, 0f), 2f); drawLine(colour, c - Offset(0f, 20f), c + Offset(0f, 20f), 2f)
					}
					if (polygon.isNotEmpty()) {
						val pts = polygon.map { Offset(l[1] + it.x * l[0], l[2] + it.y * l[0]) }
						for (i in 1 until pts.size) drawLine(Color(0xFFC4AAFF), pts[i - 1], pts[i], strokeWidth = 3f)
						for (pt in pts) drawCircle(Color(0xFFC4AAFF), 6f, pt)
					}
					cursor?.let { (c, r) ->
						drawCircle(Color.White.copy(alpha = 0.85f), r * l[0], c, style = Stroke(1.5f))
						drawCircle(Color.Black.copy(alpha = 0.5f), r * l[0] + 1.5f, c, style = Stroke(1f))
					}
				}
				if (frame == null) Text("Drawing…", color = Muted, modifier = Modifier.align(Alignment.Center))
				canvasPreview?.let { (_, label) ->
					Row(Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xEE3A3358)).padding(horizontal = 12.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
						Text(label, color = Text1, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
						Text("Back to the art", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { canvasPreview = null }.padding(4.dp))
					}
				}
				if (picking != null) Text(if (picking == "source") "Tap where to copy from" else "Tap where it lands", color = Color.White, fontSize = 14.sp,
					modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 14.dp, vertical = 8.dp))
				// What just happened, always in sight (at the foot of the long inspector it scrolled out of view).
				if (status.isNotEmpty()) Row(Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(8.dp))
					.background(if (status.startsWith("Something") || status.startsWith("Couldn't")) Color(0xEE4A2030) else Color(0xEE2B283C))
					.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					Text(status, color = Text1, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
					Text("✕", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable { status = "" }.padding(4.dp))
				}
				if (selected != null && canvasPreview == null) Text("${selected.name}${if (erasing) " · erasing" else ""}", color = Color(0xFF3B3550), fontSize = 13.sp,
					modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 4.dp))
			}
			// ---- Inspector: the selected part. ----
			if (panels) Column(Modifier.width(280.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Panel).verticalScroll(rememberScrollState()).padding(12.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF2B283C))) {
					for (t in InspectorTab.entries) Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (tab == t) Color(0xFF4A3F78) else Color.Transparent)
						.clickable { if (tab != t) { tab = t; picking = null } }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
						Text(t.label, color = if (tab == t) Text1 else Accent, fontSize = 14.sp, maxLines = 1)
					}
				}
				val host = PanelHost(p, selected, busy != null,
					run = { label, block -> scope.launch(safe) {
						flushMask(); busy = label
						try { block() } catch (e: IllegalStateException) { status = e.message ?: "Couldn't do that." } finally { busy = null }
					} },
					change = { change(it) }, latest = { projectNow },
					showOnCanvas = { img, label -> canvasPreview = if (img == null) null else img to (label ?: "") },
					focus = { b ->
						val (bw, bh) = box; val proj = projectNow
						if (proj != null && bw > 2f && bh > 2f) {
							val fit = minOf(bw / proj.width, bh / proj.height)
							val rw = maxOf(1, b[2] - b[0]); val rh = maxOf(1, b[3] - b[1])
							val zoom = minOf(0.6f * bw / (rw * fit), 0.6f * bh / (rh * fit)).coerceIn(1f, 12f)
							val scale = fit * zoom
							val cx = (b[0] + b[2]) / 2f; val cy = (b[1] + b[3]) / 2f
							view = CanvasView(zoom, bw / 2f - cx * scale - (bw - proj.width * scale) / 2f, bh / 2f - cy * scale - (bh - proj.height * scale) / 2f)
						}
					},
					reloadMask = { id, url -> scope.launch(safe) {
						val proj = projectNow ?: return@launch
						mask = withContext(worker) { MaskEdit(id, proj.width, proj.height, url).also { workerMask[0] = it } }; bump()
					} },
					select = { selectedId = it }, say = { status = it },
					cloneSource = cloneSource, cloneDest = cloneDest, pickPoint = { picking = it }, picking = picking,
					pending = aiPending, setPending = { aiPending = it },
					replace = { next, message -> replaceWith(next, message) })
				if (tab == InspectorTab.REPAIR) RepairPanel(host)
				else if (tab == InspectorTab.FACE) FacePanel(host)
				else if (tab == InspectorTab.CHECK) CheckPanel(host)
				else {
				if (selected == null) {
					Text("Choose a part on the left, then paint over it on the canvas.", color = Muted, fontSize = 13.sp)
				} else {
					Text("Selected part", color = Muted, fontSize = 12.sp)
					var name by remember(selected.id) { mutableStateOf(selected.name) }
					BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = Text1, fontSize = 16.sp), cursorBrush = SolidColor(Accent),
						keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
						keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank() && name != selected.name) change(StudioEdits.rename(p, selected.id, name.trim())) }),
						modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Field).padding(10.dp))
					Text("Group", color = Muted, fontSize = 12.sp)
					FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						for (g in StudioPart.groups) Chip(g, selected.group == g) { if (selected.group != g) change(StudioEdits.group(p, selected.id, g)) }
					}
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip(if (selected.visible) "Hide" else "Show") { change(StudioEdits.visible(p, selected.id, !selected.visible)) }
						Chip("Forward") { change(StudioEdits.move(p, selected.id, 1)) }
						Chip("Backward") { change(StudioEdits.move(p, selected.id, -1)) }
					}
					Spacer(Modifier.height(4.dp))
					Text("Separate artwork", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					var feather by remember(selected.id) { mutableStateOf(selected.feather.toFloat()) }
					Text("Soft edge ${feather.roundToInt()} px", color = Muted, fontSize = 12.sp)
					Slider(feather, { feather = it }, valueRange = 0f..24f, steps = 23, colors = sliderColors(), modifier = Modifier.height(30.dp),
						onValueChangeFinished = { if (feather.roundToInt() != selected.feather) change(StudioEdits.feather(p, selected.id, feather.roundToInt())) })
					Chip(if (selected.cutout != null) "Extract again" else "Extract", true, enabled = busy == null && (selected.mask != null || mask?.canUndo == true)) {
						scope.launch(safe) {
							flushMask()
							val now = projectNow ?: return@launch
							if (now.part(selected.id)?.mask == null) { status = "Paint a mask over the part first."; return@launch }
							busy = "Extracting ${selected.name}"
							val apply = withContext(encoder) { StudioEdits.extract(now, selected.id) }
							projectNow?.let { change(apply(it)) }; busy = null
							status = "Layer extracted. Hide it to see the artwork missing underneath."
						}
					}
					Text("The drawing's pixels under the mask become this part's own layer and leave the remaining artwork.", color = Muted, fontSize = 12.sp)
					Chip("Fit by hand", true, enabled = busy == null && selected.cutout != null) { scope.launch { flushMask(); fitting = selected.id } }
					if (selected.cutout == null) Text("Fit by hand works on a part's art: extract it (or import a PSD) first.", color = Muted, fontSize = 12.sp)
					Chip("Import mask PNG", enabled = busy == null) {
						Exchange.pick(setOf("png"), multiple = false) { names ->
							val file = names.firstOrNull() ?: return@pick
							val id = selected.id
							scope.launch(safe) {
								flushMask(); val now = projectNow ?: return@launch
								val url = withContext(Dispatchers.Default) {
									val px = decodeImage(RigFiles.read(file)) ?: throw IllegalArgumentException("That isn't a PNG.")
									require(px.width == now.width && px.height == now.height) { "The mask must be ${now.width} × ${now.height}. It will not be silently resized." }
									require(px.argb.any { (it ushr 24) != 0 }) { "This PNG is empty." }
									pngDataUrl(encodePng(px.width, px.height, px.argb))
								}
								projectNow?.let { change(StudioEdits.setMask(it, id, url)) }
								val latest = projectNow ?: return@launch
								mask = withContext(worker) { MaskEdit(id, latest.width, latest.height, url) }; bump()
								status = "Mask imported from $file."
							}
						}
					}
					Spacer(Modifier.height(4.dp))
					// ---- Peel: lift the marked piece into its own part ----
					Text("Peel", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					Text("Split lifts the marked piece off ${selected.name} into its own part, just above it. A soft mask edge shares the pixels between the two, so nothing along it is lost.",
						color = Muted, fontSize = 12.sp)
					BasicTextField(peelName, { peelName = it }, singleLine = true, textStyle = TextStyle(color = Text1, fontSize = 15.sp), cursorBrush = SolidColor(Accent),
						decorationBox = { inner -> Box { if (peelName.isEmpty()) Text("New part's name (e.g. Talisman)", color = Muted, fontSize = 15.sp); inner() } },
						modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Field).padding(10.dp))
					// Repaint underneath / Reveal use the AI image edit through the private studio; the result is reviewed first.
					LaunchedEffect(Unit) { PrivateStudio.signedIn { aiSignedIn = it } }
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip("Split", peelMode == "split") { peelMode = "split" }; Chip("Reveal", peelMode == "reveal") { peelMode = "reveal" }
					}
					if (peelMode == "split") {
						Chip(if (peelRepaint) "Repaint underneath (AI): on" else "Repaint underneath (AI): off", peelRepaint) { peelRepaint = !peelRepaint }
						if (peelRepaint) BasicTextField(peelBehind, { peelBehind = it }, singleLine = true, textStyle = TextStyle(color = Text1, fontSize = 14.sp), cursorBrush = SolidColor(Accent),
							decorationBox = { inner -> Box { if (peelBehind.isEmpty()) Text("What's underneath (e.g. the hat's teal band)", color = Muted, fontSize = 14.sp); inner() } },
							modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Field).padding(10.dp))
					} else {
						Text("Paints hidden art into ${selected.name}, behind what's there (hair behind the head, lining behind a hand). Mark where it goes; hide the parts in front first so the AI sees what's behind.", color = Muted, fontSize = 12.sp)
						BasicTextField(peelWhat, { peelWhat = it }, singleLine = true, textStyle = TextStyle(color = Text1, fontSize = 14.sp), cursorBrush = SolidColor(Accent),
							decorationBox = { inner -> Box { if (peelWhat.isEmpty()) Text("Hidden art to paint (e.g. the back of her bob)", color = Muted, fontSize = 14.sp); inner() } },
							modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Field).padding(10.dp))
					}
					val needsAi = peelMode == "reveal" || peelRepaint
					if (needsAi && !aiSignedIn) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip("Sign in to your private studio", true) { PrivateStudio.signIn { ok -> aiSignedIn = ok; status = if (ok) "Signed in for 12 hours." else "Not signed in." } }
					}
					val pending = peelPending
					if (pending != null && pending.first == selected.id) {
						Text("Review it on the canvas. Each try is one paid image edit.", color = Muted, fontSize = 12.sp)
						Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							Chip("Apply", true) {
								val (_, next, select) = pending
								peelPending = null; canvasPreview = null
								projectNow?.let { change(next(it)) }; select?.let { selectedId = it }
								status = "Applied. Undo last peel puts it back."
							}
							Chip("Discard") { peelPending = null; canvasPreview = null; status = "Discarded." }
						}
					} else Chip(if (peelMode == "reveal") "Reveal in ${selected.name}" else "Split from ${selected.name}", true,
						enabled = busy == null && (selected.mask != null || mask?.canUndo == true) && (peelMode == "reveal" || selected.cutout != null) && (!needsAi || aiSignedIn) && (peelMode == "split" || peelWhat.isNotBlank())) {
						val id = selected.id; val name = peelName.trim().ifEmpty { "${selected.name} piece" }
						val behind = peelBehind.trim().ifEmpty { "the ${selected.name}" }; val what = peelWhat.trim(); val repaint = peelRepaint; val mode = peelMode
						scope.launch(safe) {
							flushMask(); val now = projectNow ?: return@launch
							try {
								if (mode == "split" && !repaint) {
									busy = "Lifting the piece"
									val cut = withContext(encoder) { StudioPeel.cut(now, id) }
									val latest = projectNow ?: return@launch
									val (next, newId) = StudioPeel.applySplit(latest, id, cut, name)
									change(next); peelName = ""; selectedId = newId
									status = "Peeled \"$name\" off. Undo last peel puts it back."
								} else if (mode == "split") {
									busy = "Lifting the piece and repainting underneath (one image edit, 30-60 s)"
									val r = StudioAI.peelSplit(now, id, name, behind)
									val (preview, newId) = withContext(Dispatchers.Default) { StudioPeel.applySplit(now, id, r.cut, name) }
									val hidden = withContext(Dispatchers.Default) { StudioRender.composite(preview.withParts(preview.parts.map { if (it.id == newId) it.with(mapOf("visible" to kotlinx.serialization.json.JsonPrimitive(false))) else it })) }
									canvasPreview = hidden to "The piece lifted away: what's underneath now (not applied yet)"
									peelPending = Triple(id, { latest: StudioProject -> StudioPeel.applySplit(latest, id, r.cut, name).first }, null)
									status = if (r.redrawnShare > 0.15) "Note: the AI drew the $name back in over ${(r.redrawnShare * 100).roundToInt()}% of it; those pixels were dropped." else "Review it, then Apply or Discard."
								} else {
									busy = "Painting the hidden art (one image edit, 30-60 s)"
									val art = StudioAI.peelReveal(now, id, what)
									val shown = withContext(Dispatchers.Default) { StudioRender.composite(StudioAI.applyReveal(now, id, art, what)) }
									canvasPreview = shown to "With the hidden art (not applied yet)"
									peelPending = Triple(id, { latest: StudioProject -> StudioAI.applyReveal(latest, id, art, what) }, null)
									status = "Review it, then Apply or Discard."
								}
							} catch (e: IllegalStateException) { status = e.message ?: "That didn't work."; PrivateStudio.signedIn { aiSignedIn = it } }
							finally { busy = null }
						}
					}
					Chip("Undo last peel", enabled = busy == null && selected.json["peelUndo"] is kotlinx.serialization.json.JsonObject) {
						val id = selected.id
						try { change(StudioPeel.undo(p, id)); status = "Last peel undone." } catch (e: Throwable) { status = e.message ?: "Nothing to undo." }
					}
					Spacer(Modifier.height(4.dp))
					// ---- Split apart: one part per island ----
					Text("Split apart", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
					if (!splitOpen) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Chip("Split apart…", enabled = busy == null && selected.cutout != null) { splitOpen = true }
						val js = lastSplit
						if (js != null && js.first == p.id && selected.id in js.third) Chip("Join back") {
							change(StudioPeel.joinBack(p, js.second, js.third)); selectedId = js.second.id; lastSplit = null
							status = "Joined ${js.second.name} back together."
						}
					} else {
						LaunchedEffect(selected.id, selected.cutout, splitGap.roundToInt()) {
							delay(150)
							val now = projectNow ?: return@LaunchedEffect
							val found = withContext(Dispatchers.Default) {
								val art = StudioPeel.partArt(now, selected.id) ?: return@withContext null
								StudioPeel.findIslands(art, now.width, now.height, splitGap.roundToInt())
							}
							islands = found
							islandNames = found?.let { StudioPeel.splitNames(selected.name, it.pieces) } ?: emptyList()
							islandsPreview = found?.let { StudioPeel.preview(it) }?.let { px -> argbRegionBitmap(px.argb, px.width, px.height, 0f, 0f, px.width.toFloat(), px.height.toFloat(), px.width, px.height) }
						}
						Text("Join gaps under ${splitGap.roundToInt()} px", color = Muted, fontSize = 12.sp)
						Slider(splitGap, { splitGap = it }, valueRange = 0f..80f, colors = sliderColors(), modifier = Modifier.height(30.dp))
						islandsPreview?.let { bm ->
							Canvas(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF101017))) {
								val k = minOf(size.width / bm.width, size.height / bm.height)
								drawImage(bm, dstOffset = IntOffset(((size.width - bm.width * k) / 2).roundToInt(), ((size.height - bm.height * k) / 2).roundToInt()),
									dstSize = IntSize((bm.width * k).roundToInt().coerceAtLeast(1), (bm.height * k).roundToInt().coerceAtLeast(1)),
									filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
							}
						}
						val found = islands
						val count = found?.pieces?.size ?: 0
						if (found == null) Text("Finding the islands…", color = Muted, fontSize = 12.sp)
						else if (count < 2) Text("This is all one island. Lower Join gaps if the parts are close together, or trim one off by hand if they touch.", color = Muted, fontSize = 12.sp)
						else islandNames.forEachIndexed { i, n ->
							Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
								Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color(StudioPeel.tints[i % StudioPeel.tints.size])))
								BasicTextField(n, { v -> islandNames = islandNames.mapIndexed { k, old -> if (k == i) v else old } }, singleLine = true,
									textStyle = TextStyle(color = Text1, fontSize = 14.sp), cursorBrush = SolidColor(Accent),
									modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Field).padding(8.dp))
							}
						}
						Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							Chip(if (count >= 2) "Split into $count" else "Split", true, enabled = busy == null && count >= 2) {
								val f = found ?: return@Chip
								val before = selected; val names = islandNames
								scope.launch(safe) {
									busy = "Splitting ${before.name}"
									val urls = withContext(encoder) { StudioPeel.pieceImages(f).map { pngDataUrl(encodePng(f.w, f.h, it)) } }
									val latest = projectNow ?: return@launch
									val pieces = urls.mapIndexed { i, u -> u to (names.getOrNull(i)?.trim()?.ifEmpty { null } ?: "${before.name} ${i + 1}") }
									val (next, ids) = StudioPeel.applySplitApart(latest, before.id, pieces)
									change(next); busy = null; splitOpen = false; islands = null; islandsPreview = null
									lastSplit = Triple(latest.id, before, ids)
									status = "Split ${before.name} into ${pieces.joinToString { it.second }}. Each is its own layer now; Join back undoes it."
								}
							}
							Chip("Cancel") { splitOpen = false; islands = null; islandsPreview = null }
						}
					}
					Spacer(Modifier.height(4.dp))
					Chip("Delete part", enabled = p.parts.size > 1) { val id = selected.id; selectedId = null; change(StudioEdits.delete(p, id)) }
				}
				Spacer(Modifier.height(8.dp))
				val showRest = p.showRemaining ?: !p.isGeneratedParts
				Chip(if (showRest) "Remaining artwork shown" else "Remaining artwork hidden", showRest) { change(StudioEdits.showRemaining(p, !showRest)) }
				val extracted = p.parts.count { it.cutout != null }
				Text("$extracted of ${p.parts.size} parts extracted · ${p.width} × ${p.height}", color = Muted, fontSize = 12.sp)
				}
			}
		}
	}
}

/** The canvas region ([x0], [y0]) to ([x1], [y1]) as drawn: the art and the mask over it. */
private class StudioFrame(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val art: ImageBitmap, val mask: ImageBitmap?)

private class BrushSettings(val tool: Tool, val erasing: Boolean, val brush: Float, val softness: Float, val pressure: Boolean, val tolerance: Float)

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)

@Composable
private fun Chip(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
	Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF4A3F78) else Color(0xFF2B283C))
		.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
		Text(label, color = if (enabled) Accent else Muted, fontSize = 14.sp)
	}
}

/** The drawing with a candidate mask tinted over it, for comparing. */
private fun smartPreview(p: StudioProject, mask: IntArray): IntArray {
	val out = StudioRender.composite(p)
	for (i in out.indices) if ((mask[i] ushr 24) != 0) out[i] = StudioRender.blend(out[i], 0x8CBCA1FF.toInt())
	return out
}
