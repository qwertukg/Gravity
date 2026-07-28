package dev.gravity

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class SimulationSpeedTest {
    @Test
    fun `wheel schedules fixed steps linearly between one and one hundred percent`() {
        val speed = SimulationSpeed()

        assertEquals(1.0, speed.scale)
        assertEquals(10, List(10) { speed.shouldAdvance(paused = false) }.count { it })

        repeat(10) { speed.scroll(-1.0) }
        assertEquals(0.5, speed.scale, 1e-12)
        assertEquals(10, List(20) { speed.shouldAdvance(paused = false) }.count { it })

        repeat(10) { speed.scroll(-1.0) }
        assertEquals(0.01, speed.scale)
        assertEquals(1, List(100) { speed.shouldAdvance(paused = false) }.count { it })

        speed.scroll(-10.0)
        assertEquals(0.01, speed.scale)

        repeat(20) { speed.scroll(1.0) }
        assertEquals(1.0, speed.scale)
        assertEquals(10, List(10) { speed.shouldAdvance(paused = false) }.count { it })
    }

    @Test
    fun `pause preserves scheduling state and never advances physics`() {
        val speed = SimulationSpeed()
        repeat(10) { speed.scroll(-1.0) }

        assertEquals(0, List(20) { speed.shouldAdvance(paused = true) }.count { it })
        assertEquals(false, speed.shouldAdvance(paused = false))
        assertEquals(true, speed.shouldAdvance(paused = false))
    }

    @Test
    fun `every reachable level schedules the exact long-term step count`() {
        for (scrollsDown in 0..20) {
            val speed = SimulationSpeed()
            repeat(scrollsDown) { speed.scroll(-1.0) }
            val expectedScale = if (scrollsDown == 20) {
                0.01
            } else {
                1.0 - scrollsDown * 0.05
            }

            val advances = List(2_000) {
                speed.shouldAdvance(paused = false)
            }.count { it }
            assertEquals(
                (expectedScale * 2_000).roundToInt(),
                advances,
                "scrollsDown $scrollsDown",
            )
        }
    }
}
