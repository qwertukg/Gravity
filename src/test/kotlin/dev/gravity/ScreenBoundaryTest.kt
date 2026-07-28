package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenBoundaryTest {
    @Test
    fun `positions wrap at screen boundaries without changing velocity`() {
        assertEquals(0.75 to -37.5, wrappedState(-0.25, -37.5))
        assertEquals(0.25 to 18.0, wrappedState(1.25, 18.0))
        assertEquals(0.0 to 4.0, wrappedState(2.0, 4.0))
        assertEquals(0.125 to 7.0, wrappedState(-12.875, 7.0))
        assertEquals(99.75f, ToroidalSpace.wrapPixels(-0.25f, 100))
        assertEquals(0f, ToroidalSpace.wrapPixels(300f, 100))
    }

    @Test
    fun `opposite screen edges are adjacent through minimum image`() {
        assertEquals(0.02, ToroidalSpace.minimumImage(0.01 - 0.99), 1e-12)
        assertEquals(-0.02, ToroidalSpace.minimumImage(0.99 - 0.01), 1e-12)
        assertEquals(-0.5, ToroidalSpace.minimumImage(0.5), 1e-12)
    }

    private fun wrappedState(position: Double, velocity: Double): Pair<Double, Double> =
        ToroidalSpace.wrapUnit(position) to velocity
}
