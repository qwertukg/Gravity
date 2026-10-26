package dev.gravity

import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

/**
 * Blocks outlined by the pictures of the masks directory live like
 * rectangular ones: a hole holds what is in it, separate patches are blocks
 * of their own, and a hit breaks them.
 */
class MaskBlockIntegrationTest {
    private val width = 1024
    private val height = 1024

    /** Blocks by group: the members of each, from the materials' group field. */
    private fun groups(pipeline: GravityPipeline, active: Int): Map<Int, List<Int>> {
        val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
        return (0 until active).filter { materials[it * 4 + 2] != 0f && materials[it * 4 + 3] >= 0f }
            .groupBy { materials[it * 4 + 3].toInt() }
    }

    private fun world(field: Float, strength: Float, action: (GravityPipeline) -> Unit) {
        CollisionGpuContext().use {
            val config = GravityConfig(
                particles = ParticleConfig(capacity = 200_000, initialBodies = 1, seed = 1, spatialSortInterval = 0),
                gravity = GravityFieldConfig(gridWidth = 256, gridHeight = 256),
                projectiles = ProjectileConfig(blockStrength = strength),
            )
            GravityPipeline(config, width, height, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.fieldAccelerationY = -field
                // One loose body parked in a corner keeps the population non-empty.
                CollisionGpuAccess.upload(pipeline, floatArrayOf(2f / width, (height - 2f) / height, 0f, 0f))
                action(pipeline)
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
    }

    @Test
    fun `a ring holds a ball in its hole`() = world(field = 300f, strength = Float.POSITIVE_INFINITY) { pipeline ->
        val ring = BlockMask.load(File("masks/ring.png"))
        // 240 px across, the hole 120 px: on the floor, its centre 120 px up.
        val block = pipeline.spawnBlock(1, 392f, 0f, 632f, 240f, mask = ring)
        assertEquals(ring.bodyCount(CollisionConfig.REFERENCE_DIAMETER_PIXELS, 240f, 240f), block)
        val ball = pipeline.spawnProjectile(1 + block, 512f, 120f, 0f, 0f, diameterPixels = 30f)
        assertTrue(ball > 0)
        val active = 1 + block + ball
        pipeline.initializeAcceleration(active)
        repeat(600) { pipeline.step(active) }

        val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
        assertTrue(bodies.all { it.isFinite() })
        val members = groups(pipeline, active)
        assertEquals(1, members.size, "the ring stays one block")
        val ringX = members.values.single().map { bodies[it * 4] * width }.average()
        val ringY = members.values.single().map { bodies[it * 4 + 1] * height }.average()
        val ballBodies = (1 + block until active)
        val ballY = ballBodies.map { bodies[it * 4 + 1] * height }.average()
        // The ball is loose bodies: it spreads over the bottom of the hole.
        val farthest = ballBodies.maxOf { hypot(bodies[it * 4] * width - ringX, bodies[it * 4 + 1] * height - ringY) }
        // How close a body of the ball came to a member of the ring, in contact diameters.
        val closest = ballBodies.minOf { body ->
            members.values.single().minOf { member ->
                hypot(bodies[body * 4] * width - bodies[member * 4] * width, bodies[body * 4 + 1] * height - bodies[member * 4 + 1] * height)
            }
        } / CollisionConfig.REFERENCE_DIAMETER_PIXELS
        println("ring=($ringX, $ringY) ball y=$ballY farthest=$farthest px closest=$closest diameters")
        assertTrue(ringY in 110.0..130.0, "the ring rests on the floor: $ringY")
        // The hole's edge is 60 px from its centre, give or take a lattice step of its members.
        assertTrue(farthest < 62.0, "the ball stays in the hole: $farthest px from its centre")
        assertTrue(closest > 0.8, "the ball rests on the ring, not in it: $closest diameters")
        assertTrue(ballY < ringY - 30.0, "the ball lies at the bottom of the hole: $ballY")
    }

    @Test
    fun `separate patches are blocks of their own`() = world(field = 300f, strength = Float.POSITIVE_INFINITY) { pipeline ->
        val image = java.awt.image.BufferedImage(9, 3, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 3) for (x in 0 until 9) image.setRGB(x, y, if (x < 3 || x >= 6) 0 else 0xffffff)
        val mask = BlockMask.of("apart", image)
        val block = pipeline.spawnBlock(1, 300f, 400f, 480f, 460f, mask = mask)
        assertEquals(mask.bodyCount(CollisionConfig.REFERENCE_DIAMETER_PIXELS, 180f, 60f), block)
        val active = 1 + block
        pipeline.initializeAcceleration(active)
        repeat(600) { pipeline.step(active) }

        val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
        val members = groups(pipeline, active)
        assertEquals(2, members.size, "two patches, two blocks")
        for (patch in members.values) {
            val y = patch.map { bodies[it * 4 + 1] * height }.average()
            assertTrue(y in 25.0..35.0, "each lands on the floor: $y")
        }
    }

    @Test
    fun `an arch breaks under a bullet`() = world(field = 0f, strength = ProjectileConfig().blockStrength) { pipeline ->
        val arch = BlockMask.load(File("masks/arch.png"))
        val block = pipeline.spawnBlock(1, 312f, 400f, 712f, 660f, mask = arch)
        val bullet = pipeline.spawnProjectile(1 + block, 150f, 600f, 3000f, 0f, diameterPixels = 40f)
        val active = 1 + block + bullet
        pipeline.initializeAcceleration(active)
        repeat(240) { pipeline.step(active) }
        pipeline.settleFractures()

        val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
        assertTrue(bodies.all { it.isFinite() })
        val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
        val grains = (0 until active).count { materials[it * 4 + 2] != 0f && materials[it * 4 + 3] < 0f }
        val blocks = groups(pipeline, active).size
        println("arch=$block bullet=$bullet grains=$grains blocks=$blocks")
        assertTrue(grains > 0 || blocks > 1, "the hit breaks the arch")
    }
}
