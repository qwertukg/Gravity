package dev.gravity

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

class FrictionIntegrationTest {
    private val width = 256
    private val height = 256
    private val diameter = 1.12838f
    private val field = 300f

    @Test
    fun `a body slides on a smooth floor and stops on a rough one`() {
        val smooth = slide(friction = 0f)
        assertEquals(100f, smooth.first, 0.5f, "no friction keeps the sliding speed")

        val rough = slide(friction = 0.5f)
        assertEquals(0f, rough.first, 0.5f, "friction stops the body")
        // Coulomb: v^2 / (2 mu g) = 100^2 / (2 * 0.5 * 300) = 33.3 px.
        assertEquals(33.3f, rough.second, 3f, "stopping distance")
    }

    @Test
    fun `friction between bodies conserves momentum and reduces sliding`() {
        val smooth = glancingHit(friction = 0f)
        val rough = glancingHit(friction = 1f)
        assertEquals(smooth.first, rough.first, 1e-3f, "momentum x")
        assertEquals(smooth.second, rough.second, 1e-3f, "momentum y")
        assertTrue(rough.third < smooth.third - 1f, "tangential slip ${rough.third} < ${smooth.third} px/s")
    }

    /** Final horizontal speed and travelled distance of a body sliding on the floor. */
    private fun slide(friction: Float): Pair<Float, Float> {
        val startX = 50f
        val body = floatArrayOf(startX / width, 0.5f * diameter / height, 100f / width, 0f)
        var result = Float.NaN to Float.NaN
        CollisionGpuContext().use {
            GravityPipeline(config(1, friction), width, height, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.fieldAccelerationY = -field
                CollisionGpuAccess.upload(pipeline, body)
                pipeline.initializeAcceleration(1)
                repeat(240) { pipeline.step(1) }
                val state = CollisionGpuAccess.floats(pipeline, "bodies", 4)
                result = state[2] * width to state[0] * width - startX
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
        return result
    }

    /** Total momentum (x, y) and tangential relative speed after one glancing contact pass. */
    private fun glancingHit(friction: Float): Triple<Float, Float, Float> {
        // Overlapping bodies approaching along x, offset along y, sliding along y.
        val bodies = floatArrayOf(
            100f / width, 100f / height, 40f / width, 30f / height,
            (100f + 0.9f * diameter) / width, 100f / height, -40f / width, -30f / height,
        )
        var result = Triple(Float.NaN, Float.NaN, Float.NaN)
        CollisionGpuContext().use {
            GravityPipeline(config(2, friction), width, height).use { pipeline ->
                CollisionGpuAccess.upload(pipeline, bodies)
                CollisionGpuAccess.collide(pipeline, 2)
                val state = CollisionGpuAccess.floats(pipeline, "bodies", 8)
                val momentumX = (state[2] + state[6]) * width
                val momentumY = (state[3] + state[7]) * height
                result = Triple(momentumX, momentumY, abs(state[3] - state[7]) * height)
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
        return result
    }

    private fun config(count: Int, friction: Float) = GravityConfig(
        particles = ParticleConfig(capacity = count, initialBodies = count, seed = 7235),
        gravity = GravityFieldConfig(gridWidth = 128, gridHeight = 128),
        collisions = CollisionConfig(friction = friction),
    )
}
