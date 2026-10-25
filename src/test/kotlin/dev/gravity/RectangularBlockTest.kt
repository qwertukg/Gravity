package dev.gravity

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RectangularBlockTest {
    private val diameter = 1.12838f

    @Test
    fun `block bodies lie inside the rectangle without overlapping`() {
        val width = 40f
        val height = 25f
        val offsets = RectangularBlock.offsets(diameter, width, height)
        val radius = 0.5f * diameter

        assertTrue(offsets.all {
            it.x - radius >= -0.5f * width - 1e-3f && it.x + radius <= 0.5f * width + 1e-3f &&
                it.y - radius >= -0.5f * height - 1e-3f && it.y + radius <= 0.5f * height + 1e-3f
        })
        for (i in offsets.indices) {
            for (j in i + 1 until offsets.size) {
                val distance = hypot(offsets[i].x - offsets[j].x, offsets[i].y - offsets[j].y)
                assertTrue(distance >= diameter - 1e-3f)
            }
        }
    }

    @Test
    fun `block is hexagonally dense`() {
        val width = 100f
        val height = 60f
        val count = RectangularBlock.offsets(diameter, width, height).size
        val hexagonalCount = width * height / (diameter * diameter * 0.8660254f)

        assertTrue(count > 0.9f * hexagonalCount && count <= hexagonalCount)
    }

    @Test
    fun `body count matches offsets without allocating`() {
        for (width in listOf(0f, 1f, 1.2f, 1.7f, 2.3f, 10f, 37.5f, 200f)) {
            for (height in listOf(0f, 1f, 1.2f, 2f, 3.1f, 10f, 55f)) {
                assertEquals(
                    RectangularBlock.offsets(diameter, width, height).size,
                    RectangularBlock.bodyCount(diameter, width, height),
                    "width=$width height=$height",
                )
            }
        }
    }

    @Test
    fun `rectangle narrower than a body holds nothing`() {
        assertEquals(0, RectangularBlock.bodyCount(diameter, 0.5f, 100f))
        assertEquals(0, RectangularBlock.bodyCount(diameter, 100f, 0.5f))
        assertEquals(1, RectangularBlock.bodyCount(diameter, diameter, diameter))
    }
}
