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
                "--square-test=200",
                "--collisions=6",
                "--diameter=1.2",
            ),
            mapOf("GRAVITY_BODIES" to "42", "GRAVITY_GRID" to "1024"),
        )
        assertEquals(120_000, config.initialBodies)
        assertEquals(200_000, config.capacity)
        assertEquals(1024, config.gridWidth)
        assertEquals(512, config.gridHeight)
        assertEquals(200, config.squareTestPixels)
        assertEquals(6, config.collisionIterations)
        assertEquals(1.2f, config.contactDiameterPixels)
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
    fun `invalid collision configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SimulationConfig(collisionIterations = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SimulationConfig(contactDiameterPixels = 0.25f)
        }
    }

}
