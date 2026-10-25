package dev.gravity

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

/**
 * Blocks break like brittle solids: an impact faster than the material's
 * critical speed chips grains off where it strikes and cracks the block with
 * the energy it brings beyond that; pieces that came apart fly on as blocks
 * of their own. Slow contacts and rest never break anything, and breaking
 * keeps momentum.
 */
class BlockFractureTest {
    private val width = 1920
    private val height = 1080
    private val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS
    private val space = ScenarioSpace(width.toFloat(), height.toFloat(), GravityFieldConfig().gravitationalConstant, diameter)

    /** What a run left: grains broken off and blocks, with the momentum (mass x px/s) before and after. */
    private class Outcome(val grains: Int, val blocks: Int, val momentumBefore: Double, val momentumAfter: Double) {
        override fun toString() = "grains=$grains blocks=$blocks momentum x %.1f -> %.1f".format(momentumBefore, momentumAfter)
    }

    private fun run(
        name: String,
        bodies: FloatArray,
        field: Boolean,
        steps: Int,
        strength: Float = ProjectileConfig().blockStrength,
        liquid: Boolean = false,
        build: (GravityPipeline, Int) -> Int,
    ): Outcome {
        val count = bodies.size / 4
        lateinit var outcome: Outcome
        CollisionGpuContext().use {
            val config = GravityConfig(
                particles = ParticleConfig(capacity = count + 100_000, initialBodies = count, seed = 1),
                projectiles = ProjectileConfig(blockStrength = strength),
            )
            GravityPipeline(config, width, height, null, bodies, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.liquid = liquid
                if (field) pipeline.fieldAccelerationY = -config.gravity.uniformFieldAcceleration * height / 1440f
                val active = build(pipeline, count)
                pipeline.initializeAcceleration(active)
                val before = momentumX(pipeline, active)
                repeat(steps) { pipeline.step(active) }
                pipeline.settleFractures()
                val state = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
                val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
                assertTrue(state.all { it.isFinite() }, "$name: finite")
                val grains = (0 until active).count { materials[it * 4 + 2] != 0f && materials[it * 4 + 3] < 0f }
                val blocks = (0 until active).filter { materials[it * 4 + 2] != 0f && materials[it * 4 + 3] >= 0f }
                    .map { materials[it * 4 + 3] }.distinct().size
                outcome = Outcome(grains, blocks, before, momentumX(pipeline, active))
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
        println("$name: $outcome")
        return outcome
    }

    private fun run(scenario: BlockScenario, steps: Int, strength: Float = ProjectileConfig().blockStrength): Outcome {
        val setup = scenario.setup(space, seed = 1234L)
        return run(scenario.title, setup.bodies, scenario.field, steps, strength, scenario.liquid) { pipeline, first ->
            var active = first
            for (block in setup.blocks) {
                active += pipeline.spawnBlock(active, block.minX, block.minY, block.maxX, block.maxY,
                    block.density, block.velocityX, block.velocityY, block.spin)
            }
            active
        }
    }

    private fun momentumX(pipeline: GravityPipeline, active: Int): Double {
        val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
        val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
        return (0 until active).sumOf { materials[it * 4] * bodies[it * 4 + 2].toDouble() * width }
    }

    /** One body parked in a top corner: a scene of blocks and shots alone. */
    private val parked = floatArrayOf(2f / width, 1f - 2f / height, 0f, 0f)

    @Test
    fun `slow contacts and rest break nothing`() {
        for (scenario in listOf(
            BlockScenario.STACK, BlockScenario.BIG_ON_SMALL, BlockScenario.BOUNCE, BlockScenario.SLIDING,
            BlockScenario.BODIES, BlockScenario.SLOW_BODY, BlockScenario.THROWN, BlockScenario.LIQUID,
        )) {
            val outcome = run(scenario, steps = 240)
            assertEquals(0, outcome.grains, "${scenario.title}: no grain breaks off")
            assertEquals(scenario.setup(space, seed = 1234L).blocks.size, outcome.blocks, "${scenario.title}: every block whole")
        }
    }

    @Test
    fun `a ram cracks both blocks into pieces and keeps momentum`() {
        val outcome = run(BlockScenario.RAM, steps = 60)
        assertTrue(outcome.grains > 0, "grains break off")
        assertTrue(outcome.blocks > 2, "the blocks crack into pieces: ${outcome.blocks}")
        assertEquals(outcome.momentumBefore, outcome.momentumAfter, 1e-3 * abs(outcome.momentumBefore), "momentum")
    }

    @Test
    fun `unbreakable blocks stay whole`() {
        val outcome = run(BlockScenario.RAM, steps = 60, strength = Float.POSITIVE_INFINITY)
        assertEquals(0, outcome.grains)
        assertEquals(2, outcome.blocks)
    }

    @Test
    fun `a fast ball cuts a thin plate in two`() {
        val outcome = run("Ball through a plate", parked, field = false, steps = 40) { pipeline, first ->
            var active = first
            active += pipeline.spawnBlock(active, 960f, 440f, 963f, 640f)
            active += pipeline.spawnProjectile(active, 800f, 540f, 2500f, 0f, 20f)
            active
        }
        assertTrue(outcome.blocks >= 2, "the plate is cut: ${outcome.blocks} pieces")
    }

    @Test
    fun `a slow ball leaves a block whole`() {
        val outcome = run("Slow ball", parked, field = false, steps = 60) { pipeline, first ->
            var active = first
            active += pipeline.spawnBlock(active, 960f, 490f, 1060f, 590f)
            active += pipeline.spawnProjectile(active, 900f, 540f, 300f, 0f, 30f)
            active
        }
        assertEquals(0, outcome.grains)
        assertEquals(1, outcome.blocks)
    }

    @Test
    fun `a block dropped from high cracks on the floor and one dropped low does not`() {
        val low = run("Low drop", parked, field = true, steps = 240) { pipeline, first ->
            first + pipeline.spawnBlock(first, 800f, 150f, 920f, 200f)
        }
        assertEquals(0, low.grains)
        val high = run("High drop", parked, field = true, steps = 420) { pipeline, first ->
            first + pipeline.spawnBlock(first, 800f, 1000f, 920f, 1050f)
        }
        assertTrue(high.grains > 0, "the high drop breaks grains off")
    }

    @Test
    fun `every block scenario stays sound while breaking`() {
        for (scenario in BlockScenario.entries) run(scenario, steps = 240)
    }
}
