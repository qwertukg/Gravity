package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GravityConfigTest {
    @Test
    fun `parser uses constructor defaults`() {
        val config = GravityConfig.parse(emptyArray(), emptyMap())
        assertEquals(GravityConfig(), config)
    }

    @Test
    fun `command line overrides environment across configuration sections`() {
        val config = GravityConfig.parse(
            arrayOf(
                "--bodies=120000",
                "--capacity=200000",
                "--grid=1024x512",
                "--square-test=200",
                "--collisions=6",
                "--adaptive-collisions=true",
                "--diameter=1.2",
                "--pressure-max=80",
                "--trajectory-steps=2048",
                "--trajectory-updates-per-second=60",
                "--projectile-diameter=60",
                "--projectile-drag-scale=5",
                "--time-scale-min=0.02",
                "--profiler-ring=24",
                "--near-exact-bodies=3",
                "--overlay-characters=512",
                "--overlay-margin-px=10",
                "--overlay-line-height-px=14",
            ),
            mapOf("GRAVITY_BODIES" to "42", "GRAVITY_GRID" to "1024"),
        )

        assertEquals(120_000, config.particles.initialBodies)
        assertEquals(200_000, config.particles.capacity)
        assertEquals(1024, config.gravity.gridWidth)
        assertEquals(512, config.gravity.gridHeight)
        assertEquals(3, config.gravity.exactNeighborBodyLimit)
        assertEquals(200, config.particles.squareTestPixels)
        assertEquals(6, config.collisions.maximumSubsteps)
        assertTrue(config.collisions.adaptive)
        assertEquals(1.2f, config.collisions.contactDiameterPixels)
        assertEquals(2_048, config.trajectory.steps)
        assertEquals(60, config.trajectory.updatesPerSecond)
        assertEquals(60f, config.projectiles.defaultDiameterPixels)
        assertEquals(5f, config.projectiles.velocityPerDragPixel)
        assertEquals(80f, config.presentation.maximumVisualPressure)
        assertEquals(0.02, config.presentation.minimumTimeScale)
        assertEquals(512, config.presentation.overlayMaximumCharacters)
        assertEquals(10f, config.presentation.overlayMarginPixels)
        assertEquals(14f, config.presentation.overlayLineHeightPixels)
        assertEquals(24, config.diagnostics.profilerRingSlots)
    }

    @Test
    fun `body count is clipped to capacity`() {
        val config = GravityConfig.parse(
            arrayOf("--bodies=900", "--capacity=500"),
            emptyMap(),
        )
        assertEquals(500, config.particles.initialBodies)
    }

    @Test
    fun `non power of two grid is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            GravityConfig(gravity = GravityFieldConfig(gridWidth = 300))
        }
    }

    @Test
    fun `invalid nested configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            GravityConfig(collisions = CollisionConfig(maximumSubsteps = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            GravityConfig(
                collisions = CollisionConfig(contactDiameterPixels = 0.25f),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GravityConfig(
                presentation = PresentationConfig(maximumVisualPressure = 0f),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GravityConfig(
                trajectory = TrajectoryConfig(updatesPerSecond = 0),
            )
        }
    }

    @Test
    fun `benchmark options enable deterministic profiling`() {
        val config = GravityConfig.parse(
            arrayOf(
                "--benchmark=projectile",
                "--warmup-frames=40",
                "--benchmark-frames=80",
                "--seed=20260730",
            ),
            emptyMap(),
        )

        assertEquals(
            BenchmarkScenario.PROJECTILE,
            config.diagnostics.benchmarkScenario,
        )
        assertEquals(40, config.diagnostics.benchmarkWarmupFrames)
        assertEquals(80, config.diagnostics.benchmarkFrames)
        assertEquals(20_260_730L, config.particles.seed)
        assertTrue(config.diagnostics.profile)
    }

    @Test
    fun `benchmark rejects incompatible finite diagnostic run`() {
        assertFailsWith<IllegalArgumentException> {
            GravityConfig.parse(
                arrayOf("--benchmark=calm", "--frames=10"),
                emptyMap(),
            )
        }
    }

    @Test
    fun `unknown options are rejected instead of silently ignored`() {
        assertFailsWith<IllegalArgumentException> {
            GravityConfig.parse(arrayOf("--typo=1"), emptyMap())
        }
    }

    @Test
    fun `neighbor cache changes search storage without enabling adaptive substeps`() {
        val defaults = GravityConfig.parse(emptyArray(), emptyMap())
        assertTrue(defaults.collisions.neighborCache)
        assertEquals(false, defaults.collisions.adaptive)
        assertEquals(16, defaults.collisions.maximumSubsteps)
        val disabled = GravityConfig.parse(arrayOf("--collision-cache=false", "--collision-skin=0.75"), emptyMap())
        assertEquals(false, disabled.collisions.neighborCache)
        assertEquals(0.75f, disabled.collisions.neighborSkinPixels)
        for (skin in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { CollisionConfig(neighborSkinPixels = skin) }
        }
    }
}
