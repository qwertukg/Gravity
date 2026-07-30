package dev.gravity

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircularProjectileTest {
    @Test
    fun `projectile is a dense fifty pixel disk`() {
        val diameter = 1.12838f
        val projectile = ProjectileConfig()
        val offsets = CircularProjectile.offsets(
            diameter,
            projectile.defaultDiameterPixels,
        )
        val maximumCenterRadius = 0.5 * projectile.defaultDiameterPixels -
            0.5 * diameter

        assertTrue(offsets.size in 1_500..1_900)
        assertTrue(offsets.all {
            hypot(it.x.toDouble(), it.y.toDouble()) <= maximumCenterRadius + 1e-4
        })
    }

    @Test
    fun `digit keys select projectile diameter from ten to one hundred pixels`() {
        val projectile = ProjectileConfig()
        assertEquals(100f, projectile.diameterForDigit(0))
        for (digit in 1..9) {
            assertEquals(digit * 10f, projectile.diameterForDigit(digit))
        }
    }

    @Test
    fun `digit mapping is supplied by projectile configuration`() {
        val projectile = ProjectileConfig(
            digitDiameterStepPixels = 12f,
            maximumDiameterPixels = 120f,
        )
        assertEquals(12f, projectile.diameterForDigit(1))
        assertEquals(108f, projectile.diameterForDigit(9))
        assertEquals(120f, projectile.diameterForDigit(0))
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
