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
                "--pressure-max=80",
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
        assertEquals(80f, config.maximumVisualPressure)
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
        assertFailsWith<IllegalArgumentException> {
            SimulationConfig(maximumVisualPressure = 0f)
        }
    }

    @Test
    fun `benchmark options enable deterministic profiling`() {
        val config = SimulationConfig.parse(
            arrayOf(
                "--benchmark=projectile",
                "--warmup-frames=40",
                "--benchmark-frames=80",
                "--seed=20260730",
            ),
            emptyMap(),
        )

        assertEquals(BenchmarkScenario.PROJECTILE, config.benchmarkScenario)
        assertEquals(40, config.benchmarkWarmupFrames)
        assertEquals(80, config.benchmarkFrames)
        assertEquals(20_260_730L, config.seed)
        assertEquals(true, config.profile)
    }

    @Test
    fun `benchmark rejects incompatible finite diagnostic run`() {
        assertFailsWith<IllegalArgumentException> {
            SimulationConfig.parse(
                arrayOf("--benchmark=calm", "--frames=10"),
                emptyMap(),
            )
        }
    }

}
