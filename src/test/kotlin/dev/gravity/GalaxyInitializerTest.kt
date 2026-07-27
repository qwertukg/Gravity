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
    fun `galaxy keeps a screen-edge safety margin and rotates clockwise`() {
        val count = 20_000
        val aspect = 3440f / 1440f
        val bodies = ByteBuffer.allocateDirect(count * 4 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val masses = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val state = GalaxyInitializer.fill(bodies, masses, count, aspect, gravity = 0.035f)
        assertTrue(state.maximumPhysicalRadius in 0.378f..0.38f)
        assertTrue(abs(state.massPrefix.last() - count) / count < 1e-5)
        assertEquals(0.5f, bodies[0])
        assertEquals(0.5f, bodies[1])
        assertTrue(masses[0] > 100f * masses[count - 1])

        var rotatingWithDisk = 0
        var moving = 0
        var mode2Real = 0.0
        var mode2Imaginary = 0.0
        for (id in 1 until count) {
            val offset = id * 4
            val x = (bodies[offset] - 0.5f) * aspect
            val y = bodies[offset + 1] - 0.5f
            val vx = bodies[offset + 2] * aspect
            val vy = bodies[offset + 3]
            assertTrue(bodies[offset] in 0f..1f)
            assertTrue(bodies[offset + 1] in 0f..1f)
            assertTrue(hypot(x, y) <= 0.38001f)
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
