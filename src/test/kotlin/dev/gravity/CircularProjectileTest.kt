package dev.gravity

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

class CircularProjectileTest {
    @Test
    fun `projectile is a dense one hundred pixel disk`() {
        val diameter = 1.12838f
        val offsets = CircularProjectile.offsets(diameter)
        val maximumCenterRadius = 0.5 * CircularProjectile.DIAMETER_PIXELS -
            0.5 * diameter

        assertTrue(offsets.size in 6_500..7_500)
        assertTrue(offsets.all {
            hypot(it.x.toDouble(), it.y.toDouble()) <= maximumCenterRadius + 1e-4
        })
    }
}
