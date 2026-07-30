package dev.gravity

import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuCollisionReferenceTest {
    private data class Body(val x: Double, val y: Double)
    private data class PairId(val first: Int, val second: Int)

    @Test
    fun `grid detector has no missing pairs with more than 64 bodies in one cell`() {
        val random = Random(20260730)
        val bodies = List(130) {
            Body(
                0.5 + random.nextDouble(-0.00005, 0.00005),
                0.5 + random.nextDouble(-0.00005, 0.00005),
            )
        }
        assertGridMatchesReference(bodies, 3440, 1440, 1.12838)
        assertEquals(130 * 129 / 2, bruteForce(bodies, 3440, 1440, 1.12838).size)
    }

    @Test
    fun `coincident bodies are finite and every pair is retained`() {
        val bodies = List(12) { Body(0.25, 0.75) }
        val pairs = bruteForce(bodies, 800, 600, 1.12838)
        assertEquals(66, pairs.size)
        pairs.forEach {
            val normal = fallbackNormal(it.first, it.second)
            assertTrue(normal.first.isFinite() && normal.second.isFinite())
            assertEquals(1.0, normal.first * normal.first + normal.second * normal.second, 1e-12)
            val reverse = fallbackNormal(it.second, it.first)
            assertEquals(-normal.first, reverse.first, 1e-12)
            assertEquals(-normal.second, reverse.second, 1e-12)
        }
        assertGridMatchesReference(bodies, 800, 600, 1.12838)
    }

    @Test
    fun `contacts across opposite torus edges and empty cells are exhaustive`() {
        val bodies = listOf(
            Body(0.0001, 0.5),
            Body(0.9999, 0.5),
            Body(0.5, 0.0001),
            Body(0.5, 0.9999),
            Body(0.25, 0.25),
        )
        val expected = bruteForce(bodies, 1000, 1000, 1.12838)
        assertTrue(PairId(0, 1) in expected)
        assertTrue(PairId(2, 3) in expected)
        assertFalse(PairId(0, 4) in expected)
        assertGridMatchesReference(bodies, 1000, 1000, 1.12838)
        assertGridMatchesReference(listOf(Body(0.5, 0.5)), 1000, 1000, 1.12838)
    }

    private fun assertGridMatchesReference(
        bodies: List<Body>,
        width: Int,
        height: Int,
        diameter: Double,
    ) {
        assertEquals(
            bruteForce(bodies, width, height, diameter),
            gridPairs(bodies, width, height, diameter),
        )
    }

    private fun bruteForce(
        bodies: List<Body>,
        width: Int,
        height: Int,
        diameter: Double,
    ): Set<PairId> = buildSet {
        for (first in bodies.indices) {
            for (second in first + 1 until bodies.size) {
                if (touching(bodies[first], bodies[second], width, height, diameter)) {
                    add(PairId(first, second))
                }
            }
        }
    }

    private fun gridPairs(
        bodies: List<Body>,
        width: Int,
        height: Int,
        diameter: Double,
    ): Set<PairId> {
        val gridWidth = ToroidalSpace.collisionGridCells(width, diameter.toFloat())
        val gridHeight = ToroidalSpace.collisionGridCells(height, diameter.toFloat())
        val cells = Array(gridWidth * gridHeight) { ArrayList<Int>() }
        bodies.forEachIndexed { id, body ->
            val x = floor(body.x * gridWidth).toInt().mod(gridWidth)
            val y = floor(body.y * gridHeight).toInt().mod(gridHeight)
            cells[x + y * gridWidth] += id
        }
        return buildSet {
            bodies.forEachIndexed { first, body ->
                val homeX = floor(body.x * gridWidth).toInt().mod(gridWidth)
                val homeY = floor(body.y * gridHeight).toInt().mod(gridHeight)
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val x = (homeX + dx).mod(gridWidth)
                        val y = (homeY + dy).mod(gridHeight)
                        cells[x + y * gridWidth].forEach { second ->
                            if (second > first &&
                                touching(body, bodies[second], width, height, diameter)) {
                                add(PairId(first, second))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun touching(
        first: Body,
        second: Body,
        width: Int,
        height: Int,
        diameter: Double,
    ): Boolean {
        val dx = ToroidalSpace.minimumImage(first.x - second.x) * width
        val dy = ToroidalSpace.minimumImage(first.y - second.y) * height
        return dx * dx + dy * dy < diameter * diameter
    }

    private fun fallbackNormal(first: Int, second: Int): Pair<Double, Double> {
        val low = minOf(first, second).toUInt()
        val high = maxOf(first, second).toUInt()
        val hash = low * 1_664_525u + high * 1_013_904_223u
        val angle = (hash and 65_535u).toDouble() * (2.0 * Math.PI / 65_536.0)
        val x = kotlin.math.cos(angle)
        val y = kotlin.math.sin(angle)
        return if (first < second) x to y else -x to -y
    }
}
