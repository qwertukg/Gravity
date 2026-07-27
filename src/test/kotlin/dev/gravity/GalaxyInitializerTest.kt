package dev.gravity

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalaxyInitializerTest {
    @Test
    fun `galaxy keeps a periodic safety margin and rotates clockwise`() {
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
        }
        assertTrue(rotatingWithDisk > count * 0.96)
        assertTrue(moving > count * 0.99)
    }
}
