package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SimulationConfigTest {
    @Test
    fun `parser uses constructor defaults`() {
        val config = SimulationConfig.parse(emptyArray(), emptyMap())
        assertEquals(SimulationConfig(), config)
    }

    @Test
    fun `command line overrides environment`() {
        val config = SimulationConfig.parse(
            arrayOf(
                "--bodies=120000",
                "--capacity=200000",
                "--grid=1024x512",
                "--collision-iterations=3",
                "--collision-substeps=2",
                "--fixed",
            ),
            mapOf("GRAVITY_BODIES" to "42", "GRAVITY_GRID" to "1024"),
        )
        assertEquals(120_000, config.initialBodies)
        assertEquals(200_000, config.capacity)
        assertEquals(1024, config.gridWidth)
        assertEquals(512, config.gridHeight)
        assertEquals(3, config.collisionIterations)
        assertEquals(2, config.collisionSubsteps)
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

    @Test
    fun `collision iteration count is bounded`() {
        assertFailsWith<IllegalArgumentException> {
            SimulationConfig(collisionIterations = 9)
        }
    }

    @Test
    fun `collision substep count is bounded`() {
        assertFailsWith<IllegalArgumentException> {
            SimulationConfig(collisionSubsteps = 9)
        }
    }
}
