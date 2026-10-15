package io.github.psd2live.ui.components
import io.github.psd2live.core.nanoTime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import io.github.psd2live.ui.state.KeyBinding
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.ShortcutAction

/**
 * The app's menus as plain data: one list of commands that the macOS menu bar draws today and the in-window
 * title bar can draw later (and an iPad shell after that). Items carry an id instead of a lambda, so two
 * builds of the same menus compare equal and the menu bar only changes when a label, a check or an enabled
 * state does; the click handlers travel next to the model in [AppMenuBuild.handlers].
 */
sealed interface AppMenuNode {
	data class Item(
		val id: String,
		val text: String,
		/** The shortcut action this item runs, for its key label and for [MenuShortcutGuard]. */
		val action: ShortcutAction? = null,
		val enabled: Boolean = true,
		/** Non-null for a checkable item. */
		val checked: Boolean? = null,
	) : AppMenuNode

	data class Submenu(val text: String, val children: List<AppMenuNode>, val enabled: Boolean = true) : AppMenuNode
	data class Header(val text: String) : AppMenuNode
	data object Separator : AppMenuNode
}

data class AppMenu(val title: String, val items: List<AppMenuNode>)

class AppMenuBuild(val menus: List<AppMenu>, val handlers: Map<String, () -> Unit>)

class AppMenuBuilder constructor(private val handlers: MutableMap<String, () -> Unit>) {
	val nodes = mutableListOf<AppMenuNode>()

	fun item(id: String, text: String, action: ShortcutAction? = null, enabled: Boolean = true, checked: Boolean? = null, onClick: () -> Unit) {
		nodes += AppMenuNode.Item(id, text, action, enabled, checked)
		handlers[id] = onClick
	}

	fun submenu(text: String, enabled: Boolean = true, content: AppMenuBuilder.() -> Unit) {
		val child = AppMenuBuilder(handlers).apply(content)
		nodes += AppMenuNode.Submenu(text, child.nodes.toList(), enabled)
	}

	fun header(text: String) { nodes += AppMenuNode.Header(text) }
	fun separator() { nodes += AppMenuNode.Separator }
}

class AppMenusBuilder constructor() {
	private val handlers = mutableMapOf<String, () -> Unit>()
	private val menus = mutableListOf<AppMenu>()

	fun menu(title: String, content: AppMenuBuilder.() -> Unit) {
		menus += AppMenu(title, AppMenuBuilder(handlers).apply(content).nodes.toList())
	}

	fun build() = AppMenuBuild(menus.toList(), handlers.toMap())
}

fun appMenus(content: AppMenusBuilder.() -> Unit): AppMenuBuild = AppMenusBuilder().apply(content).build()

/**
 * Where the app hands its menus to the window that draws the menu bar. PSD2LiveApp builds them; Main's window
 * scope reads [menus]. The handlers are a plain field: replacing them must not redraw the menu bar.
 */
class AppMenuSink {
	var menus: List<AppMenu> by mutableStateOf(emptyList())
		private set
	private var handlers: Map<String, () -> Unit> = emptyMap()

	fun publish(build: AppMenuBuild) {
		handlers = build.handlers
		if (menus != build.menus) menus = build.menus
	}

	fun select(item: AppMenuNode.Item) {
		val action = item.action
		if (action != null && !MenuShortcutGuard.allow(action, fromMenu = true)) return
		handlers[item.id]?.invoke()
	}
}

val LocalAppMenuSink = compositionLocalOf<AppMenuSink?> { null }

/**
 * A native menu item with a key equivalent and the app's own key handler can both see one key press, in either
 * order. Whichever runs first wins; the other side drops the same action for a moment. Repeats from one side
 * (a held zoom key) still all pass.
 */
object MenuShortcutGuard {
	private const val WINDOW_NANOS = 250_000_000L
	private val last = HashMap<ShortcutAction, Pair<Boolean, Long>>()

	@Synchronized
	fun allow(action: ShortcutAction, fromMenu: Boolean, now: Long = nanoTime()): Boolean {
		val previous = last[action]
		if (previous != null && previous.first != fromMenu && now - previous.second < WINDOW_NANOS) {
			last.remove(action)
			return false
		}
		last[action] = fromMenu to now
		return true
	}
}

/**
 * The key equivalent macOS shows next to an item and fires itself. Only Command chords: a bare key or an
 * Option-only chord there would be taken from text fields and the canvas.
 */
fun KeyBinding.menuShortcut(): KeyShortcut? {
	val key = key ?: return null
	if (mouse != null || !ctrl) return null
	return KeyShortcut(key, meta = true, alt = alt, shift = shift)
}

/** Draws the menus in the macOS menu bar. */
@Composable
fun FrameWindowScope.NativeAppMenuBar(sink: AppMenuSink, keymap: Keymap) {
	val menus = sink.menus
	if (menus.isEmpty()) return
	MenuBar {
		for (menu in menus) Menu(menu.title) { nativeNodes(menu.items, sink, keymap) }
	}
}

@Composable
private fun MenuScope.nativeNodes(nodes: List<AppMenuNode>, sink: AppMenuSink, keymap: Keymap) {
	for (node in nodes) when (node) {
		is AppMenuNode.Item -> {
			val shortcut = node.action?.let { keymap.bindingsFor(it).firstNotNullOfOrNull { b -> b.menuShortcut() } }
			if (node.checked != null) {
				CheckboxItem(node.text, checked = node.checked, enabled = node.enabled, shortcut = shortcut,
					onCheckedChange = { sink.select(node) })
			} else {
				Item(node.text, enabled = node.enabled, shortcut = shortcut, onClick = { sink.select(node) })
			}
		}
		is AppMenuNode.Submenu -> Menu(node.text, enabled = node.enabled) { nativeNodes(node.children, sink, keymap) }
		is AppMenuNode.Header -> Item(node.text, enabled = false, onClick = {})
		AppMenuNode.Separator -> Separator()
	}
}
