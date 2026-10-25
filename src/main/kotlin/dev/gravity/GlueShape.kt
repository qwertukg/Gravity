package dev.gravity

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The solid outline of a glued block, whatever its members: a signed distance
 * field on a grid in the block's own frame, sampled bilinearly by the
 * collision shaders (collision_rigid.glsl).
 *
 * Outside the block the value is the distance from the nearest member's
 * centre minus a body radius, so a body touches the block exactly when it
 * touches a member. Inside, the members are closed into one solid: discs of
 * [FILL_DIAMETERS] diameters around them cover the gaps of a packing, and the
 * value is minus the depth below that surface, so a body that got in is
 * pushed out of the nearest face instead of further between members.
 */
class GlueShape private constructor(
    /** Rest-frame position of the first grid node, px. */
    val originX: Float,
    val originY: Float,
    val cellPixels: Float,
    val width: Int,
    val height: Int,
    /** Row-major node values, px. */
    val values: FloatArray,
) {
    /** Smallest value on the grid border: beyond it, the distance is at least this. */
    val borderMinimum: Float = run {
        var minimum = Float.MAX_VALUE
        for (x in 0 until width) {
            minimum = min(minimum, values[x])
            minimum = min(minimum, values[x + (height - 1) * width])
        }
        for (y in 0 until height) {
            minimum = min(minimum, values[y * width])
            minimum = min(minimum, values[width - 1 + y * width])
        }
        minimum
    }

    companion object {
        /** Grid spacing in contact diameters. */
        const val CELL_DIAMETERS = 0.5f
        /** Diameter around each member that closes a hexagonal packing (> 1/sqrt 3). */
        const val FILL_DIAMETERS = 0.6f
        /** Grid reach beyond the outermost member centres, in contact diameters. */
        const val MARGIN_DIAMETERS = 2f

        fun of(offsets: List<PixelOffset>, diameter: Float): GlueShape {
            require(offsets.isNotEmpty())
            val radius = 0.5 * diameter
            val fill = FILL_DIAMETERS.toDouble() * diameter
            val cell = (CELL_DIAMETERS * diameter).toDouble()
            val margin = MARGIN_DIAMETERS.toDouble() * diameter
            val minX = offsets.minOf { it.x }.toDouble() - margin
            val minY = offsets.minOf { it.y }.toDouble() - margin
            val maxX = offsets.maxOf { it.x }.toDouble() + margin
            val maxY = offsets.maxOf { it.y }.toDouble() + margin
            val width = ceil((maxX - minX) / cell).toInt() + 1
            val height = ceil((maxY - minY) / cell).toInt() + 1

            // Members bucketed by contact diameter for the nearest-centre search.
            val bucketWidth = floor((maxX - minX) / diameter).toInt() + 1
            val bucketHeight = floor((maxY - minY) / diameter).toInt() + 1
            fun bucketX(x: Double) = floor((x - minX) / diameter).toInt().coerceIn(0, bucketWidth - 1)
            fun bucketY(y: Double) = floor((y - minY) / diameter).toInt().coerceIn(0, bucketHeight - 1)
            val starts = IntArray(bucketWidth * bucketHeight + 1)
            offsets.forEach { starts[bucketX(it.x.toDouble()) + bucketY(it.y.toDouble()) * bucketWidth + 1]++ }
            for (bucket in 1 until starts.size) starts[bucket] += starts[bucket - 1]
            val filled = starts.copyOf()
            val bucketed = IntArray(offsets.size)
            offsets.forEachIndexed { index, it ->
                bucketed[filled[bucketX(it.x.toDouble()) + bucketY(it.y.toDouble()) * bucketWidth]++] = index
            }

            // Distance from every node to the nearest centre, exact up to [reach].
            val reach = margin + fill
            val span = ceil(reach / diameter).toInt()
            val nearest = DoubleArray(width * height) { reach }
            for (y in 0 until height) {
                val nodeY = minY + y * cell
                val by = bucketY(nodeY)
                for (x in 0 until width) {
                    val nodeX = minX + x * cell
                    val bx = bucketX(nodeX)
                    var best = reach * reach
                    for (cy in max(0, by - span)..min(bucketHeight - 1, by + span)) {
                        for (cx in max(0, bx - span)..min(bucketWidth - 1, bx + span)) {
                            val bucket = cx + cy * bucketWidth
                            for (k in starts[bucket] until starts[bucket + 1]) {
                                val dx = offsets[bucketed[k]].x - nodeX
                                val dy = offsets[bucketed[k]].y - nodeY
                                best = min(best, dx * dx + dy * dy)
                            }
                        }
                    }
                    nearest[x + y * width] = sqrt(best)
                }
            }

            // Depth of inside nodes: distance to the nearest outside node, less
            // the most that node can lie beyond the surface, never more than
            // the true depth. The nearest disc alone gives another lower bound.
            val inside = BooleanArray(width * height) { nearest[it] < fill }
            val squared = squaredDistanceToOutside(inside, width, height)
            val values = FloatArray(width * height) { node ->
                val outline = if (inside[node]) {
                    val depth = max(fill - nearest[node], sqrt(squared[node]) * cell - cell * sqrt(0.5))
                    -max(depth, 0.0)
                } else {
                    nearest[node] - fill
                }
                // Shift the filled outline back to the touching distance.
                (outline + fill - radius).toFloat()
            }
            return GlueShape(minX.toFloat(), minY.toFloat(), cell.toFloat(), width, height, values)
        }

        /** Squared distance in nodes from each node to the nearest outside node (Felzenszwalb). */
        private fun squaredDistanceToOutside(inside: BooleanArray, width: Int, height: Int): DoubleArray {
            val far = (width + height).toDouble().let { it * it }
            val grid = DoubleArray(width * height) { if (inside[it]) far else 0.0 }
            val line = DoubleArray(max(width, height))
            val result = DoubleArray(max(width, height))
            for (x in 0 until width) {
                for (y in 0 until height) line[y] = grid[x + y * width]
                transform(line, height, result)
                for (y in 0 until height) grid[x + y * width] = result[y]
            }
            for (y in 0 until height) {
                for (x in 0 until width) line[x] = grid[x + y * width]
                transform(line, width, result)
                for (x in 0 until width) grid[x + y * width] = result[x]
            }
            return grid
        }

        /** One-dimensional squared distance transform of the first [length] values. */
        private fun transform(values: DoubleArray, length: Int, result: DoubleArray) {
            val hull = IntArray(length)
            val bounds = DoubleArray(length + 1)
            fun intersection(q: Int, p: Int) =
                ((values[q] + q.toDouble() * q) - (values[p] + p.toDouble() * p)) / (2.0 * (q - p))
            var k = 0
            hull[0] = 0
            bounds[0] = Double.NEGATIVE_INFINITY
            bounds[1] = Double.POSITIVE_INFINITY
            for (q in 1 until length) {
                var s = intersection(q, hull[k])
                while (s <= bounds[k]) {
                    k--
                    s = intersection(q, hull[k])
                }
                k++
                hull[k] = q
                bounds[k] = s
                bounds[k + 1] = Double.POSITIVE_INFINITY
            }
            k = 0
            for (q in 0 until length) {
                while (bounds[k + 1] < q) k++
                val p = hull[k]
                result[q] = (q - p).toDouble() * (q - p) + values[p]
            }
        }
    }
}
