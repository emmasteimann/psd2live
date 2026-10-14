package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import kotlin.test.Test

/** A mouse over the empty canvas, before anything is open, must not throw: it used to take the app down. */
class PlaybackPointerEmptyTest {
	@Test
	fun `pointer moves with no workspace loaded are ignored`() {
		val runtime = WorkspaceRuntime<RigPreviewModel>({ _ -> error("nothing builds") })
		val sessions = WorkspacePlaybackSessions(runtime)
		sessions.pointer("workspace", 0.2f to -0.3f)
		sessions.pointer("workspace", null)
	}
}
