package dev.gravity

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

/**
 * Every block scenario and every test scenario of the menu runs as the
 * application starts it, blocks breaking, and stays sound: finite, blocks out
 * of the walls, no loose body (grains broken off included) deep in a block.
 */
class BlockScenarioRunTest {
    private val width = 1920
    private val height = 1080
    private val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS

    @Test
    fun `block scenarios run soundly`() {
        val space = ScenarioSpace(width.toFloat(), height.toFloat(), GravityFieldConfig().gravitationalConstant, diameter)
        for (scenario in BlockScenario.entries + TestScenario.entries) {
            val setup = scenario.setup(space, seed = 1234L)
            val count = setup.bodies.size / 4
            CollisionGpuContext().use {
                val blockBodies = setup.blocks.sumOf { RectangularBlock.bodyCount(diameter, it.maxX - it.minX, it.maxY - it.minY) }
                val config = GravityConfig(
                    particles = ParticleConfig(capacity = count + blockBodies + 1_000, initialBodies = count, seed = 1),
                    projectiles = if (scenario.unbreakable) ProjectileConfig(blockStrength = Float.POSITIVE_INFINITY) else ProjectileConfig(),
                )
                GravityPipeline(config, width, height, null, setup.bodies, bounded = true).use { pipeline ->
                    pipeline.mutualGravity = false
                    pipeline.liquid = scenario.liquid
                    // The application's field: 300 px/s² at 1440 px, scaled to the screen.
                    if (scenario.field) pipeline.fieldAccelerationY = -config.gravity.uniformFieldAcceleration * height / 1440f
                    var active = count
                    for (block in setup.blocks) {
                        active += pipeline.spawnBlock(active, block.minX, block.minY, block.maxX, block.maxY,
                            block.density, block.velocityX, block.velocityY, block.spin)
                    }
                    pipeline.initializeAcceleration(active)
                    repeat(240) { pipeline.step(active) }
                    val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
                    val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
                    assertTrue(bodies.all { it.isFinite() }, "${scenario.title}: finite")
                    val radius = 0.5f * diameter
                    var deepest = 0f
                    var wall = 0f
                    // Grains broken off a block are solid but name no block.
                    fun member(id: Int) = materials[id * 4 + 2] != 0f && materials[id * 4 + 3] >= 0f
                    val members = (0 until active).filter(::member)
                    for (id in members) {
                        val x = bodies[id * 4] * width
                        val y = bodies[id * 4 + 1] * height
                        wall = maxOf(wall, radius - x, x + radius - width, radius - y, y + radius - height)
                    }
                    // Loose bodies within the fill of a member of a block (BlockPenetrationTest).
                    val cell = 2f * diameter
                    val grid = HashMap<Long, MutableList<Int>>()
                    fun key(x: Float, y: Float) = ((x / cell).toLong() shl 32) or ((y / cell).toLong() and 0xffffffffL)
                    for (id in members) grid.getOrPut(key(bodies[id * 4] * width, bodies[id * 4 + 1] * height)) { mutableListOf() } += id
                    for (id in 0 until active) {
                        if (member(id)) continue
                        val x = bodies[id * 4] * width
                        val y = bodies[id * 4 + 1] * height
                        for (dx in -1..1) for (dy in -1..1) {
                            for (member in grid[key(x + dx * cell, y + dy * cell)].orEmpty()) {
                                val distance = hypot(bodies[member * 4] * width - x, bodies[member * 4 + 1] * height - y)
                                deepest = maxOf(deepest, GlueShape.FILL_DIAMETERS * diameter - distance)
                            }
                        }
                    }
                    println("${scenario.title}: bodies $active, deepest in a block %.3f px, into a wall %.4f px".format(deepest, wall))
                    assertTrue(wall < 1e-3f, "${scenario.title}: blocks stay out of the walls")
                    assertTrue(deepest < 0.4f * diameter, "${scenario.title}: no body deep in a block")
                }
                assertEquals(GL_NO_ERROR, glGetError())
            }
        }
    }
}
