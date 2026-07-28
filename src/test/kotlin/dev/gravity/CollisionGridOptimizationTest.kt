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
                        .let { value -> value - floor(value / width) * width },
                    (it.y + random.nextDouble(-1.5, 1.5))
                        .let { value -> value - floor(value / height) * height },
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
        val x = Math.floorMod(floor(point.x).toInt(), width)
        val y = Math.floorMod(floor(point.y).toInt(), height)
        return x + y * width
    }
}
