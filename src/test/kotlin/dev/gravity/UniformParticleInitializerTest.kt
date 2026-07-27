package dev.gravity

import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UniformParticleInitializerTest {
    @Test
    fun `particles uniformly fill normalized screen with zero velocity and unit mass`() {
        val count = 20_000
        val bodies = FloatBuffer.allocate(count * 4)
        val masses = FloatBuffer.allocate(count)
        val state = UniformParticleInitializer.fill(bodies, masses, count, seed = 73L)

        var meanX = 0.0
        var meanY = 0.0
        repeat(count) { id ->
            val offset = id * 4
            val x = bodies[offset]
            val y = bodies[offset + 1]
            assertTrue(x >= 0f && x < 1f)
            assertTrue(y >= 0f && y < 1f)
            assertEquals(0f, bodies[offset + 2])
            assertEquals(0f, bodies[offset + 3])
            assertEquals(1f, masses[id])
            meanX += x
            meanY += y
        }

        assertTrue(meanX / count in 0.49..0.51)
        assertTrue(meanY / count in 0.49..0.51)
        assertEquals(count.toDouble(), state.massPrefix.last())
        assertEquals(count.toFloat(), state.normalizationMass)
        assertEquals(73L, state.seed)
    }

    @Test
    fun `same seed exactly reproduces initial state`() {
        val count = 500

        fun generate(): Pair<FloatBuffer, FloatBuffer> {
            val bodies = FloatBuffer.allocate(count * 4)
            val masses = FloatBuffer.allocate(count)
            UniformParticleInitializer.fill(bodies, masses, count, seed = 101L)
            return bodies to masses
        }

        val first = generate()
        val second = generate()
        repeat(count * 4) { assertEquals(first.first[it], second.first[it]) }
        repeat(count) { assertEquals(first.second[it], second.second[it]) }
    }

    @Test
    fun `default seed changes between initializations`() {
        val first = UniformParticleInitializer.fill(
            FloatBuffer.allocate(4),
            FloatBuffer.allocate(1),
            1,
        )
        val second = UniformParticleInitializer.fill(
            FloatBuffer.allocate(4),
            FloatBuffer.allocate(1),
            1,
        )

        assertTrue(first.seed != second.seed)
    }
}
