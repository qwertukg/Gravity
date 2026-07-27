package dev.gravity

import kotlin.math.floor
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals

class PeriodicSpaceTest {
    @Test
    fun `positions wrap without changing velocity`() {
        assertEquals(0.75 to -37.5, wrappedState(-0.25, -37.5))
        assertEquals(0.25 to 18.0, wrappedState(1.25, 18.0))
        assertEquals(0.0 to 4.0, wrappedState(2.0, 4.0))
    }

    @Test
    fun `minimum image crosses the nearest side of the torus`() {
        assertEquals(0.02, minimumImage(0.01 - 0.99), absoluteTolerance = 1e-12)
        assertEquals(-0.02, minimumImage(0.99 - 0.01), absoluteTolerance = 1e-12)
        assertEquals(0.40, minimumImage(0.40), absoluteTolerance = 1e-12)
    }

    @Test
    fun `collision distance is continuous across screen seam`() {
        val screenWidth = 3_440.0
        val left = 0.1 / screenWidth
        val right = 1.0 - 0.1 / screenWidth
        val separationPixels = minimumImage(left - right) * screenWidth

        assertEquals(0.2, separationPixels, absoluteTolerance = 1e-9)
    }

    private fun wrap(value: Double): Double = value - floor(value)

    private fun wrappedState(position: Double, velocity: Double): Pair<Double, Double> =
        wrap(position) to velocity

    private fun minimumImage(delta: Double): Double = delta - round(delta)
}
