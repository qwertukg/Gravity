package dev.gravity

import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    fun `galaxy diameter matches viewport height and rotates clockwise`() {
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

        val state = GalaxyInitializer.fill(
            bodies,
            masses,
            count,
            domainAspect,
            gravity = 0.035f,
            preset = preset,
        )
        val expectedRadius = 0.5f / viewportAspect
        assertTrue(state.maximumPhysicalRadius in expectedRadius * 0.995f..expectedRadius)
        val expectedLiveMass = count * preset.liveMassFraction
        assertTrue(abs(state.massPrefix.last() - expectedLiveMass) / count < 1e-5)
        assertEquals(count.toFloat(), state.normalizationMass)
        assertEquals(0.5f, bodies[0])
        assertEquals(0.5f, bodies[1])
        assertTrue(masses[0] > 100f * masses[count - 1])

        var rotatingWithDisk = 0
        var moving = 0
        var mode2Real = 0.0
        var mode2Imaginary = 0.0
        for (id in 1 until count) {
            val offset = id * 4
            val x = (bodies[offset] - 0.5f) * domainAspect
            val y = bodies[offset + 1] - 0.5f
            val vx = bodies[offset + 2] * domainAspect
            val vy = bodies[offset + 3]
            assertTrue(bodies[offset] in 0f..1f)
            assertTrue(bodies[offset + 1] in 0f..1f)
            assertTrue(hypot(x, y) <= expectedRadius + 1e-5f)
            if (x * vy - y * vx < 0f) rotatingWithDisk++
            if (hypot(vx, vy) > 1e-5f) moving++
            val angle = atan2(y, x)
            mode2Real += cos(2f * angle)
            mode2Imaginary += sin(2f * angle)
        }
        assertTrue(rotatingWithDisk > count * 0.96)
        assertTrue(moving > count * 0.99)
        val mode2Amplitude = hypot(mode2Real, mode2Imaginary) / (count - 1)
        assertTrue(mode2Amplitude < 0.02, "initial m=2 amplitude was $mode2Amplitude")
    }
}
