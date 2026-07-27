package dev.gravity

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ViewportMappingTest {
    @Test
    fun `cursor maps into the visible center strip of square space`() {
        val width = 3440
        val height = 1440

        assertClose(0f, ViewportMapping.domainX(0.0, width))
        assertClose(0.5f, ViewportMapping.domainX(width * 0.5, width))
        assertClose(1f, ViewportMapping.domainX(width.toDouble(), width))
        assertClose(0.5f, ViewportMapping.domainY(height * 0.5, width, height))
        assertClose(
            0.5f + height.toFloat() / (2f * width),
            ViewportMapping.domainY(0.0, width, height),
        )
        assertClose(
            0.5f - height.toFloat() / (2f * width),
            ViewportMapping.domainY(height.toDouble(), width, height),
        )
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 1e-6f, "expected $expected, got $actual")
    }
}
