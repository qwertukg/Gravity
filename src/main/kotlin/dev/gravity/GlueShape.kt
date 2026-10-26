package dev.gravity

import java.util.stream.IntStream
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
 * centre minus a body radius, so a body touches the block where it touches a
 * member. Inside, the members are closed into one solid: discs of
 * [FILL_DIAMETERS] diameters around them cover the gaps of a packing, and the
 * value is minus the depth below that surface, so a body that got in is
 * pushed out of the nearest face instead of further between members. The
 * field is then smoothed, so faces are flat rather than rippled by members.
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
    /** Convex hull of the member centres, counter-clockwise (x, y) pairs, px. */
    val hull: FloatArray,
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
        /** Largest ripple of a face the smoothing removes, in contact diameters. */
        const val RIPPLE_DIAMETERS = 0.1f
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
            // Centres in bucket order, so a bucket's members lie side by side.
            val bucketedX = DoubleArray(offsets.size)
            val bucketedY = DoubleArray(offsets.size)
            offsets.forEach {
                val slot = filled[bucketX(it.x.toDouble()) + bucketY(it.y.toDouble()) * bucketWidth]++
                bucketedX[slot] = it.x.toDouble()
                bucketedY[slot] = it.y.toDouble()
            }

            // Distance from every node to the nearest centre, exact up to [reach].
            // Buckets are searched ring by ring out to [span]; a ring r buckets
            // out holds no centre nearer than r - 1 diameters, so the search
            // stops once the nearest found is nearer than that.
            val reach = margin + fill
            val span = ceil(reach / diameter).toInt()
            val nearest = DoubleArray(width * height) { reach }
            // Rows are independent: a large shape takes them on all cores.
            val rows = IntStream.range(0, height)
            (if (width.toLong() * height >= PARALLEL_NODES) rows.parallel() else rows).forEach { y ->
                val nodeY = minY + y * cell
                val by = bucketY(nodeY)
                for (x in 0 until width) {
                    val nodeX = minX + x * cell
                    val bx = bucketX(nodeX)
                    var best = reach * reach
                    for (ring in 0..span) {
                        val bound = (ring - 1) * diameter.toDouble()
                        if (ring > 1 && bound * bound >= best) break
                        for (cy in max(0, by - ring)..min(bucketHeight - 1, by + ring)) {
                            val edge = cy == by - ring || cy == by + ring
                            var cx = max(0, bx - ring)
                            while (cx <= min(bucketWidth - 1, bx + ring)) {
                                val bucket = cx + cy * bucketWidth
                                for (k in starts[bucket] until starts[bucket + 1]) {
                                    val dx = bucketedX[k] - nodeX
                                    val dy = bucketedY[k] - nodeY
                                    best = min(best, dx * dx + dy * dy)
                                }
                                // Inside the ring only its two ends are new.
                                cx = if (edge || cx == bx + ring) cx + 1 else max(cx + 1, bx + ring)
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
            return GlueShape(
                minX.toFloat(), minY.toFloat(), cell.toFloat(), width, height,
                smooth(values, width, height, diameter), hull(offsets),
            )
        }

        /** Andrew's monotone chain: the extreme members, which are what reaches a wall first. */
        internal fun hull(offsets: List<PixelOffset>): FloatArray =
            hull(FloatArray(offsets.size) { offsets[it].x }, FloatArray(offsets.size) { offsets[it].y })

        /** The hull of the centres (xs, ys). */
        internal fun hull(xs: FloatArray, ys: FloatArray): FloatArray =
            hullIndices(xs, ys).flatMap { listOf(xs[it], ys[it]) }.toFloatArray()

        /** Indices of the centres (xs, ys) on their hull, counter-clockwise. */
        internal fun hullIndices(xs: FloatArray, ys: FloatArray): IntArray {
            if (xs.isEmpty()) return IntArray(0)
            // Only the ends of a row of centres at one height can be corners:
            // rows a hundredth of a pixel apart are told apart.
            val minY = ys.min()
            val rows = ((ys.max() - minY) / ROW_PIXELS).toInt() + 1
            val lowest = IntArray(rows) { -1 }
            val highest = IntArray(rows) { -1 }
            for (index in xs.indices) {
                val row = ((ys[index] - minY) / ROW_PIXELS).toInt()
                if (lowest[row] < 0 || xs[index] < xs[lowest[row]]) lowest[row] = index
                if (highest[row] < 0 || xs[index] > xs[highest[row]]) highest[row] = index
            }
            val points = (0 until rows).filter { lowest[it] >= 0 }
                .flatMap { listOf(lowest[it], highest[it]) }
                .distinctBy { PixelOffset(xs[it], ys[it]) }
                .sortedWith(compareBy({ xs[it] }, { ys[it] }))
            if (points.size < 3) return points.toIntArray()
            fun turn(o: Int, a: Int, b: Int) =
                (xs[a] - xs[o]).toDouble() * (ys[b] - ys[o]) - (ys[a] - ys[o]).toDouble() * (xs[b] - xs[o])
            val chain = ArrayList<Int>()
            for (pass in 0..1) {
                val start = chain.size
                for (point in if (pass == 0) points else points.asReversed()) {
                    while (chain.size >= start + 2 && turn(chain[chain.size - 2], chain[chain.size - 1], point) <= 0.0) {
                        chain.removeAt(chain.size - 1)
                    }
                    chain += point
                }
                chain.removeAt(chain.size - 1)
            }
            return chain.toIntArray()
        }

        /** Nodes from which a shape's distances are found on all cores. */
        private const val PARALLEL_NODES = 1 shl 15

        /** Height of the rows hull() looks for the ends of, px. */
        private const val ROW_PIXELS = 0.01f

        /**
         * A face of members is a row of discs, rippled with their spacing: two
         * blocks would interlock like gear teeth and slide as over cobbles. A
         * binomial blur of about one cell (half a diameter) leaves a linear field
         * as it is, so flat faces stay where they are, and the ripple of one
         * diameter falls below a thousandth. A blur also rounds a convex corner
         * off, which would let bodies onto its member, so the blur never rises
         * more than [RIPPLE_DIAMETERS] above the field: that much covers the
         * ripple of a face, and a corner stays where its member is.
         */
        private fun smooth(values: FloatArray, width: Int, height: Int, diameter: Float): FloatArray {
            val kernel = floatArrayOf(1f, 6f, 15f, 20f, 15f, 6f, 1f).map { it / 64f }
            val reach = kernel.size / 2
            val across = FloatArray(values.size)
            for (y in 0 until height) for (x in 0 until width) {
                var sum = 0f
                for (k in kernel.indices) sum += kernel[k] * values[(x + k - reach).coerceIn(0, width - 1) + y * width]
                across[x + y * width] = sum
            }
            val result = FloatArray(values.size)
            for (y in 0 until height) for (x in 0 until width) {
                var sum = 0f
                for (k in kernel.indices) sum += kernel[k] * across[x + (y + k - reach).coerceIn(0, height - 1) * width]
                result[x + y * width] = min(sum, values[x + y * width] + RIPPLE_DIAMETERS * diameter)
            }
            return result
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
