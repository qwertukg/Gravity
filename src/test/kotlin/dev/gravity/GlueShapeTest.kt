package dev.gravity

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlueShapeTest {
    private val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS
    private val offsets = RectangularBlock.offsets(diameter, 40f, 20f)
    private val shape = GlueShape.of(offsets, diameter)

    private fun nearest(x: Float, y: Float) = offsets.minOf { hypot(it.x - x, it.y - y) }

    /** Bilinear sample, as collision_rigid.glsl does. */
    private fun sample(x: Float, y: Float): Float {
        val u = (x - shape.originX) / shape.cellPixels
        val v = (y - shape.originY) / shape.cellPixels
        val cx = floor(u).toInt()
        val cy = floor(v).toInt()
        val fx = u - cx
        val fy = v - cy
        fun at(i: Int, j: Int) = shape.values[i + j * shape.width]
        val low = at(cx, cy) + (at(cx + 1, cy) - at(cx, cy)) * fx
        val high = at(cx, cy + 1) + (at(cx + 1, cy + 1) - at(cx, cy + 1)) * fx
        return low + (high - low) * fy
    }

    @Test
    fun `outside a body touches the shape exactly when it touches a member`() {
        for (y in 0 until shape.height) for (x in 0 until shape.width) {
            val px = shape.originX + x * shape.cellPixels
            val py = shape.originY + y * shape.cellPixels
            val distance = nearest(px, py)
            if (distance >= GlueShape.FILL_DIAMETERS * diameter && distance < 2f * diameter) {
                assertEquals(distance - 0.5f * diameter, shape.values[x + y * shape.width], 1e-4f)
            }
        }
    }

    @Test
    fun `the gaps of the packing and the whole interior are inside`() {
        val minX = offsets.minOf { it.x }
        val maxX = offsets.maxOf { it.x }
        val minY = offsets.minOf { it.y }
        val maxY = offsets.maxOf { it.y }
        var deepest = 0f
        var x = minX
        while (x <= maxX) {
            var y = minY
            while (y <= maxY) {
                val value = sample(x, y)
                assertTrue(value < 0.5f * diameter, "($x, $y) is inside: $value")
                // Never deeper than the distance to the nearest face.
                val face = min(min(x - minX, maxX - x), min(y - minY, maxY - y)) + 0.5f * diameter
                assertTrue(-value <= face + 0.1f * diameter, "($x, $y): depth ${-value} > face $face")
                deepest = maxOf(deepest, -value)
                y += 0.137f
            }
            x += 0.137f
        }
        // The middle lies about half the height below the surface.
        val halfHeight = 0.5f * (maxY - minY)
        assertTrue(deepest > halfHeight - diameter, "deepest $deepest px, half height $halfHeight px")
        // A triangle gap between three members is inside, not a hole.
        val gapX = offsets[0].x + 0.5f * diameter
        val gapY = offsets[0].y + diameter / (2f * sqrt(3f))
        assertTrue(sample(gapX, gapY) < 0f, "gap value ${sample(gapX, gapY)}")
    }

    @Test
    fun `the border bounds the field beyond the grid`() {
        assertTrue(shape.borderMinimum > 1.4f * diameter, "border ${shape.borderMinimum}")
    }
}
