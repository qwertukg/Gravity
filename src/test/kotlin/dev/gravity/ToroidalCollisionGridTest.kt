package dev.gravity

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToroidalCollisionGridTest {
    @Test
    fun `all contacts across seam remain in adjacent uniform cells`() {
        val diameter = 1.12838f
        val random = Random(741)

        for (extent in intArrayOf(3440, 1440)) {
            val cells = ToroidalSpace.collisionGridCells(extent, diameter)
            val cellSize = extent.toDouble() / cells
            assertTrue(cellSize >= diameter)

            repeat(20_000) {
                val first = random.nextDouble(extent.toDouble())
                val separation = random.nextDouble(
                    -diameter.toDouble(),
                    diameter.toDouble(),
                )
                val second = ToroidalSpace.wrapUnit(
                    first / extent + separation / extent,
                ) * extent
                val firstCell = cell(first, extent, cells)
                val secondCell = cell(second, extent, cells)
                val directCellDistance = abs(firstCell - secondCell)
                val periodicCellDistance = min(
                    directCellDistance,
                    cells - directCellDistance,
                )
                assertTrue(
                    periodicCellDistance <= 1,
                    "contact spans $periodicCellDistance cells on extent $extent",
                )
            }
        }
    }

    @Test
    fun `fast translation through seam preserves projectile shape`() {
        val width = 3440.0
        val height = 1440.0
        val centerX = 3438.0
        val centerY = 2.0
        val translatedCenterX = ToroidalSpace.wrapUnit((centerX + 11.0) / width)
        val translatedCenterY = ToroidalSpace.wrapUnit((centerY - 8.0) / height)

        CircularProjectile.offsets(1.12838f, 50f).forEach { offset ->
            val translatedX = ToroidalSpace.wrapUnit(
                (centerX + offset.x + 11.0) / width,
            )
            val translatedY = ToroidalSpace.wrapUnit(
                (centerY + offset.y - 8.0) / height,
            )
            val relativeX = ToroidalSpace.minimumImage(
                translatedX - translatedCenterX,
            ) * width
            val relativeY = ToroidalSpace.minimumImage(
                translatedY - translatedCenterY,
            ) * height
            assertEquals(offset.x.toDouble(), relativeX, 1e-9)
            assertEquals(offset.y.toDouble(), relativeY, 1e-9)
        }
    }

    private fun cell(position: Double, extent: Int, cells: Int): Int =
        floor(position / extent * cells).toInt().coerceIn(0, cells - 1)
}
