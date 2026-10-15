package org.umamo.runtime.eval

import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelSamplingBoundsTest {
	@Test
	fun emptyColorTrackUsesTheStaticColor() {
		val parameter = ParameterId("Angle")
		val grid = KeyformGrid<ChannelValue>(listOf(KeyformAxis(parameter, floatArrayOf(0f, 1f))), emptyList())
		val channels = ChannelGrids(mapOf(FormChannel.MULTIPLY_COLOR to grid))
		val static = ColorRgb(0.2f, 0.4f, 0.6f)
		assertEquals(static, channels.colorAt(FormChannel.MULTIPLY_COLOR, static, { _ -> 0f }))
	}

	@Test
	fun emptyScalarTrackIsAbsent() {
		val parameter = ParameterId("Angle")
		val grid = KeyformGrid<ChannelValue>(listOf(KeyformAxis(parameter, floatArrayOf(0f))), emptyList())
		val channels = ChannelGrids(mapOf(FormChannel.OPACITY to grid))
		assertNull(channels.scalarOrNull(FormChannel.OPACITY, { _ -> 0f }))
		assertEquals(0.5f, channels.scalarAt(FormChannel.OPACITY, 0.5f, { _ -> 0f }))
	}
}
