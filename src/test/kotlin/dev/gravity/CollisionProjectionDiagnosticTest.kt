package dev.gravity

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollisionProjectionDiagnosticTest {
    private data class Body(
        var x: Double,
        var y: Double,
        var vx: Double = 0.0,
        var vy: Double = 0.0,
    )

    private data class StepStats(
        val contacts: Int,
        val maxRawCorrection: Double,
        val maxAppliedCorrection: Double,
    )

    private data class Contact(
        val first: Int,
        val second: Int,
        val nx: Double,
        val ny: Double,
        val distance: Double,
    )

    @Test
    fun `perfectly inelastic pair loses closing normal velocity`() {
        val bodies = mutableListOf(
            Body(x = -0.4, y = 0.0, vx = 1.0),
            Body(x = 0.4, y = 0.0, vx = -1.0),
        )

        val stats = project(bodies)
        log("pair", 0, bodies, stats)

        assertEquals(0.0, bodies[0].vx, absoluteTolerance = 1e-12)
        assertEquals(0.0, bodies[1].vx, absoluteTolerance = 1e-12)
        assertEquals(0.0, bodies[0].vy, absoluteTolerance = 1e-12)
        assertEquals(0.0, bodies[1].vy, absoluteTolerance = 1e-12)
        assertEquals(1, stats.contacts)
        assertTrue(stats.maxAppliedCorrection <= MAX_CORRECTION + 1e-12)
    }

    @Test
    fun `asymmetric compression cannot teleport a central body`() {
        val bodies = mutableListOf(
            Body(0.0, 0.0),
            Body(-0.10, 0.00),
            Body(-0.20, 0.05),
            Body(-0.20, -0.05),
            Body(-0.30, 0.00),
        )
        val initialCentroid = centroid(bodies)

        repeat(12) { step ->
            val before = bodies.map { it.x to it.y }
            val stats = project(bodies)
            log("asymmetric", step, bodies, stats)
            bodies.forEachIndexed { index, body ->
                val displacement = hypot(
                    body.x - before[index].first,
                    body.y - before[index].second,
                )
                assertTrue(displacement <= MAX_CORRECTION + 1e-12)
            }
            assertTrue(stats.maxAppliedCorrection <= MAX_CORRECTION + 1e-12)
            val currentCentroid = centroid(bodies)
            assertEquals(initialCentroid.first, currentCentroid.first, absoluteTolerance = 2e-6)
            assertEquals(initialCentroid.second, currentCentroid.second, absoluteTolerance = 2e-6)
        }
    }

    @Test
    fun `symmetric seven body compression keeps its centroid`() {
        val bodies = mutableListOf(Body(0.0, 0.0))
        repeat(6) { index ->
            val angle = 2.0 * PI * index / 6.0
            bodies += Body(0.72 * cos(angle), 0.72 * sin(angle))
        }
        val initialCentroid = centroid(bodies)

        repeat(16) { step ->
            val stats = project(bodies)
            log("hexagon", step, bodies, stats)
            val currentCentroid = centroid(bodies)
            assertEquals(initialCentroid.first, currentCentroid.first, absoluteTolerance = 2e-6)
            assertEquals(initialCentroid.second, currentCentroid.second, absoluteTolerance = 2e-6)
            assertTrue(stats.maxAppliedCorrection <= MAX_CORRECTION + 1e-12)
        }
    }

    private fun project(bodies: MutableList<Body>): StepStats {
        val count = bodies.size
        val contacts = IntArray(count)
        val velocityX = DoubleArray(count)
        val velocityY = DoubleArray(count)
        val correctionX = DoubleArray(count)
        val correctionY = DoubleArray(count)
        val pairs = mutableListOf<Contact>()

        for (first in 0 until count) {
            for (second in first + 1 until count) {
                val dx = bodies[second].x - bodies[first].x
                val dy = bodies[second].y - bodies[first].y
                val distance = hypot(dx, dy)
                if (distance >= DIAMETER) continue

                contacts[first]++
                contacts[second]++
                val nx = if (distance > 1e-12) dx / distance else 1.0
                val ny = if (distance > 1e-12) dy / distance else 0.0
                pairs += Contact(first, second, nx, ny, distance)
            }
        }

        pairs.forEach { contact ->
            val first = contact.first
            val second = contact.second
            val weight = 1.0 / maxOf(contacts[first], contacts[second])
            val relativeNormal =
                (bodies[second].vx - bodies[first].vx) * contact.nx +
                    (bodies[second].vy - bodies[first].vy) * contact.ny
            if (relativeNormal < 0.0) {
                val impulse = 0.5 * weight * relativeNormal
                velocityX[first] += impulse * contact.nx
                velocityY[first] += impulse * contact.ny
                velocityX[second] -= impulse * contact.nx
                velocityY[second] -= impulse * contact.ny
            }

            val correction =
                0.5 * POSITION_RELAXATION * weight *
                    (DIAMETER - contact.distance)
            correctionX[first] -= correction * contact.nx
            correctionY[first] -= correction * contact.ny
            correctionX[second] += correction * contact.nx
            correctionY[second] += correction * contact.ny
        }

        var maxRaw = 0.0
        var maxApplied = 0.0
        bodies.forEachIndexed { index, body ->
            body.vx += velocityX[index]
            body.vy += velocityY[index]
            val raw = hypot(correctionX[index], correctionY[index])
            val scale = if (raw > MAX_CORRECTION) MAX_CORRECTION / raw else 1.0
            val appliedX = correctionX[index] * scale
            val appliedY = correctionY[index] * scale
            body.x += appliedX
            body.y += appliedY
            maxRaw = maxOf(maxRaw, raw)
            maxApplied = maxOf(maxApplied, hypot(appliedX, appliedY))
        }
        return StepStats(pairs.size, maxRaw, maxApplied)
    }

    private fun log(
        scenario: String,
        step: Int,
        bodies: List<Body>,
        stats: StepStats,
    ) {
        val center = bodies.first()
        val centroid = centroid(bodies)
        println(
            "COLLISION_DIAGNOSTIC scenario=$scenario step=$step " +
                "contacts=${stats.contacts} " +
                "rawCorrectionPx=${"%.6f".format(stats.maxRawCorrection)} " +
                "appliedCorrectionPx=${"%.6f".format(stats.maxAppliedCorrection)} " +
                "center=(${"%.6f".format(center.x)},${"%.6f".format(center.y)}) " +
                "centroid=(${"%.6f".format(centroid.first)},${"%.6f".format(centroid.second)})"
        )
    }

    private fun centroid(bodies: List<Body>): Pair<Double, Double> =
        bodies.sumOf { it.x } / bodies.size to bodies.sumOf { it.y } / bodies.size

    private companion object {
        const val DIAMETER = 1.0
        const val POSITION_RELAXATION = 0.5
        const val MAX_CORRECTION = 0.25
    }
}
