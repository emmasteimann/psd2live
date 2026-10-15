package io.github.psd2live.i18n

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The desktop's bundles, embedded: the iPad finds, formats and falls back as ResourceBundle does. */
class IpadTranslationsTest {
	private val saved = IpadTranslations.languageTag

	@AfterTest
	fun restore() { IpadTranslations.languageTag = saved }

	@Test
	fun messagesComeFromTheEmbeddedBundles() {
		IpadTranslations.languageTag = "en"
		assertEquals("en", currentLanguageTag())
		assertEquals("日本語", tr("language.japanese"))
		assertEquals("Painting \"quill\" · 12346 stroke(s)", tr("editor.paint.sessionSummary", "quill", 12345.6))
		IpadTranslations.languageTag = "ja"
		assertEquals("ペイント「quill」· 3 ストローク", tr("editor.paint.sessionSummary", "quill", 3))
		assertEquals("!no.such.key!", tr("no.such.key"))
	}
}
