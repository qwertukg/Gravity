package dev.gravity

import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class CollisionGridOptimizationTest {
    private data class Point(val x: Double, val y: Double)

    @Test
    fun `clearing cells occupied by the previous build empties the sparse grid`() {
        val width = 64
        val height = 32
        val heads = IntArray(width * height)
        val random = Random(91)
        var previous = List(400) {
            Point(random.nextDouble(width.toDouble()), random.nextDouble(height.toDouble()))
        }

        repeat(40) { iteration ->
            previous.forEach { heads[cell(it, width, height)] = 0 }
            assertTrue(heads.all { it == 0 }, "stale head at iteration $iteration")

            val moved = previous.map {
                Point(
                    (it.x + random.nextDouble(-1.5, 1.5))
                        .coerceIn(0.0, width - 1e-6),
                    (it.y + random.nextDouble(-1.5, 1.5))
                        .coerceIn(0.0, height - 1e-6),
                )
            }.toMutableList()
            if (iteration % 5 == 0) {
                repeat(20) {
                    moved += Point(
                        random.nextDouble(width.toDouble()),
                        random.nextDouble(height.toDouble()),
                    )
                }
            }
            moved.forEachIndexed { id, point ->
                heads[cell(point, width, height)] = id + 1
            }
            previous = moved
        }
    }

    private fun cell(point: Point, width: Int, height: Int): Int {
        val x = floor(point.x).toInt().coerceIn(0, width - 1)
        val y = floor(point.y).toInt().coerceIn(0, height - 1)
        return x + y * width
    }
}
