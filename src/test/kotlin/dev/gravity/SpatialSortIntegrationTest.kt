package dev.gravity

import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

class SpatialSortIntegrationTest {
    private val width = 3440
    private val height = 1440
    private val diameter = 1.12838f

    @Test
    fun `reordering is a bit-exact permutation along the Morton tile curve`() {
        CollisionGpuContext().use {
            val random = Random(4242)
            val count = 6000
            val input = scene(count) { i ->
                when {
                    // Dense block, coincident bodies, both seams and the far
                    // corner, then uniform bodies: every key range is used.
                    i < 2500 -> floatArrayOf(1200f + (i % 50) * 0.9f, 700f + (i / 50) * 0.9f, i.toFloat(), -i.toFloat())
                    i < 2600 -> floatArrayOf(300f, 300f, 1f, 2f)
                    i < 2700 -> floatArrayOf(if (i % 2 == 0) 0f else width - 0.01f, (i % 97) * 14f, -3f, 3f)
                    i < 2710 -> floatArrayOf(width * 0.99999994f, height * 0.99999994f, 0f, 0f)
                    else -> floatArrayOf(random.nextFloat() * width, random.nextFloat() * height, random.nextFloat(), 0f)
                }
            }.shuffledBodies(random)
            val acceleration = FloatArray(count * 2) { random.nextFloat() - 0.5f }
            GravityPipeline(config(count, sortInterval = 1), width, height).use { pipeline ->
                CollisionGpuAccess.upload(pipeline, input)
                CollisionGpuAccess.uploadFloats(pipeline, "acceleration", acceleration)
                CollisionGpuAccess.sort(pipeline, count)
                val sorted = CollisionGpuAccess.floats(pipeline, "bodies", count * 4)
                val sortedAcceleration = CollisionGpuAccess.floats(pipeline, "acceleration", count * 2)
                assertContentEquals(records(input, acceleration), records(sorted, sortedAcceleration), "same bodies, bit for bit")

                val widthBits = CollisionGpuAccess.intField(pipeline, "sortWidthBits")
                val heightBits = CollisionGpuAccess.intField(pipeline, "sortHeightBits")
                val keys = IntArray(count) { mortonKey(sorted[it * 4], sorted[it * 4 + 1], widthBits, heightBits) }
                for (i in 1 until count) assertTrue(keys[i - 1] <= keys[i], "key order at $i: ${keys[i - 1]} > ${keys[i]}")
                assertTrue(keys.toSet().size > 1000, "the scene spans many tiles")
                // The scatter counts every tile back to zero for the next sort.
                val tiles = 1 shl (widthBits + heightBits)
                assertTrue(CollisionGpuAccess.ints(pipeline, "sortCounts", tiles).all { it == 0 })
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
    }

    @Test
    fun `reordering invalidates cached neighbor lists`() {
        CollisionGpuContext().use {
            val random = Random(7)
            val count = 256
            // Touching pairs far apart, stored in shuffled order: the cache
            // reuses its lists until the IDs change.
            val input = scene(count) { i ->
                floatArrayOf(40f + (i / 2 % 32) * 50f + (i % 2) * 1.0f, 100f + (i / 64) * 50f, 0f, 0f)
            }.shuffledBodies(random)
            GravityPipeline(config(count, sortInterval = 1), width, height).use { pipeline ->
                CollisionGpuAccess.upload(pipeline, input)
                repeat(3) { CollisionGpuAccess.collide(pipeline, count) }
                val rebuilds = CollisionGpuAccess.rebuilds(pipeline)
                assertEquals(1, rebuilds, "stationary pairs reuse their lists")
                CollisionGpuAccess.sort(pipeline, count)
                repeat(3) { pass ->
                    val current = CollisionGpuAccess.floats(pipeline, "bodies", count * 4)
                    CollisionGpuAccess.collide(pipeline, count)
                    assertContacts("after sort/$pass", pipeline, current)
                }
                assertEquals(rebuilds + 1, CollisionGpuAccess.rebuilds(pipeline), "exactly one rebuild for the new IDs")
            }
        }
    }

    @Test
    fun `grid epoch wrap restarts from empty lists without losing contacts`() {
        CollisionGpuContext().use {
            val count = 64
            GravityPipeline(config(count, sortInterval = 0), width, height).use { pipeline ->
                val maximum = CollisionGpuAccess.setEpoch(pipeline, 0)
                CollisionGpuAccess.setEpoch(pipeline, maximum - 3)
                var input = scene(count) { i -> floatArrayOf(500f + (i % 8) * 1.05f, 500f + (i / 8) * 1.05f, 0f, 0f) }
                repeat(8) { pass ->
                    CollisionGpuAccess.upload(pipeline, input)
                    CollisionGpuAccess.collide(pipeline, count)
                    assertContacts("wrap/$pass", pipeline, input)
                    // Move every column beyond half the skin: each pass rebuilds
                    // over stale heads of the previous epochs.
                    input = CollisionGpuAccess.floats(pipeline, "bodies", count * 4).also { bodies ->
                        for (i in 0 until count) bodies[i * 4] += (if (i % 2 == 0) 0.3f else -0.3f) / width
                    }
                }
                assertTrue(CollisionGpuAccess.epoch(pipeline) in 1..5, "the epoch wrapped")
                // The first pass and the pass after the wrap must rebuild.
                assertTrue(CollisionGpuAccess.rebuilds(pipeline) >= 2)
            }
        }
    }

    @Test
    fun `steps with and without reordering give the same bodies bit for bit`() {
        CollisionGpuContext().use {
            val random = Random(99)
            val count = 2048
            // Isolated pairs that collide head-on: one contact per body keeps
            // every correction a single term, so no float sum is reordered.
            // Mutual gravity is off; its exact near field takes the first
            // bodies of a linked list, which depends on IDs by design.
            val input = scene(count) { i ->
                val pair = i / 2
                val side = if (i % 2 == 0) -1f else 1f
                floatArrayOf(
                    30f + (pair % 64) * 50f + side * 1.5f,
                    30f + (pair / 64) * 40f,
                    -side * 40f,
                    (pair % 7) * 3f,
                )
            }.shuffledBodies(random)
            val results = listOf(0, 1).map { interval ->
                GravityPipeline(config(count, sortInterval = interval), width, height).use { pipeline ->
                    pipeline.mutualGravity = false
                    pipeline.fieldAccelerationY = -300f
                    CollisionGpuAccess.upload(pipeline, input)
                    pipeline.initializeAcceleration(count)
                    repeat(12) { pipeline.step(count) }
                    val bodies = CollisionGpuAccess.floats(pipeline, "bodies", count * 4)
                    val pressures = CollisionGpuAccess.floats(pipeline, "pressures", count)
                    records(bodies, pressures, 1)
                }
            }
            assertContentEquals(results[0], results[1])
            // Head-on at 40 px/s each; the inelastic hit leaves a few px/s.
            assertTrue(results[0].count { kotlin.math.abs(Float.fromBits(it[2].toInt()) * width) < 10f } > count / 2, "pairs must actually collide")
        }
    }

    private fun assertContacts(label: String, pipeline: GravityPipeline, input: FloatArray) {
        val count = input.size / 4
        val expected = bruteForceContacts(input)
        val counts = CollisionGpuAccess.ints(pipeline, "collisionContactCounts", count)
        assertContentEquals(expected.map { it.size }.toIntArray(), counts, "$label contact counts")
        val cached = CollisionGpuAccess.cachedContacts(pipeline, count, count)
        for (id in 0 until count) {
            assertContentEquals(expected[id].sorted().toIntArray(), cached[id].sortedArray(), "$label body $id contact IDs")
        }
    }

    private fun bruteForceContacts(input: FloatArray): Array<MutableList<Int>> {
        val count = input.size / 4
        val result = Array(count) { mutableListOf<Int>() }
        for (i in 0 until count) for (j in i + 1 until count) {
            var dx = input[i * 4] * width - input[j * 4] * width
            var dy = input[i * 4 + 1] * height - input[j * 4 + 1] * height
            dx -= floor(dx / width + 0.5f) * width
            dy -= floor(dy / height + 0.5f) * height
            if (dx * dx + dy * dy < diameter * diameter) { result[i].add(j); result[j].add(i) }
        }
        return result
    }

    /** Per-body records of raw float bits, sorted: equal lists = same multiset. */
    private fun records(bodies: FloatArray, extra: FloatArray, extraPerBody: Int = 2): List<List<Long>> =
        List(bodies.size / 4) { i ->
            (0..3).map { bodies[i * 4 + it].toRawBits().toLong() } +
                (0 until extraPerBody).map { extra[i * extraPerBody + it].toRawBits().toLong() }
        }.sortedWith { a, b ->
            a.indices.firstOrNull { a[it] != b[it] }?.let { a[it].compareTo(b[it]) } ?: 0
        }

    private fun mortonKey(x: Float, y: Float, widthBits: Int, heightBits: Int): Int {
        val tileX = floor(x * (1 shl widthBits).toFloat()).toInt().coerceIn(0, (1 shl widthBits) - 1)
        val tileY = floor(y * (1 shl heightBits).toFloat()).toInt().coerceIn(0, (1 shl heightBits) - 1)
        val common = min(widthBits, heightBits)
        var key = 0
        for (bit in 0 until common) {
            key = key or (((tileX shr bit) and 1) shl (2 * bit)) or (((tileY shr bit) and 1) shl (2 * bit + 1))
        }
        return key or (((tileX or tileY) shr common) shl (2 * common))
    }

    private fun FloatArray.shuffledBodies(random: Random): FloatArray {
        val order = (0 until size / 4).shuffled(random)
        return FloatArray(size) { this[order[it / 4] * 4 + it % 4] }
    }

    private fun config(count: Int, sortInterval: Int) = GravityConfig(
        particles = ParticleConfig(capacity = count, initialBodies = count, seed = 7235, spatialSortInterval = sortInterval),
        gravity = GravityFieldConfig(gridWidth = 128, gridHeight = 128),
    )

    private fun scene(count: Int, body: (Int) -> FloatArray): FloatArray = FloatArray(count * 4).also { data ->
        repeat(count) { i ->
            val value = body(i)
            for (j in 0..3) data[i * 4 + j] = value[j] / if (j % 2 == 0) width else height
        }
    }
}
