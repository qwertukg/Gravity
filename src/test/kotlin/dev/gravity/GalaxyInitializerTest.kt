package dev.gravity

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalaxyInitializerTest {
    @Test
    fun `twin disks touch opposite screen edges and rotate clockwise`() {
        val count = 20_000
        val domainAspect = 1f
        val viewportAspect = 3440f / 1440f
        val preset = GalaxyPreset.fittingViewportHeight(viewportAspect)
        val bodies = ByteBuffer.allocateDirect(count * 4 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val masses = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val state = GalaxyInitializer.fillTwin(
            bodies,
            masses,
            count,
            domainAspect,
            gravity = 0.035f,
            preset = preset,
            seeds = longArrayOf(11L, 29L),
        )
        val expectedRadius = 0.5f / viewportAspect
        assertTrue(state.maximumPhysicalRadius in expectedRadius * 0.995f..expectedRadius)
        val expectedLiveMass = count * preset.liveMassFraction
        assertTrue(abs(state.massPrefix.last() - expectedLiveMass) / count < 1e-5)
        assertEquals(count.toFloat(), state.normalizationMass)
        val bodiesPerGalaxy = count / 2
        assertTrue(state.centralBodyIds.contentEquals(intArrayOf(0, bodiesPerGalaxy)))
        assertTrue(state.galaxySeeds.contentEquals(longArrayOf(11L, 29L)))
        assertTrue(abs(expectedRadius - bodies[0]) < 1e-6f)
        assertEquals(0.5f, bodies[1])
        assertEquals(preset.verticalDriftSpeed, bodies[3])
        assertTrue(abs(1f - expectedRadius - bodies[bodiesPerGalaxy * 4]) < 1e-6f)
        assertEquals(0.5f, bodies[bodiesPerGalaxy * 4 + 1])
        assertEquals(-preset.verticalDriftSpeed, bodies[bodiesPerGalaxy * 4 + 3])
        assertTrue(masses[0] > 100f * masses[count - 1])

        var rotatingWithDisk = 0
        var moving = 0
        var mode2Real = 0.0
        var mode2Imaginary = 0.0
        for (id in 0 until count) {
            val localId = id % bodiesPerGalaxy
            if (localId == 0) continue
            val galaxy = id / bodiesPerGalaxy
            val centerX = if (galaxy == 0) expectedRadius else 1f - expectedRadius
            val offset = id * 4
            val x = (bodies[offset] - centerX) * domainAspect
            val y = bodies[offset + 1] - 0.5f
            val vx = bodies[offset + 2] * domainAspect
            val vy = bodies[offset + 3]
            val orbitalVy = vy -
                if (galaxy == 0) preset.verticalDriftSpeed else -preset.verticalDriftSpeed
            assertTrue(bodies[offset] in 0f..1f)
            assertTrue(bodies[offset + 1] in 0f..1f)
            assertTrue(hypot(x, y) <= expectedRadius + 1e-5f)
            if (x * orbitalVy - y * vx < 0f) rotatingWithDisk++
            if (hypot(vx, vy) > 1e-5f) moving++
            val angle = atan2(y, x)
            mode2Real += cos(2f * angle)
            mode2Imaginary += sin(2f * angle)
        }
        val orbitingBodies = count - 2
        assertTrue(rotatingWithDisk > orbitingBodies * 0.96)
        assertTrue(moving > orbitingBodies * 0.99)
        val mode2Amplitude = hypot(mode2Real, mode2Imaginary) / orbitingBodies
        assertTrue(mode2Amplitude < 0.02, "initial m=2 amplitude was $mode2Amplitude")
    }

    @Test
    fun `default twin seeds differ between disks and launches`() {
        fun generate(): Pair<LongArray, FloatArray> {
            val count = 100
            val bodies = FloatBuffer.allocate(count * 4)
            val masses = FloatBuffer.allocate(count)
            val state = GalaxyInitializer.fillTwin(
                bodies = bodies,
                masses = masses,
                count = count,
                domainAspect = 1f,
                gravity = 0.035f,
                preset = GalaxyPreset(),
            )
            return state.galaxySeeds to floatArrayOf(
                bodies[4],
                bodies[5],
                bodies[(count / 2 + 1) * 4],
                bodies[(count / 2 + 1) * 4 + 1],
            )
        }

        val first = generate()
        val second = generate()
        assertTrue(first.first[0] != first.first[1])
        assertTrue(second.first[0] != second.first[1])
        assertTrue(!first.first.contentEquals(second.first))
        assertTrue(!first.second.contentEquals(second.second))
    }

    @Test
    fun `same twin seeds reproduce exact initial state`() {
        val count = 200
        val seeds = longArrayOf(101L, 202L)

        fun generate(): Pair<FloatBuffer, FloatBuffer> {
            val bodies = FloatBuffer.allocate(count * 4)
            val masses = FloatBuffer.allocate(count)
            GalaxyInitializer.fillTwin(
                bodies = bodies,
                masses = masses,
                count = count,
                domainAspect = 1f,
                gravity = 0.035f,
                preset = GalaxyPreset(),
                seeds = seeds,
            )
            return bodies to masses
        }

        val first = generate()
        val second = generate()
        repeat(count * 4) { assertEquals(first.first[it], second.first[it]) }
        repeat(count) { assertEquals(first.second[it], second.second[it]) }
    }
}
