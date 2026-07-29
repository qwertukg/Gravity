package dev.gravity

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircularProjectileTest {
    @Test
    fun `projectile is a dense fifty pixel disk`() {
        val diameter = 1.12838f
        val offsets = CircularProjectile.offsets(diameter)
        val maximumCenterRadius = 0.5 * CircularProjectile.DEFAULT_DIAMETER_PIXELS -
            0.5 * diameter

        assertTrue(offsets.size in 1_500..1_900)
        assertTrue(offsets.all {
            hypot(it.x.toDouble(), it.y.toDouble()) <= maximumCenterRadius + 1e-4
        })
    }

    @Test
    fun `digit keys select projectile diameter from ten to one hundred pixels`() {
        assertEquals(100f, CircularProjectile.diameterForDigit(0))
        for (digit in 1..9) {
            assertEquals(digit * 10f, CircularProjectile.diameterForDigit(digit))
        }
    }

    @Test
    fun `selected projectile diameters create progressively larger disks`() {
        val bodyDiameter = 1.12838f
        var previousBodies = 0
        for (projectileDiameter in 10..100 step 10) {
            val offsets = CircularProjectile.offsets(
                contactDiameterPixels = bodyDiameter,
                projectileDiameterPixels = projectileDiameter.toFloat(),
            )
            val maximumCenterRadius = 0.5 * projectileDiameter - 0.5 * bodyDiameter

            assertTrue(offsets.size > previousBodies)
            assertTrue(offsets.all {
                hypot(it.x.toDouble(), it.y.toDouble()) <= maximumCenterRadius + 1e-4
            })
            previousBodies = offsets.size
        }
    }
}
