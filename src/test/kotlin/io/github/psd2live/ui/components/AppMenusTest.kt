package io.github.psd2live.ui.components

import androidx.compose.ui.input.key.Key
import io.github.psd2live.ui.state.KeyBinding
import io.github.psd2live.ui.state.ShortcutAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppMenusTest {
	@Test
	fun `one key press seen by the menu bar and the key handler runs once`() {
		val t = 1_000_000_000_000L
		assertTrue(MenuShortcutGuard.allow(ShortcutAction.SAVE_PROJECT, fromMenu = true, now = t))
		assertFalse(MenuShortcutGuard.allow(ShortcutAction.SAVE_PROJECT, fromMenu = false, now = t + 5_000_000))
		// The other order too.
		assertTrue(MenuShortcutGuard.allow(ShortcutAction.ZOOM_IN, fromMenu = false, now = t))
		assertFalse(MenuShortcutGuard.allow(ShortcutAction.ZOOM_IN, fromMenu = true, now = t + 5_000_000))
	}

	@Test
	fun `repeats from one side and later presses all pass`() {
		val t = 2_000_000_000_000L
		assertTrue(MenuShortcutGuard.allow(ShortcutAction.ZOOM_OUT, fromMenu = false, now = t))
		assertTrue(MenuShortcutGuard.allow(ShortcutAction.ZOOM_OUT, fromMenu = false, now = t + 30_000_000))
		assertTrue(MenuShortcutGuard.allow(ShortcutAction.ZOOM_OUT, fromMenu = true, now = t + 600_000_000))
	}

	@Test
	fun `only command chords become menu key equivalents`() {
		assertEquals(androidx.compose.ui.input.key.KeyShortcut(Key.S, meta = true), KeyBinding(Key.S, ctrl = true).menuShortcut())
		assertNull(KeyBinding(Key.S).menuShortcut())
		assertNull(KeyBinding(Key.S, alt = true).menuShortcut())
	}

	@Test
	fun `menus compare by content so the menu bar redraws only on real changes`() {
		fun build(enabled: Boolean) = appMenus {
			menu("File") { item("save", "Save", ShortcutAction.SAVE_PROJECT, enabled) {}; separator() }
		}
		assertEquals(build(true).menus, build(true).menus)
		assertNotEquals(build(true).menus, build(false).menus)
		var ran = 0
		val sink = AppMenuSink()
		sink.publish(appMenus { menu("Help") { item("x", "X") { ran++ } } })
		sink.select(sink.menus.single().items.single() as AppMenuNode.Item)
		assertEquals(1, ran)
	}
}
