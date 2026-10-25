package dev.gravity

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiquidKernelTest {
    @Test
    fun `rest density is the number density of the hexagonal packing`() {
        for (diameter in listOf(1.12838f, 3.38514f)) {
            for (radius in listOf(2f, 3f)) {
                val kernel = LiquidKernel(diameter, radius)
                // One body per hexagonal cell of area d^2 sqrt(3) / 2.
                val packing = 1.0 / (diameter * diameter * sqrt(3.0) * 0.5)
                assertEquals(packing, kernel.restDensity.toDouble(), 0.05 * packing, "d=$diameter h=$radius d")
            }
        }
    }

    @Test
    fun `kernels vanish at the radius and relaxation scales with body size`() {
        val small = LiquidKernel(1.12838f, 2f)
        val large = LiquidKernel(3.38514f, 2f)
        val h = small.radiusPixels.toDouble()
        assertEquals(0.0, small.poly6(h * h))
        assertEquals(0.0, small.spikyGradient(h))
        assertTrue(small.spikyGradient(0.5 * h) > 0.0)
        // Gradients of a number density scale as 1/d^3, the density as 1/d^2.
        val ratio = small.relaxation / large.relaxation
        assertEquals(9.0, ratio.toDouble(), 0.05)
    }
}
