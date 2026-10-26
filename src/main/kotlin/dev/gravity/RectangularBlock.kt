package dev.gravity

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A rectangle filled with hexagonally packed bodies, like [CircularProjectile].
 * Offsets are relative to the rectangle centre; every body lies fully inside.
 */
object RectangularBlock {
    fun offsets(
        contactDiameterPixels: Float,
        widthPixels: Float,
        heightPixels: Float,
    ): List<PixelOffset> {
        val offsets = ArrayList<PixelOffset>(bodyCount(contactDiameterPixels, widthPixels, heightPixels))
        forEachOffset(contactDiameterPixels, widthPixels, heightPixels) { x, y -> offsets += PixelOffset(x, y) }
        return offsets
    }

    /** Visits the offsets row by row, each row from left to right, without allocating them. */
    inline fun forEachOffset(
        contactDiameterPixels: Float,
        widthPixels: Float,
        heightPixels: Float,
        action: (x: Float, y: Float) -> Unit,
    ) {
        val lattice = Lattice(contactDiameterPixels, widthPixels, heightPixels)
        for (row in 0 until lattice.rows) {
            val odd = row and 1 == 1
            val columns = if (odd) lattice.oddColumns else lattice.evenColumns
            val startX = -lattice.halfX + if (odd) 0.5f * contactDiameterPixels else 0f
            val y = -lattice.halfY + row * lattice.rowStep
            for (column in 0 until columns) action(startX + column * contactDiameterPixels, y)
        }
    }

    /** Same as `offsets(...).size`, without allocating; cheap enough per frame. */
    fun bodyCount(
        contactDiameterPixels: Float,
        widthPixels: Float,
        heightPixels: Float,
    ): Int = Lattice(contactDiameterPixels, widthPixels, heightPixels).bodyCount

    @PublishedApi
    internal class Lattice(diameter: Float, width: Float, height: Float) {
        /** Half extents available to body centres. */
        val halfX = 0.5f * (width - diameter)
        val halfY = 0.5f * (height - diameter)
        val rowStep = diameter * (sqrt(3.0) * 0.5).toFloat()
        val rows: Int
        val evenColumns: Int
        val oddColumns: Int
        val bodyCount: Int

        init {
            require(diameter > 0f)
            if (halfX < 0f || halfY < 0f) {
                rows = 0
                evenColumns = 0
                oddColumns = 0
            } else {
                rows = floor(2f * halfY / rowStep + EPSILON).toInt() + 1
                evenColumns = floor(2f * halfX / diameter + EPSILON).toInt() + 1
                val oddSpan = 2f * halfX - 0.5f * diameter
                oddColumns = if (oddSpan < -EPSILON * diameter) {
                    0
                } else {
                    floor(oddSpan / diameter + EPSILON).toInt() + 1
                }
            }
            bodyCount = (rows + 1) / 2 * evenColumns + rows / 2 * oddColumns
        }
    }

    private const val EPSILON = 1e-4f
}
