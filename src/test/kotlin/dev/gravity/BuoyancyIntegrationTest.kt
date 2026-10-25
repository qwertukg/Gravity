package dev.gravity

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

/**
 * Archimedes from collisions alone: a block differs from the pile only in the
 * mass of its bodies. The pile is a granular solid, so a block finds its level
 * when dropped onto it, as on sand, rather than rising from deep inside.
 */
class BuoyancyIntegrationTest {
    private val width = 256
    private val height = 256
    private val blockHeight = 30f

    @Test
    fun `a light block dropped onto a pile floats at its surface`() {
        val (surface, center) = drop(density = 0.5f)
        val bottom = center - 0.5f * blockHeight
        assertTrue(bottom > surface - 0.5f * blockHeight, "floats: bottom $bottom px, surface $surface px")
        assertTrue(bottom < surface, "partly submerged: bottom $bottom px, surface $surface px")
    }

    @Test
    fun `a heavy block dropped onto a pile sinks below its surface`() {
        val (surface, center) = drop(density = 3f)
        val top = center + 0.5f * blockHeight
        assertTrue(top < surface - 10f, "sinks: top $top px, surface $surface px")
    }

    /** Pile surface and block centre (px) after the block settles. */
    private fun drop(density: Float): Pair<Float, Float> {
        val count = 36_000
        val random = Random(1)
        // Bodies fall from the lower three quarters and settle into a pile.
        val bodies = FloatArray(count * 4) { i -> if (i % 4 < 2) random.nextFloat() * 0.75f else 0f }
        var result = Float.NaN to Float.NaN
        CollisionGpuContext().use {
            GravityPipeline(config(count, density), width, height, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.fieldAccelerationY = -300f
                CollisionGpuAccess.upload(pipeline, bodies)
                pipeline.initializeAcceleration(count)
                repeat(360) { pipeline.step(count) }
                val surface = surface(pipeline, count)
                val block = pipeline.spawnBlock(count, 108f, 200f, 148f, 200f + blockHeight)
                assertTrue(block > 500)
                val active = count + block
                repeat(720) { pipeline.step(active) }
                result = surface to blockCenterY(pipeline, active, density)
                println("density=$density surface=${result.first} px block centre=${result.second} px")
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
        return result
    }

    /** Height below which 98% of the pile lies. */
    private fun surface(pipeline: GravityPipeline, count: Int): Float {
        val bodies = CollisionGpuAccess.floats(pipeline, "bodies", count * 4)
        return (0 until count).map { bodies[it * 4 + 1] * height }.sorted()[count * 98 / 100]
    }

    private fun blockCenterY(pipeline: GravityPipeline, active: Int, density: Float): Float {
        val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
        val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
        return (0 until active).filter { materials[4 * it] == density }
            .map { bodies[it * 4 + 1] * height }.average().toFloat()
    }

    private fun config(count: Int, density: Float) = GravityConfig(
        particles = ParticleConfig(capacity = count + 4096, initialBodies = count, seed = 7235),
        gravity = GravityFieldConfig(gridWidth = 128, gridHeight = 128),
        projectiles = ProjectileConfig(blockDensity = density, blockStrength = Float.POSITIVE_INFINITY),
    )
}
