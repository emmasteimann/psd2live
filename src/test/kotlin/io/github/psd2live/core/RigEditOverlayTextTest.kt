package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layerloom patch: revisions hash RigEditOverlay's text, so without the patch's fields it must be exactly the text
 * upstream's data class generates (checked against that format), and with them it must differ.
 */
class RigEditOverlayTextTest {
	@Test
	fun upstreamTextUntilThePatchFieldsAreSet() {
		val upstream = "RigEditOverlay(importedCmo3=null, importedLayerIds={}, skeleton=null, parameterEdits=[], deletedParameterIds=[], " +
			"keyformSetEdits=[], keyformDeleteEdits=[], keyformCopyEdits=[], warpEdits=[], physicsEdits=[], disabledPhysicsIds=[], " +
			"physicsOrder=[], physicsFps=${RigEditOverlay.DEFAULT_PHYSICS_FPS}, swingEdits=[], assetLayers={}, calibrationLayerIds=[], splitBaselineLayerIds=[], " +
			"splitDrawableIds={}, structureEdits=[], authoringJournal=[], motionClips=[], motionPresets={}, simEdits=[])"
		assertEquals(upstream, RigEditOverlay().toString())
		val withPose = RigEditOverlay(poseGroups = listOf(listOf("a", "b")))
		assertTrue(withPose.toString() != upstream && "poseGroups=[[a, b]]" in withPose.toString())
		assertTrue("expressions=" in RigEditOverlay(expressions = listOf(ExpressionEdit("e", "E"))).toString())
	}
}
