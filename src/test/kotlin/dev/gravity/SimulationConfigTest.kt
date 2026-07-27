package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SimulationConfigTest {
    @Test
    fun `defaults use exactly 1000000 fixed bodies`() {
        val config = SimulationConfig.parse(emptyArray(), emptyMap())
        assertEquals(1_000_000, config.initialBodies)
        assertEquals(1_000_000, config.capacity)
        assertEquals(1024, config.gridWidth)
        assertEquals(1024, config.gridHeight)
        assertEquals(true, config.fixedBodyCount)
    }

    @Test
    fun `command line overrides environment`() {
        val config = SimulationConfig.parse(
            arrayOf("--bodies=120000", "--capacity=200000", "--grid=1024x512", "--fixed"),
            mapOf("GRAVITY_BODIES" to "42", "GRAVITY_GRID" to "1024"),
        )
        assertEquals(120_000, config.initialBodies)
        assertEquals(200_000, config.capacity)
        assertEquals(1024, config.gridWidth)
        assertEquals(512, config.gridHeight)
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
            SimulationConfig(gridWidth = 300)
        }
    }
}
