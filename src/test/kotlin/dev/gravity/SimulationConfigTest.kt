package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SimulationConfigTest {
    @Test
    fun `command line overrides environment`() {
        val config = SimulationConfig.parse(
            arrayOf("--bodies=120000", "--capacity=200000", "--grid=256", "--fixed"),
            mapOf("GRAVITY_BODIES" to "42", "GRAVITY_GRID" to "1024"),
        )
        assertEquals(120_000, config.initialBodies)
        assertEquals(200_000, config.capacity)
        assertEquals(256, config.gridSize)
        assertEquals(true, config.fixedBodyCount)
    }

    @Test
    fun `body count is clipped to capacity`() {
        val config = SimulationConfig.parse(
            arrayOf("--bodies=900", "--capacity=500"),
            emptyMap(),
        )
        assertEquals(500, config.initialBodies)
    }

    @Test
    fun `non power of two grid is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SimulationConfig(gridSize = 300)
        }
    }
}
