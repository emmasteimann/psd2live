package io.github.psd2live.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals

/** Layerloom patch: on macOS shortcuts read as Apple writes them; elsewhere as before. */
class MacKeyLabelsTest {
	@Test
	fun labelsFollowThePlatform() {
		val chord = parseKeyBinding("Ctrl+Shift+O")!!
		assertEquals("Ctrl+Shift+O", chord.format(), "the stored form never changes")
		if (isMacOs) {
			assertEquals("⇧⌘O", chord.display())
			assertEquals(listOf("⌥", "⇧", "⌘", "V"), parseKeyBinding("Ctrl+Alt+Shift+V")!!.displayTokens())
			assertEquals("⌘↩", parseKeyBinding("Ctrl+Enter")!!.display())
			assertEquals("Click · ⇧ add · ⌥ subtract · ⌘Click walks · {kbd:Ctrl} stays · {key:SELECT_ALL}",
				platformKeyWords("Click · Shift add · Alt subtract · Ctrl+Click walks · {kbd:Ctrl} stays · {key:SELECT_ALL}"))
			assertEquals("Alternatively, shifting", platformKeyWords("Alternatively, shifting"), "only the key words")
		} else {
			assertEquals("Ctrl+Shift+O", chord.display())
			assertEquals("Shift add · Alt subtract", platformKeyWords("Shift add · Alt subtract"))
		}
	}
}
