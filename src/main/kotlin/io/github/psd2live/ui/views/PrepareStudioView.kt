package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Layerloom patch (ONE-APP-PLAN S3): the Prepare workspace, Layerloom's studio inside the editor. The studio is a
 * local web app (its relay on 127.0.0.1:4180, which also runs its Python pipeline and paid relay); this panel starts
 * the relay when it isn't running and shows the studio in JetBrains Runtime's built-in Chromium. "Open in PSD2Live" in the
 * studio reaches this same app through its open-request inbox (ui/state/OpenRequests).
 */
@Composable
internal fun PrepareStudioView(modifier: Modifier = Modifier, viewModel: io.github.psd2live.ui.state.PSD2LiveViewModel? = null) {
	var status by remember { mutableStateOf(PrepareStudio.status) }
	var component by remember { mutableStateOf(PrepareStudio.component) }
	LaunchedEffect(Unit) {
		if (component == null) component = withContext(Dispatchers.IO) { PrepareStudio.open { status = it } }
	}
	val ui = component
	if (ui != null) Column(modifier) {
		// Layerloom patch (S3a): the open project carries its studio project: hand it back to the studio.
		val studio = viewModel?.studioProject()
		if (studio != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(io.github.psd2live.i18n.tr("prepare.projectArt"), modifier = Modifier.weight(1f))
			io.github.psd2live.ui.components.CompactButton(io.github.psd2live.i18n.tr("prepare.loadProjectArt"), {
				PrepareStudio.loadIntoStudio(studio)
			})
		}
		SwingPanel(factory = { ui }, modifier = Modifier.fillMaxWidth().weight(1f))
	}
	else Box(modifier, contentAlignment = Alignment.Center) {
		Column(Modifier.padding(24.dp)) { Text(status) }
	}
}

/** One studio relay and one browser for the whole app; the panel can be closed and shown again. */
internal object PrepareStudio {
	private const val URL = "http://127.0.0.1:4180/"
	@Volatile var component: java.awt.Component? = null; private set
	@Volatile var status: String = "Starting Layerloom…"; private set
	private var relay: Process? = null

	private fun layerloomDir(): File = File(System.getenv("LAYERLOOM_DIR")
		?: (System.getProperty("user.home") + "/Downloads/neuro-goose/layerloom"))

	private fun reachable(): Boolean = runCatching {
		(URI(URL).toURL().openConnection() as HttpURLConnection).run {
			connectTimeout = 800; readTimeout = 800; responseCode in 200..499
		}
	}.getOrDefault(false)

	private fun node(): String = listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")
		.firstOrNull { File(it).canExecute() } ?: "node"

	private val opening = kotlinx.coroutines.sync.Mutex()

	/**
	 * Layerloom patch (S3a): hands [project] (a studio project the editor's project carries) to the studio: it goes
	 * where the studio's relay serves it, then the studio restores it as Open project would (its current project is
	 * kept as the previous one).
	 */
	fun loadIntoStudio(project: java.nio.file.Path) {
		runCatching {
			val handoff = File(System.getProperty("user.home"), ".psd2live/prepare-handoff/project.json").toPath()
			java.nio.file.Files.createDirectories(handoff.parent)
			java.nio.file.Files.copy(project, handoff, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
			JbrBrowser.run("window.layerloomOpenFromRig && window.layerloomOpenFromRig()")
		}.onFailure { status = "Couldn't hand the art to the studio: ${it.message}" }
	}

	suspend fun open(report: (String) -> Unit): java.awt.Component? = opening.withLock { openOnce(report) }

	private suspend fun openOnce(report: (String) -> Unit): java.awt.Component? {
		component?.let { return it }
		fun say(text: String) { status = text; report(text) }
		if (!reachable()) {
			val dir = layerloomDir()
			if (!File(dir, "scripts/open-private-studio.mjs").isFile) { say("Layerloom isn't at $dir (set LAYERLOOM_DIR)."); return null }
			say("Starting Layerloom's studio…")
			relay = ProcessBuilder(node(), "scripts/open-private-studio.mjs").directory(dir)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start()
			Runtime.getRuntime().addShutdownHook(Thread { relay?.destroy() })
			repeat(60) { if (!reachable()) delay(500) }
			if (!reachable()) { say("Layerloom's studio didn't start (node scripts/open-private-studio.mjs in $dir)."); return null }
		}
		// The browser: JetBrains Runtime's built-in Chromium (the jcef module). KCEF's separately downloaded copy
		// crashed on Homebrew's OpenJDK (libjcef FindClass, 2026-10-14), so on any other runtime this says how to
		// start the app instead of loading Chromium.
		if (!ModuleLayer.boot().findModule("jcef").isPresent) {
			say("The Prepare workspace needs PSD2Live running on JetBrains Runtime with JCEF (~/.psd2live/jbr). " +
				"Start it with the PSD2Live icon, or ./gradlew run (it picks that runtime when it's installed).")
			return null
		}
		say("Opening Layerloom…")
		return withContext(Dispatchers.Main) { JbrBrowser.open(URL) }.also { component = it }
	}
}

/**
 * JetBrains Runtime's JCEF, started once, the way IntelliJ uses it: the runtime's own app arguments and settings
 * (JCefAppConfig: framework paths and so on), rendered offscreen into our own panel ([StudioPanel]). Two other routes
 * failed on macOS (2026-10-14): a windowed browser inside the Compose window crashed AppKit's text input, and JCEF's
 * built-in offscreen view needs JOGL, whose natives won't load from the runtime. Kept apart so other runtimes never
 * load org.cef classes.
 */
private object JbrBrowser {
	private var app: org.cef.CefApp? = null
	private var current: org.cef.browser.CefBrowser? = null

	/** Runs [script] in the studio page. */
	fun run(script: String) { current?.let { it.executeJavaScript(script, it.url ?: "", 0) } }

	fun open(url: String): java.awt.Component {
		val cef = app ?: run {
			// com.jetbrains.cef is only in the runtime (not in the compile-time API), hence reflection.
			val configClass = Class.forName("com.jetbrains.cef.JCefAppConfig")
			val config = configClass.getMethod("getInstance").invoke(null)
			@Suppress("UNCHECKED_CAST")
			val args = configClass.getMethod("getAppArgs").invoke(config) as Array<String>
			val settings = (configClass.getMethod("getCefSettings").invoke(config) as org.cef.CefSettings).apply {
				windowless_rendering_enabled = true
				cache_path = File(System.getProperty("user.home"), ".psd2live/jcef-cache").absolutePath
			}
			org.cef.CefApp.startup(args)
			org.cef.CefApp.getInstance(args, settings).also { app = it }
		}
		val panel = StudioPanel()
		val browser = cef.createClient().createBrowser(url, org.cef.browser.CefRendering.CefRenderingWithHandler(panel.handler, panel), false)
		current = browser
		panel.attach(browser)
		browser.createImmediately()
		return panel
	}
}

/**
 * The studio's pixels and input: Chromium paints frames into [handler] (BGRA, in device pixels), the panel draws the
 * latest one, and mouse, wheel and key events go back to the browser. The handler is a proxy so it fits the
 * runtime's CefRenderHandler whatever methods that version adds.
 */
private class StudioPanel : javax.swing.JPanel() {
	@Volatile private var frame: java.awt.image.BufferedImage? = null
	private var browser: org.cef.browser.CefBrowser? = null

	private fun scale(): Double = graphicsConfiguration?.defaultTransform?.scaleX ?: 2.0

	val handler: org.cef.handler.CefRenderHandler = java.lang.reflect.Proxy.newProxyInstance(
		org.cef.handler.CefRenderHandler::class.java.classLoader, arrayOf(org.cef.handler.CefRenderHandler::class.java),
	) { proxy, method, args ->
		when (method.name) {
			"getViewRect" -> java.awt.Rectangle(0, 0, maxOf(width, 1), maxOf(height, 1))
			"getDeviceScaleFactor" -> scale()
			"getScreenInfo" -> (args[1] as org.cef.handler.CefScreenInfo).run {
				device_scale_factor = scale(); depth = 32; depth_per_component = 8
				x = 0; y = 0; width = maxOf(this@StudioPanel.width, 1); height = maxOf(this@StudioPanel.height, 1)
				available_x = 0; available_y = 0; available_width = width; available_height = height
				true
			}
			"getScreenPoint" -> (args[1] as java.awt.Point).let { p ->
				val origin = runCatching { locationOnScreen }.getOrDefault(java.awt.Point())
				java.awt.Point(origin.x + p.x, origin.y + p.y)
			}
			"onPaint" -> { if (args[1] != true) paint(args[3] as java.nio.ByteBuffer, args[4] as Int, args[5] as Int); null }
			"onCursorChange" -> { runCatching { cursor = java.awt.Cursor.getPredefinedCursor(args[1] as Int) }; true }
			"startDragging" -> false
			"hashCode" -> System.identityHashCode(proxy)
			"equals" -> proxy === args?.get(0)
			"toString" -> "StudioPanel.handler"
			else -> when (method.returnType) {
				java.lang.Boolean.TYPE -> false
				java.lang.Double.TYPE -> 1.0
				java.lang.Integer.TYPE -> 0
				else -> null
			}
		}
	} as org.cef.handler.CefRenderHandler

	private fun paint(buffer: java.nio.ByteBuffer, w: Int, h: Int) {
		if (w <= 0 || h <= 0) return
		val image = frame?.takeIf { it.width == w && it.height == h }
			?: java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE)
		val pixels = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
		buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels, 0, minOf(pixels.size, buffer.capacity() / 4))
		frame = image
		repaint()
		// Checks: PSD2LIVE_PREPARE_SNAPSHOT=out.png saves the latest frame, to see the studio without the screen.
		System.getenv("PSD2LIVE_PREPARE_SNAPSHOT")?.let { out -> runCatching { javax.imageio.ImageIO.write(image, "png", File(out)) } }
	}

	// Drawn at its own size (device pixels / scale), never stretched: while Chromium catches up with a resize the old
	// frame shows unscaled with a margin instead of squashed.
	override fun paintComponent(g: java.awt.Graphics) {
		super.paintComponent(g)
		val image = frame ?: return
		val s = scale()
		if (s != lastScale) { lastScale = s; browser?.notifyScreenInfoChanged() }
		g.drawImage(image, 0, 0, (image.width / s).toInt(), (image.height / s).toInt(), null)
	}

	private var lastScale = 0.0

	// Every size change reaches Chromium. Compose's SwingPanel sizes this panel through setBounds, which doesn't
	// always come with a componentResized event.
	override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
		val resized = width != this.width || height != this.height
		super.setBounds(x, y, width, height)
		if (resized) browser?.wasResized(width, height)
	}

	private var wheelRest = 0.0

	/**
	 * Scrolls the page as far as Chrome would. JCEF passes only the whole-number rotation on, as that many pixels
	 * (and the other way round), so a trackpad's small steps vanish and a wheel notch is 1 px against Chrome's 40.
	 * Measured 2026-10-14: rotation r arrives as deltaY -r. On macOS, Java's precise rotation is a trackpad's
	 * scrolling delta x 0.1, or a wheel's line count (whole numbers), and Chrome scrolls those as 1 px and 40 px.
	 */
	private fun wheel(browser: org.cef.browser.CefBrowser, e: java.awt.event.MouseWheelEvent) {
		val precise = e.preciseWheelRotation
		val pixels = precise * (if (precise != 0.0 && precise == Math.rint(precise)) 40.0 else 10.0) + wheelRest
		val whole = pixels.toInt()
		wheelRest = pixels - whole
		if (whole == 0) return
		browser.sendMouseWheelEvent(java.awt.event.MouseWheelEvent(this, e.id, e.`when`, e.modifiersEx, e.x, e.y,
			e.xOnScreen, e.yOnScreen, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -whole, -whole.toDouble()))
	}

	fun attach(browser: org.cef.browser.CefBrowser) {
		this.browser = browser
		isFocusable = true
		background = java.awt.Color(0x1e, 0x1e, 0x1e)
		val mouse = object : java.awt.event.MouseAdapter() {
			override fun mousePressed(e: java.awt.event.MouseEvent) { requestFocusInWindow(); browser.setFocus(true); browser.sendMouseEvent(e) }
			override fun mouseReleased(e: java.awt.event.MouseEvent) = browser.sendMouseEvent(e)
			override fun mouseMoved(e: java.awt.event.MouseEvent) = browser.sendMouseEvent(e)
			override fun mouseDragged(e: java.awt.event.MouseEvent) = browser.sendMouseEvent(e)
			override fun mouseEntered(e: java.awt.event.MouseEvent) = browser.sendMouseEvent(e)
			override fun mouseExited(e: java.awt.event.MouseEvent) = browser.sendMouseEvent(e)
			override fun mouseWheelMoved(e: java.awt.event.MouseWheelEvent) = wheel(browser, e)
		}
		addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse)
		addKeyListener(object : java.awt.event.KeyListener {
			override fun keyPressed(e: java.awt.event.KeyEvent) = browser.sendKeyEvent(e)
			override fun keyReleased(e: java.awt.event.KeyEvent) = browser.sendKeyEvent(e)
			override fun keyTyped(e: java.awt.event.KeyEvent) = browser.sendKeyEvent(e)
		})
		addFocusListener(object : java.awt.event.FocusAdapter() {
			override fun focusGained(e: java.awt.event.FocusEvent) = browser.setFocus(true)
			override fun focusLost(e: java.awt.event.FocusEvent) = browser.setFocus(false)
		})
		focusTraversalKeysEnabled = false
	}
}
