package dev.gravity

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

class BlockScenarioTest {
    private val screens = listOf(3440f to 1440f, 1920f to 1080f)
    private val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS

    @Test
    fun `every scenario has a name of its own for --scenario`() {
        val names = (Scenario.entries + BlockScenario.entries + TestScenario.entries).map { it.optionName }
        assertTrue(names.size == names.toSet().size, "duplicate names in $names")
    }

    @Test
    fun `every block scenario fits the screen without overlaps at the start`() {
        for ((width, height) in screens) {
            val space = ScenarioSpace(width, height, 0.035f, diameter)
            for (scenario in BlockScenario.entries + TestScenario.entries) {
                val setup = scenario.setup(space, seed = 1234L)
                val count = setup.bodies.size / 4
                assertTrue(count in 1..Scenario.MAXIMUM_BODIES, "${scenario.title}: $count bodies")
                // A bullet into a wall needs none.
                if (scenario !is TestScenario || scenario.group == TestGroup.BLOCKS) {
                    assertTrue(setup.blocks.isNotEmpty(), "${scenario.title} has blocks")
                }
                for (block in setup.blocks) {
                    assertTrue(block.minX >= 0f && block.minY >= 0f && block.maxX <= width && block.maxY <= height, "${scenario.title}: $block on screen")
                    assertTrue(RectangularBlock.bodyCount(diameter, block.maxX - block.minX, block.maxY - block.minY) > 0, "${scenario.title}: $block has bodies")
                }
                for ((i, a) in setup.blocks.withIndex()) for (b in setup.blocks.drop(i + 1)) {
                    val apart = a.maxX <= b.minX || b.maxX <= a.minX || a.maxY <= b.minY || b.maxY <= a.minY
                    assertTrue(apart, "${scenario.title}: $a and $b overlap")
                }
                for (id in 0 until count) {
                    val x = setup.bodies[id * 4] * width
                    val y = setup.bodies[id * 4 + 1] * height
                    assertTrue(x in 0f..width && y in 0f..height, "${scenario.title} body $id at ($x, $y)")
                    for (block in setup.blocks) {
                        // Distance from the body centre to the block's rectangle.
                        val dx = max(max(block.minX - x, x - block.maxX), 0f)
                        val dy = max(max(block.minY - y, y - block.maxY), 0f)
                        assertTrue(dx > 0f || dy > 0f || min(dx, dy) > 0.5f * diameter, "${scenario.title} body $id starts inside $block")
                    }
                }
            }
        }
    }
}
