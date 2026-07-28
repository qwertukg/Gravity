package dev.gravity

import kotlin.math.floor

/**
 * Coordinate operations shared by CPU-side code and the periodic GPU shaders.
 */
internal object ToroidalSpace {
    fun wrapUnit(value: Double): Double = value - floor(value)

    fun wrapPixels(value: Float, extent: Int): Float {
        val size = extent.toFloat()
        return value - floor(value / size) * size
    }

    /**
     * Splits a periodic axis into equal cells whose size is never smaller than
     * the interaction diameter. This keeps a 3-cell neighborhood exhaustive
     * across the seam as well as inside the domain.
     */
    fun collisionGridCells(extent: Int, minimumCellSize: Float): Int =
        floor(extent / minimumCellSize).toInt().coerceAtLeast(1)

    /**
     * Returns the shortest signed displacement on a unit-period axis.
     * The result is in [-0.5, 0.5).
     */
    fun minimumImage(delta: Double): Double = delta - floor(delta + 0.5)
}
