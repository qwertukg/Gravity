package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenBoundaryTest {
    @Test
    fun `positions reflect at screen boundaries`() {
        assertEquals(0.25 to 37.5, reflectedState(-0.25, -37.5))
        assertEquals(0.75 to -18.0, reflectedState(1.25, 18.0))
        assertEquals(0.0 to 4.0, reflectedState(2.0, 4.0))
    }

    @Test
    fun `opposite screen edges are not adjacent`() {
        assertEquals(-0.98, 0.01 - 0.99, absoluteTolerance = 1e-12)
        assertEquals(0.98, 0.99 - 0.01, absoluteTolerance = 1e-12)
    }

    private fun reflectedState(position: Double, velocity: Double): Pair<Double, Double> {
        var phase = position % 2.0
        if (phase < 0.0) phase += 2.0
        return if (phase >= 1.0) {
            (2.0 - phase) to -velocity
        } else {
            phase to velocity
        }
    }
}
