package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenarioTest {
    private val screens = listOf(3440f to 1440f, 1920f to 1080f)

    @Test
    fun `every scenario fits the body limit with finite bodies on the torus`() {
        assertEquals(10, Scenario.entries.size)
        for ((width, height) in screens) {
            val space = ScenarioSpace(width, height, 0.035f, 1.12838f)
            for (scenario in Scenario.entries) {
                val bodies = scenario.bodies(space, seed = 1234L)
                val count = bodies.size / 4
                assertTrue(count in 50_000..Scenario.MAXIMUM_BODIES, "${scenario.title}: $count bodies")
                for (i in 0 until count) {
                    val x = bodies[i * 4]
                    val y = bodies[i * 4 + 1]
                    assertTrue(x >= 0f && x < 1f && y >= 0f && y < 1f, "${scenario.title} body $i at ($x, $y)")
                    assertTrue(bodies[i * 4 + 2].isFinite() && bodies[i * 4 + 3].isFinite(), "${scenario.title} body $i velocity")
                }
            }
        }
    }

    @Test
    fun `bodies do not start overlapping`() {
        // Overlapping starts are pushed apart at hundreds of px/s by the
        // collision solver, spraying fast bodies over the whole screen.
        val diameter = 1.12838f
        for ((width, height) in screens) {
            val space = ScenarioSpace(width, height, 0.035f, diameter)
            for (scenario in Scenario.entries) {
                val bodies = scenario.bodies(space, seed = 1234L)
                val count = bodies.size / 4
                val cells = HashMap<Long, MutableList<Int>>()
                fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
                for (i in 0 until count) {
                    val cx = (bodies[i * 4] * width / diameter).toInt()
                    val cy = (bodies[i * 4 + 1] * height / diameter).toInt()
                    cells.getOrPut(key(cx, cy)) { mutableListOf() } += i
                }
                var overlapping = 0
                for (i in 0 until count) {
                    val x = bodies[i * 4] * width
                    val y = bodies[i * 4 + 1] * height
                    val cx = (x / diameter).toInt()
                    val cy = (y / diameter).toInt()
                    val close = (-1..1).any { dy ->
                        (-1..1).any { dx ->
                            cells[key(cx + dx, cy + dy)].orEmpty().any { j ->
                                j != i && kotlin.math.hypot(
                                    bodies[j * 4] * width - x,
                                    bodies[j * 4 + 1] * height - y,
                                ) < 0.95f * diameter
                            }
                        }
                    }
                    if (close) overlapping++
                }
                assertTrue(overlapping <= count / 1000, "${scenario.title} at ${height.toInt()} px: $overlapping overlapping bodies")
            }
        }
    }

    @Test
    fun `scenarios keep their screen size for larger bodies`() {
        for (pointSize in listOf(2f, 3f, 6f)) {
            val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS * pointSize
            val reference = ScenarioSpace(3440f, 1440f, 0.035f, CollisionConfig.REFERENCE_DIAMETER_PIXELS)
            val space = ScenarioSpace(3440f, 1440f, 0.035f, diameter)
            for (scenario in Scenario.entries) {
                val expected = scenario.bodies(reference, 3L).size / 4 / (pointSize * pointSize)
                val count = scenario.bodies(space, 3L).size / 4
                // Packing rounds per disc, so allow a few percent.
                assertEquals(expected, count.toFloat(), expected * 0.05f, "${scenario.title} at point size $pointSize")
            }
        }
    }

    @Test
    fun `scenarios are reproducible from their seed`() {
        val space = ScenarioSpace(3440f, 1440f, 0.035f, 1.12838f)
        for (scenario in Scenario.entries) {
            assertTrue(scenario.bodies(space, 7L).contentEquals(scenario.bodies(space, 7L)), scenario.title)
        }
    }

    @Test
    fun `circular speed is flat in 2D gravity apart from the periodic background`() {
        val space = ScenarioSpace(3440f, 1440f, 0.035f, 1.12838f)
        val expected = kotlin.math.sqrt(0.035f) * 1440f
        assertEquals(expected, space.circularSpeed(1f, 0f), 1e-3f)
        assertTrue(space.circularSpeed(1f, 500f) < expected)
        assertEquals(0f, space.circularSpeed(0f, 500f))
    }
}
