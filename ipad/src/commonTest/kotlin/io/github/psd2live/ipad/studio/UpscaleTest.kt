package io.github.psd2live.ipad.studio

import kotlin.test.Test
import kotlin.test.assertTrue

class UpscaleTest {
	@Test fun theModelAnswersSomething() {
		val path = "/Users/goosible/Downloads/neuro-goose/psd2live-main/ipad/iosApp/build/dd/Build/Products/Debug-iphonesimulator/Layerloom.app/AnimeUpscaler.mlmodelc"
		if (!okio.FileSystem.SYSTEM.exists(okio.Path.Companion.run { path.toPath() })) return
		AnimeUpscaler.useModelAt(path)
		val t = AnimeUpscaler.tile
		val input = FloatArray(3 * t * t) { (it % 97) / 97f }
		val out = AnimeUpscaler.run(input)
		println("UPSCALE " + AnimeUpscaler.lastInfo)
		assertTrue(out.max() > 0.2f, AnimeUpscaler.lastInfo)
	}
}
