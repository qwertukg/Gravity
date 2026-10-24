package dev.gravity

import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.*

class CollisionShaderIntegrationTest {
    private val width = 3440
    private val height = 1440
    private val diameter = 1.12838f

    @Test
    fun `GPU cache retains all contacts and matches original corrections including both overflow paths`() {
        CollisionGpuContext().use {
            val random = Random(7235)
            val scenes = linkedMapOf(
                "isolated" to FloatArray(128 * 4).also { data ->
                    repeat(128) { i -> data[i * 4] = random.nextFloat(); data[i * 4 + 1] = random.nextFloat() }
                },
                "pair" to scene(2) { i -> floatArrayOf(100f + i * 0.8f, 100f, if (i == 0) 10f else -10f, 0f) },
                "contact-cache overflow" to scene(20) { i -> floatArrayOf(500f + (i % 5) * 0.12f, 500f + (i / 5) * 0.12f, i.toFloat(), -i.toFloat()) },
                "neighbor-cache overflow" to scene(130) { i -> floatArrayOf(700f + (i % 13) * 0.025f, 600f + (i / 13) * 0.025f, i.toFloat(), -i.toFloat()) },
                "coincident" to scene(130) { floatArrayOf(700f, 600f, 0f, 0f) },
                "seams" to scene(16) { i -> floatArrayOf(if (i % 2 == 0) 0.1f else width - 0.1f, if (i % 4 < 2) 0.1f else height - 0.1f, i * 11f, -i * 7f) },
                "square" to scene(256) { i -> floatArrayOf(1000f + i % 16, 700f + i / 16, 0f, 0f) },
                "contact threshold" to scene(128) { i ->
                    val gap = when ((i / 2) % 3) { 0 -> Math.nextDown(diameter); 1 -> diameter; else -> Math.nextUp(diameter) }
                    floatArrayOf(10f + (i / 2) * 4f + (i % 2) * gap, 10f, 0f, 0f)
                },
            )
            for ((name, input) in scenes) {
                compareReplay(name, input, passes = if (name == "isolated") 4 else 12)
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
    }

    @Test
    fun `GPU cache rebuilds after fast drift seam crossing insertion and accumulated corrections`() {
        CollisionGpuContext().use {
            val initial = scene(4) { i -> floatArrayOf(if (i < 2) 0.15f + i * 0.8f else 200f + i, 200f, 0f, 0f) }
            val config = config(32)
            GravityPipeline(config, width, height).use { optimized ->
                GravityPipeline(config.copy(collisions = config.collisions.copy(neighborCache = false)), width, height).use { reference ->
                    var input = initial
                    repeat(30) { step ->
                        if (step == 4) input[2] = -2000f / width
                        if (step == 8) input = input + scene(3) { i -> floatArrayOf(200f + i * 0.5f, 200f, 0f, 0f) }
                        CollisionGpuAccess.upload(optimized, input)
                        CollisionGpuAccess.upload(reference, input, clearHeads = true)
                        val drift = config.collisions.timeStep / 16
                        CollisionGpuAccess.collide(optimized, input.size / 4, drift)
                        CollisionGpuAccess.collide(reference, input.size / 4, drift)
                        compare("motion $step", optimized, reference, input.size / 4)
                        input = CollisionGpuAccess.floats(optimized, "bodies", input.size)
                    }
                    assertTrue(CollisionGpuAccess.rebuilds(optimized) > 2, "motion must invalidate cached neighbors")
                }
            }
        }
    }

    @Test
    fun `stationary sparse scene actually reuses the GPU neighbor list`() {
        CollisionGpuContext().use {
            val input = scene(32) { i -> floatArrayOf(30f * i + 20f, 400f, 0f, 0f) }
            GravityPipeline(config(32), width, height).use { pipeline ->
                CollisionGpuAccess.upload(pipeline, input)
                repeat(20) { CollisionGpuAccess.collide(pipeline, 32) }
                assertEquals(1, CollisionGpuAccess.rebuilds(pipeline))
            }
        }
    }

    @Test
    fun `cache displacement boundary rebuilds before a previously excluded pair touches`() {
        CollisionGpuContext().use {
            val config = config(2)
            GravityPipeline(config, width, height).use { pipeline ->
                // Start outside the candidate radius, then approach from both
                // sides. The second move crosses half-skin and must rebuild
                // BEFORE evaluating the newly created contact.
                val left = 100f
                val right = left + diameter + config.collisions.neighborSkinPixels + 0.02f
                for ((index, move) in listOf(0f, 0.20f, 0.30f).withIndex()) {
                    val input = scene(2) { i -> floatArrayOf(if (i == 0) left + move else right - move, 200f, 0f, 0f) }
                    CollisionGpuAccess.upload(pipeline, input)
                    CollisionGpuAccess.collide(pipeline, 2)
                    assertContentEquals(bruteForceCounts(input), CollisionGpuAccess.ints(pipeline, "collisionContactCounts", 2))
                    assertEquals(if (index < 2) 1 else 2, CollisionGpuAccess.rebuilds(pipeline))
                }
                assertContentEquals(intArrayOf(1, 1), CollisionGpuAccess.ints(pipeline, "collisionContactCounts", 2))
            }
        }
    }

    @Test
    fun `subpixel skin disables reuse conservatively instead of dropping contacts`() {
        CollisionGpuContext().use {
            val config = config(2).let { it.copy(collisions = it.collisions.copy(neighborSkinPixels = 0.00001f)) }
            GravityPipeline(config, width, height).use { pipeline ->
                val input = scene(2) { i -> floatArrayOf(100f + i * 0.8f, 100f, 0f, 0f) }
                repeat(3) {
                    CollisionGpuAccess.upload(pipeline, input)
                    CollisionGpuAccess.collide(pipeline, 2)
                    assertContentEquals(intArrayOf(1, 1), CollisionGpuAccess.ints(pipeline, "collisionContactCounts", 2))
                }
                assertEquals(3, CollisionGpuAccess.rebuilds(pipeline))
            }
        }
    }

    @Test
    fun `optional adaptive indirect dispatch preserves inactive ping pong parity and final pressure`() {
        CollisionGpuContext().use {
            val config = config(8).let { it.copy(collisions = it.collisions.copy(adaptive = true)) }
            GravityPipeline(config, width, height).use { optimized ->
                GravityPipeline(config.copy(collisions = config.collisions.copy(neighborCache = false)), width, height).use { reference ->
                    val input = scene(8) { i -> floatArrayOf(100f + (i / 2) * 100f + (i % 2) * 0.8f, 100f, if (i % 2 == 0) 20f else -20f, 0f) }
                    CollisionGpuAccess.upload(optimized, input)
                    CollisionGpuAccess.upload(reference, input, clearHeads = true)
                    for (pipeline in listOf(optimized, reference)) CollisionGpuAccess.adaptiveSteps(pipeline, 8, 4, 16)
                    repeat(16) { substep ->
                        CollisionGpuAccess.collide(optimized, 8, config.collisions.timeStep / 16, pressure = false, substep = substep)
                        CollisionGpuAccess.collide(reference, 8, config.collisions.timeStep / 16, pressure = false, substep = substep)
                        compare("adaptive $substep", optimized, reference, 8, exact = true)
                    }
                    CollisionGpuAccess.collide(optimized, 8)
                    CollisionGpuAccess.collide(reference, 8)
                    compare("adaptive final", optimized, reference, 8, exact = true)
                    assertContentEquals(CollisionGpuAccess.ints(reference, "collisionAdaptiveState", 5), CollisionGpuAccess.ints(optimized, "collisionAdaptiveState", 5))
                }
            }
        }
    }

    @Test
    fun `overflow fallback stays exhaustive when the reference grid is reused`() {
        CollisionGpuContext().use {
            val config = config(130)
            val input = scene(130) { i -> floatArrayOf(700f + i % 13 * 0.025f, 600f + i / 13 * 0.025f, 0f, 0f) }
            GravityPipeline(config, width, height).use { optimized ->
                GravityPipeline(config.copy(collisions = config.collisions.copy(neighborCache = false)), width, height).use { reference ->
                    repeat(4) {
                        CollisionGpuAccess.upload(optimized, input)
                        CollisionGpuAccess.upload(reference, input, clearHeads = true)
                        CollisionGpuAccess.collide(optimized, 130)
                        CollisionGpuAccess.collide(reference, 130)
                        assertContentEquals(IntArray(130) { 129 }, CollisionGpuAccess.ints(optimized, "collisionContactCounts", 130))
                        compare("reused overflow $it", optimized, reference, 130)
                    }
                    assertEquals(1, CollisionGpuAccess.rebuilds(optimized))
                }
            }
        }
    }

    @Test
    fun `dense scene uses direct grid without changing contacts or corrections`() {
        CollisionGpuContext().use {
            val config = config(4096)
            val input = scene(4096) { i -> floatArrayOf(1000f + i % 64, 600f + i / 64, 0f, 0f) }
            val expected = IntArray(4096) { i ->
                (if (i % 64 > 0) 1 else 0) + (if (i % 64 < 63) 1 else 0) +
                    (if (i / 64 > 0) 1 else 0) + (if (i / 64 < 63) 1 else 0)
            }
            GravityPipeline(config, width, height).use { optimized ->
                GravityPipeline(config.copy(collisions = config.collisions.copy(neighborCache = false)), width, height).use { reference ->
                    repeat(3) { pass ->
                        CollisionGpuAccess.upload(optimized, input)
                        CollisionGpuAccess.upload(reference, input, clearHeads = true)
                        CollisionGpuAccess.collide(optimized, 4096)
                        CollisionGpuAccess.collide(reference, 4096)
                        assertContentEquals(expected, CollisionGpuAccess.ints(optimized, "collisionContactCounts", 4096))
                        compare("dense direct $pass", optimized, reference, 4096)
                        // Each dense pass schedules the direct grid for the next
                        // one; a direct pass leaves no list, so all rebuild.
                        assertTrue(CollisionGpuAccess.directGrid(optimized), "dense pass $pass schedules the direct grid")
                        assertEquals(pass + 1, CollisionGpuAccess.rebuilds(optimized))
                    }
                }
            }
        }
    }

    @Test
    fun `walls keep cache and reference identical and never contact across an edge`() {
        CollisionGpuContext().use {
            val count = 64
            val config = config(count)
            // Bodies pressed into every wall and corner, plus pairs that touch
            // only through the torus seam and must stay apart with walls.
            val input = scene(count) { i ->
                when (i % 4) {
                    0 -> floatArrayOf(0.2f + (i / 4) * 3f, 0.3f, -30f, 0f)
                    1 -> floatArrayOf(width - 0.2f, 0.2f + (i / 4) * 3f, 25f, 10f)
                    2 -> floatArrayOf(0.1f + (i / 4) * 0.8f, height - 0.1f, 0f, 40f)
                    else -> floatArrayOf(if (i % 8 == 3) 0.05f else width - 0.05f, 700f + i, 0f, -5f)
                }
            }
            GravityPipeline(config, width, height, bounded = true).use { optimized ->
                GravityPipeline(config.copy(collisions = config.collisions.copy(neighborCache = false)), width, height, bounded = true).use { reference ->
                    var state = input
                    repeat(12) { pass ->
                        CollisionGpuAccess.upload(optimized, state)
                        CollisionGpuAccess.upload(reference, state, clearHeads = true)
                        CollisionGpuAccess.collide(optimized, count, config.collisions.timeStep / 16)
                        CollisionGpuAccess.collide(reference, count, config.collisions.timeStep / 16)
                        compare("walls/$pass", optimized, reference, count)
                        state = CollisionGpuAccess.floats(optimized, "bodies", count * 4)
                        for (i in 0 until count) {
                            assertTrue(state[i * 4] in 0f..<1f && state[i * 4 + 1] in 0f..<1f, "walls/$pass body $i left the screen")
                        }
                    }
                    // Seam pairs (x = 0.05 and x = W - 0.05) never count as contacts.
                    val counts = CollisionGpuAccess.ints(optimized, "collisionContactCounts", count)
                    for (i in 3 until count step 4) assertEquals(0, counts[i], "seam body $i")
                }
            }
        }
    }

    @Test
    fun `a wall acts exactly like a mirror-image body`() {
        CollisionGpuContext().use {
            val config = config(3)
            // Small coordinates keep float positions fine enough to compare.
            val mirror = 20f
            GravityPipeline(config, width, height, bounded = true).use { pipeline ->
                // Body 0 flies into the left wall. Bodies 1 and 2 are its
                // mirror pair about x = mirror, clear of every wall: the wall
                // must give body 0 the same motion that body 2 gives body 1.
                var state = scene(3) { i ->
                    when (i) {
                        0 -> floatArrayOf(2f, 300f, -400f, 50f)
                        1 -> floatArrayOf(mirror + 2f, 900f, -400f, 50f)
                        else -> floatArrayOf(mirror - 2f, 900f, 400f, 50f)
                    }
                }
                var touched = false
                repeat(64) {
                    CollisionGpuAccess.upload(pipeline, state)
                    CollisionGpuAccess.collide(pipeline, 3, config.collisions.timeStep / 16)
                    state = CollisionGpuAccess.floats(pipeline, "bodies", 12)
                    val wallX = state[0] * width
                    val pairX = state[4] * width - mirror
                    // The solver leaves no penetration behind; the rebound shows the hit.
                    if (state[2] > 0f) touched = true
                    assertTrue(abs(wallX - pairX) < 5e-3f, "x: wall $wallX, mirror pair $pairX")
                    assertTrue(abs(state[2] - state[6]) * width < 2f, "vx: wall ${state[2] * width}, mirror pair ${state[6] * width}")
                }
                assertTrue(touched, "the body never reached the wall")
                assertTrue(state[0] in 0f..<1f, "the body left the screen")
            }
        }
    }

    @Test
    fun `wall gravity is isolated while torus gravity acts across the edge`() {
        CollisionGpuContext().use {
            val count = 2
            val config = config(count)
            // Two bodies 20 px from the left and right edges: on the torus they
            // are 40 px apart through the edge, with walls 3400 px apart.
            val input = scene(count) { i -> floatArrayOf(if (i == 0) 20f else width - 20f, 720f, 0f, 0f) }
            for (bounded in listOf(false, true)) {
                GravityPipeline(config, width, height, bounded = bounded).use { pipeline ->
                    CollisionGpuAccess.upload(pipeline, input)
                    pipeline.initializeAcceleration(count)
                    val acceleration = CollisionGpuAccess.floats(pipeline, "acceleration", count * 2)
                    // Left body: on the torus pulled left (towards the seam).
                    if (bounded) assertTrue(acceleration[0] > 0f, "walls: left body ax = ${acceleration[0]}")
                    else assertTrue(acceleration[0] < 0f, "torus: left body ax = ${acceleration[0]}")
                    // Mirror symmetry up to the mesh's small self-force (~2e-5),
                    // which both space modes share.
                    assertEquals(-acceleration[0], acceleration[2], 5e-5f)
                }
            }
        }
    }

    @Test
    fun `switching to walls and back restores the periodic gravity exactly`() {
        CollisionGpuContext().use {
            val count = 256
            val config = config(count)
            val random = Random(91)
            val input = scene(count) { floatArrayOf(random.nextFloat() * width, random.nextFloat() * height, 0f, 0f) }
            GravityPipeline(config, width, height).use { pipeline ->
                CollisionGpuAccess.upload(pipeline, input)
                pipeline.initializeAcceleration(count)
                val before = CollisionGpuAccess.floats(pipeline, "acceleration", count * 2)
                pipeline.bounded = true
                pipeline.initializeAcceleration(count)
                val walls = CollisionGpuAccess.floats(pipeline, "acceleration", count * 2)
                pipeline.bounded = false
                pipeline.initializeAcceleration(count)
                val after = CollisionGpuAccess.floats(pipeline, "acceleration", count * 2)
                assertContentEquals(before, after)
                assertTrue(!before.contentEquals(walls))
            }
        }
    }

    @Test
    fun `a uniform field accelerates a free body at exactly its strength`() {
        CollisionGpuContext().use {
            val config = config(1)
            for (bounded in listOf(false, true)) {
                GravityPipeline(config, width, height, bounded = bounded).use { pipeline ->
                    pipeline.fieldAccelerationX = -120f
                    pipeline.fieldAccelerationY = 300f
                    CollisionGpuAccess.upload(pipeline, scene(1) { floatArrayOf(1720f, 300f, 0f, 0f) })
                    pipeline.initializeAcceleration(1)
                    val steps = 60
                    repeat(steps) { pipeline.step(1) }
                    val body = CollisionGpuAccess.floats(pipeline, "bodies", 4)
                    val elapsed = steps * config.collisions.timeStep
                    // A lone body feels only the field (plus a ~1e-5 mesh self-force).
                    assertEquals(-120f * elapsed, body[2] * width, 0.05f, "vx, bounded=$bounded")
                    assertEquals(300f * elapsed, body[3] * height, 0.05f, "vy, bounded=$bounded")
                }
            }
        }
    }

    @Test
    fun `a cleared session with no bodies accepts projectiles`() {
        CollisionGpuContext().use {
            val config = config(4096)
            GravityPipeline(config, width, height).use { pipeline ->
                // As after C: nothing is active, gravity and the preview still run.
                pipeline.initializeAcceleration(0)
                pipeline.updateTrajectory(0, 1000f, 700f, 100f, 0f)
                val spawned = pipeline.spawnProjectile(0, 1000f, 700f, 200f, 0f, 30f)
                assertTrue(spawned > 0)
                repeat(30) { pipeline.step(spawned) }
                assertEquals(0, pipeline.measureCluster(spawned).nonFiniteBodies)
            }
        }
    }

    @Test
    fun `switching off mutual gravity leaves only the uniform field`() {
        CollisionGpuContext().use {
            val count = 256
            val config = config(count)
            val random = Random(17)
            val input = scene(count) { floatArrayOf(random.nextFloat() * width, random.nextFloat() * height, 0f, 0f) }
            GravityPipeline(config, width, height).use { pipeline ->
                pipeline.fieldAccelerationY = -300f
                CollisionGpuAccess.upload(pipeline, input)
                pipeline.initializeAcceleration(count)
                val attracted = CollisionGpuAccess.floats(pipeline, "acceleration", count * 2)
                pipeline.mutualGravity = false
                CollisionGpuAccess.upload(pipeline, input)
                pipeline.initializeAcceleration(count)
                val free = CollisionGpuAccess.floats(pipeline, "acceleration", count * 2)
                for (i in 0 until count) {
                    assertEquals(0f, free[i * 2], "body $i ax")
                    assertEquals(-300f / height, free[i * 2 + 1], "body $i ay")
                }
                assertTrue((0 until count).any { abs(attracted[it * 2]) > 1e-3f })
                // Physics keeps running without the gravity passes.
                repeat(10) { pipeline.step(count) }
                assertEquals(0, pipeline.measureCluster(count).nonFiniteBodies)
            }
        }
    }

    private fun compareReplay(name: String, initial: FloatArray, passes: Int) {
        val count = initial.size / 4
        val config = config(count)
        GravityPipeline(config, width, height).use { optimized ->
            GravityPipeline(config.copy(collisions = config.collisions.copy(neighborCache = false)), width, height).use { reference ->
                var input = initial
                repeat(passes) { pass ->
                    CollisionGpuAccess.upload(optimized, input)
                    CollisionGpuAccess.upload(reference, input, clearHeads = true)
                    CollisionGpuAccess.collide(optimized, count)
                    CollisionGpuAccess.collide(reference, count)
                    val actualCounts = CollisionGpuAccess.ints(optimized, "collisionContactCounts", count)
                    assertContentEquals(bruteForceCounts(input), actualCounts, "$name/$pass independent contacts")
                    val expectedPairs = bruteForceContacts(input)
                    val actualPairs = CollisionGpuAccess.cachedContacts(optimized, count, count)
                    for (id in 0 until count) if (actualCounts[id] <= 8) {
                        assertContentEquals(expectedPairs[id].sorted().toIntArray(), actualPairs[id].sortedArray(), "$name/$pass body $id exact contact IDs")
                    }
                    compare("$name/$pass", optimized, reference, count, exact = name == "isolated" || name == "pair")
                    input = CollisionGpuAccess.floats(optimized, "bodies", input.size)
                }
                println("Verified $name: $count bodies, $passes passes, rebuilds=${CollisionGpuAccess.rebuilds(optimized)}")
            }
        }
    }

    private fun compare(label: String, optimized: GravityPipeline, reference: GravityPipeline, count: Int, exact: Boolean = false) {
        assertContentEquals(CollisionGpuAccess.ints(reference, "collisionContactCounts", count), CollisionGpuAccess.ints(optimized, "collisionContactCounts", count), "$label contact counts")
        for ((name, length) in listOf("bodies" to count * 4, "pressures" to count)) {
            val expected = CollisionGpuAccess.floats(reference, name, length)
            val actual = CollisionGpuAccess.floats(optimized, name, length)
            for (i in actual.indices) {
                assertTrue(actual[i].isFinite(), "$label $name[$i] is not finite")
                if (exact) assertEquals(expected[i].toRawBits(), actual[i].toRawBits(), "$label $name[$i] bits")
                else {
                    // Atomic linked-list order is nondeterministic even in the
                    // reference. Bound one-pass rounding, not long chaotic paths.
                    val tolerance = if (name == "bodies") 2e-7f + 32f * Math.ulp(expected[i]) else 2e-4f + 32f * Math.ulp(expected[i])
                    assertTrue(abs(actual[i] - expected[i]) <= tolerance, "$label $name[$i]: ${actual[i]} != ${expected[i]} (tolerance $tolerance)")
                }
            }
        }
    }

    private fun bruteForceCounts(input: FloatArray): IntArray = bruteForceContacts(input).map { it.size }.toIntArray()

    private fun bruteForceContacts(input: FloatArray): Array<MutableList<Int>> {
        val count = input.size / 4
        val result = Array(count) { mutableListOf<Int>() }
        for (i in 0 until count) for (j in i + 1 until count) {
            // Float pixel conversion matches the GPU contact predicate exactly;
            // pair enumeration is independent of both spatial data structures.
            var dx = input[i * 4] * width - input[j * 4] * width
            var dy = input[i * 4 + 1] * height - input[j * 4 + 1] * height
            dx -= floor(dx / width + 0.5f) * width
            dy -= floor(dy / height + 0.5f) * height
            if (dx * dx + dy * dy < diameter * diameter) { result[i].add(j); result[j].add(i) }
        }
        return result
    }

    private fun config(count: Int) = GravityConfig(
        particles = ParticleConfig(capacity = count, initialBodies = count, seed = 7235),
        gravity = GravityFieldConfig(gridWidth = 128, gridHeight = 128),
    )

    private fun scene(count: Int, body: (Int) -> FloatArray): FloatArray = FloatArray(count * 4).also { data ->
        repeat(count) { i ->
            val value = body(i)
            for (j in 0..3) data[i * 4 + j] = value[j] / if (j % 2 == 0) width else height
        }
    }
}
