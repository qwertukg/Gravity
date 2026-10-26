package dev.gravity

import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

/**
 * Sleeping tiles (SleepTiles): a pile at rest is left exactly as it is,
 * and what moves into it wakes it.
 */
class SleepIntegrationTest {
    private val width = 256
    private val height = 256
    private val diameter = 1.12838f
    private val field = 300f

    @Test
    fun `a settled pile sleeps still and a body dropped on it wakes it`() {
        CollisionGpuContext().use {
            val pile = pile()
            val count = pile.size / 4
            GravityPipeline(config(count + 1), width, height, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.fieldAccelerationY = -field
                CollisionGpuAccess.upload(pipeline, pile)
                pipeline.initializeAcceleration(count)
                repeat(1200) { pipeline.step(count) }
                val settled = CollisionGpuAccess.floats(pipeline, "bodies", count * 4)
                repeat(120) { pipeline.step(count) }
                val later = CollisionGpuAccess.floats(pipeline, "bodies", count * 4)
                val still = (0 until count).count { settled[it * 4] == later[it * 4] && settled[it * 4 + 1] == later[it * 4 + 1] }
                val moved = (0 until count).maxOf {
                    hypot((later[it * 4] - settled[it * 4]) * width, (later[it * 4 + 1] - settled[it * 4 + 1]) * height)
                }
                println("sleep: $still of $count bodies of the settled pile exactly still over 120 steps, the rest moved at most %.3f px".format(moved))
                assertTrue(still >= count * 65 / 100, "a settled pile sleeps: $still of $count still")
                assertTrue(moved < 8f, "what still moves in a settled pile creeps: $moved px in 120 steps")

                // A body falls onto the pile from above its middle: the pile
                // wakes under it and holds it on top.
                val top = (0 until count).maxOf { later[it * 4 + 1] } * height
                val dropped = later.copyOf((count + 1) * 4)
                dropped[count * 4] = 0.5f
                dropped[count * 4 + 1] = (top + 40f) / height
                dropped[count * 4 + 2] = 0f
                dropped[count * 4 + 3] = 0f
                CollisionGpuAccess.upload(pipeline, dropped)
                pipeline.initializeAcceleration(count + 1)
                repeat(240) { pipeline.step(count + 1) }
                val after = CollisionGpuAccess.floats(pipeline, "bodies", (count + 1) * 4)
                val landed = after[count * 4 + 1] * height
                val speed = hypot(after[count * 4 + 2] * width, after[count * 4 + 3] * height)
                println("sleep: the dropped body rests at y=%.1f px on a pile up to %.1f px, speed %.1f px/s".format(landed, top, speed))
                // It may bury itself a little in the loose bed, as it would awake.
                assertTrue(landed > top - 3f * diameter, "the dropped body stays on the pile: $landed px over $top px")
                assertTrue(speed < 5f, "the dropped body has come to rest: $speed px/s")
                val woken = (0 until count).count {
                    hypot((after[it * 4] - later[it * 4]) * width, (after[it * 4 + 1] - later[it * 4 + 1]) * height) > 0.01f
                }
                println("sleep: $woken bodies of the pile moved under the dropped body")
                assertTrue(woken > 0, "the pile wakes under the dropped body")
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
    }

    @Test
    fun `a slow body rolling into a resting one still pushes it`() {
        CollisionGpuContext().use {
            // The resting body sleeps alone on the floor far from anything;
            // the other rolls in at 40 px/s.
            val bodies = floatArrayOf(
                150f / width, 0.5f * diameter / height, 0f, 0f,
                60f / width, 0.5f * diameter / height, 40f / width, 0f,
            )
            GravityPipeline(config(2, friction = 0f), width, height, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.fieldAccelerationY = -field
                CollisionGpuAccess.upload(pipeline, bodies)
                pipeline.initializeAcceleration(2)
                repeat(480) { pipeline.step(2) }
                // The spatial sort renames bodies: tell them by their order.
                val state = CollisionGpuAccess.floats(pipeline, "bodies", 8)
                val ahead = maxOf(state[0], state[4]) * width
                val behind = minOf(state[0], state[4]) * width
                println("sleep: the resting body was pushed from 150 to %.1f px, the rolling one is at %.1f px".format(ahead, behind))
                assertTrue(ahead > 160f, "the resting body is pushed along: $ahead px")
                assertTrue(ahead - behind >= 0.9f * diameter, "the bodies do not overlap: ${ahead - behind} px apart")
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
    }

    /** A hexagonal bed of full rows on the floor between the walls, in unit coordinates. */
    private fun pile(): FloatArray {
        val values = ArrayList<Float>()
        val step = 1.02f * diameter
        val rowStep = step * sqrt(3f) * 0.5f
        for (row in 0 until 16) {
            val y = (0.5f + row) * rowStep
            var x = 0.5f * diameter + if (row % 2 == 0) 0f else 0.5f * step
            while (x <= width - 0.5f * diameter) {
                values += x / width
                values += y / height
                values += 0f
                values += 0f
                x += step
            }
        }
        return values.toFloatArray()
    }

    private fun config(count: Int, friction: Float = 0.5f) = GravityConfig(
        particles = ParticleConfig(capacity = 4096, initialBodies = count, seed = 7235),
        gravity = GravityFieldConfig(gridWidth = 128, gridHeight = 128),
        collisions = CollisionConfig(friction = friction),
    )
}
