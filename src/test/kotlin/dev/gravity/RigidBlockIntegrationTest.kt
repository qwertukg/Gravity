package dev.gravity

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

class RigidBlockIntegrationTest {
    private val width = 3440
    private val height = 1440

    @Test
    fun `a glued block hit off centre stays rigid, moves and spins`() {
        CollisionGpuContext().use {
            GravityPipeline(config(), width, height).use { pipeline ->
                pipeline.mutualGravity = false
                val block = pipeline.spawnBlock(firstBody = 1, minX = 1000f, minY = 700f, maxX = 1040f, maxY = 730f)
                assertTrue(block > 500, "block bodies: $block")
                // A bullet below the block's upper edge, off its centre line.
                val bullet = pipeline.spawnProjectile(
                    firstBody = 1 + block,
                    centerX = 975f,
                    centerY = 722f,
                    velocityX = 600f,
                    velocityY = 0f,
                    diameterPixels = 10f,
                )
                assertTrue(bullet > 0)
                val active = 1 + block + bullet
                // Park the seeded body out of the way.
                val initial = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
                initial[0] = 10f / width; initial[1] = 10f / height; initial[2] = 0f; initial[3] = 0f
                CollisionGpuAccess.upload(pipeline, initial)
                // Only the unit-mass bullet moves.
                val momentumBefore = momentumX(initial, from = 1, until = active)

                pipeline.initializeAcceleration(active)
                repeat(120) { pipeline.step(active) }

                val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
                val rigid = RigidAccess(pipeline)
                val members = rigid.ints("members", block)
                val rest = rigid.floats("rest", block * 2)
                assertEquals(block, members.toSet().size, "members follow spatial sorting to distinct bodies")

                var centerX = 0.0; var centerY = 0.0; var velocityX = 0.0
                for (k in 0 until block) {
                    centerX += bodies[members[k] * 4] * width
                    centerY += bodies[members[k] * 4 + 1] * height
                    velocityX += bodies[members[k] * 4 + 2] * width
                }
                centerX /= block; centerY /= block; velocityX /= block
                var cross = 0.0; var dot = 0.0
                for (k in 0 until block) {
                    val px = bodies[members[k] * 4] * width - centerX
                    val py = bodies[members[k] * 4 + 1] * height - centerY
                    cross += rest[2 * k] * py - rest[2 * k + 1] * px
                    dot += rest[2 * k] * px + rest[2 * k + 1] * py
                }
                val angle = atan2(cross, dot)
                var residual = 0.0
                for (k in 0 until block) {
                    val rx = rest[2 * k]; val ry = rest[2 * k + 1]
                    val expectedX = centerX + cos(angle) * rx - sin(angle) * ry
                    val expectedY = centerY + sin(angle) * rx + cos(angle) * ry
                    residual = maxOf(
                        residual,
                        hypot(bodies[members[k] * 4] * width - expectedX, bodies[members[k] * 4 + 1] * height - expectedY),
                    )
                }
                println("block=$block bullet=$bullet residual=$residual px angle=$angle rad velocity=$velocityX px/s")
                assertTrue(residual < 0.01, "block keeps its shape: residual $residual px")
                assertTrue(velocityX > 5.0, "the hit moves the block: $velocityX px/s")
                assertTrue(abs(angle) > 0.05, "an off-centre hit spins the block: $angle rad")
                // The parked body stays at rest; sorting may have moved its ID.
                // Masses must have followed the bodies through every sort.
                val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
                val momentumAfter = (0 until active).sumOf { materials[4 * it] * bodies[it * 4 + 2].toDouble() * width }
                assertEquals(momentumBefore, momentumAfter, 0.01 * momentumBefore, "momentum is conserved")
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
    }

    private fun momentumX(bodies: FloatArray, from: Int, until: Int): Double =
        (from until until).sumOf { bodies[it * 4 + 2].toDouble() * width }

    private fun config() = GravityConfig(
        particles = ParticleConfig(capacity = 4096, initialBodies = 1, seed = 7235, spatialSortInterval = 1),
        gravity = GravityFieldConfig(gridWidth = 128, gridHeight = 128),
    )

    private class RigidAccess(pipeline: GravityPipeline) {
        private val blocks = GravityPipeline::class.java.getDeclaredField("rigidBlocks")
            .also { it.isAccessible = true }.get(pipeline)

        private fun buffer(name: String) = RigidBlocks::class.java.getDeclaredField(name)
            .also { it.isAccessible = true }.getInt(blocks)

        fun ints(name: String, length: Int) = CollisionGpuAccess.ints(buffer(name), length)
        fun floats(name: String, length: Int) = CollisionGpuAccess.floats(buffer(name), length)
    }
}
