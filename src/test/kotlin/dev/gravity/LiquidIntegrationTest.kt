package dev.gravity

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

class LiquidIntegrationTest {
    private val width = 256
    private val height = 256
    private val diameter = 1.12838f

    @Test
    fun `a collapsing liquid column settles into a level pool`() {
        // 40 x 120 px of liquid spread over the 256 px floor is 19 px deep.
        val column = lattice(minX = 0f, maxX = 40f, maxY = 120f) { _, _ -> true }
        val pool = settle(column)
        println("column 40x120: liquid top=${pool.first} px width=${pool.second} px")
        assertTrue(pool.second > 240f, "liquid covers the floor: ${pool.second} px")
        assertTrue(pool.first < 25f, "liquid levels out: ${pool.first} px")
    }

    @Test
    fun `a light block rises from the bottom of a liquid`() {
        val travel = blockTravel(density = 0.5f, minY = 12f, maxY = 42f)
        assertTrue(travel > 60f, "rises: $travel px")
    }

    @Test
    fun `a heavy block sinks to the bottom of a liquid`() {
        val travel = blockTravel(density = 3f, minY = 110f, maxY = 140f)
        assertTrue(travel < -60f, "sinks: $travel px")
    }

    @Test
    fun `deep and self-gravitating liquid at rest stays calm`() {
        // Both used to stir themselves up: the pool bounced off its floor, the
        // planet's pressed core never settled with too few iterations.
        val poolSize = 512
        val pool = calmLattice(poolSize, poolSize) { _, y -> y <= 200f }
        val poolSpeed = restlessness(pool, poolSize, poolSize, bounded = true)
        val planetSize = 1024
        val planet = calmLattice(planetSize, planetSize) { x, y ->
            val dx = x - planetSize / 2f
            val dy = y - planetSize / 2f
            dx * dx + dy * dy <= 130f * 130f
        }
        val planetSpeed = restlessness(planet, planetSize, planetSize, bounded = false)
        println("restlessness: pool rms=${poolSpeed.first} p99=${poolSpeed.second}; planet rms=${planetSpeed.first} p99=${planetSpeed.second} px/s")
        assertTrue(poolSpeed.first < 10.0 && poolSpeed.second < 40.0, "pool: $poolSpeed px/s")
        assertTrue(planetSpeed.first < 15.0 && planetSpeed.second < 40.0, "planet: $planetSpeed px/s")
    }

    /** RMS and 99th percentile speed (px/s) after six seconds. */
    private fun restlessness(bodies: FloatArray, viewWidth: Int, viewHeight: Int, bounded: Boolean): Pair<Double, Double> {
        val count = bodies.size / 4
        var result = Double.NaN to Double.NaN
        CollisionGpuContext().use {
            GravityPipeline(config(count, count, density = 1f), viewWidth, viewHeight, bounded = bounded).use { pipeline ->
                // The pool sits under the field, the planet under its own gravity.
                pipeline.mutualGravity = !bounded
                pipeline.liquid = true
                if (bounded) pipeline.fieldAccelerationY = -300f
                CollisionGpuAccess.upload(pipeline, bodies)
                pipeline.initializeAcceleration(count)
                repeat(720) { pipeline.step(count) }
                val state = CollisionGpuAccess.floats(pipeline, "bodies", count * 4)
                val speeds = (0 until count).map {
                    val vx = state[it * 4 + 2] * viewWidth.toDouble()
                    val vy = state[it * 4 + 3] * viewHeight.toDouble()
                    sqrt(vx * vx + vy * vy)
                }.sorted()
                result = sqrt(speeds.sumOf { it * it } / count) to speeds[count * 99 / 100]
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
        return result
    }

    private fun calmLattice(viewWidth: Int, viewHeight: Int, keep: (Float, Float) -> Boolean): FloatArray {
        val rowStep = diameter * sqrt(3f) * 0.5f
        val values = ArrayList<Float>()
        var row = 0
        var y = 0.5f * diameter
        while (y <= viewHeight - diameter) {
            var x = 0.5f * diameter + if (row % 2 == 0) 0f else 0.5f * diameter
            while (x <= viewWidth - 0.5f * diameter) {
                if (keep(x, y)) values += listOf(x / viewWidth, y / viewHeight, 0f, 0f)
                x += diameter
            }
            y += rowStep
            row++
        }
        return values.toFloatArray()
    }

    @Test
    fun `coincident liquid bodies separate`() {
        // A wall corner clamps several bodies onto one point. They used to have
        // no direction between them, stay merged and fly off as one red dot.
        val count = 5
        val bodies = FloatArray(count * 4) { i ->
            when (i % 4) {
                0 -> 100f / width
                1 -> 100f / height
                2 -> 30f / width
                else -> -20f / height
            }
        }
        CollisionGpuContext().use {
            GravityPipeline(config(count, count, density = 1f), width, height).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.liquid = true
                CollisionGpuAccess.upload(pipeline, bodies)
                pipeline.initializeAcceleration(count)
                repeat(30) { pipeline.step(count) }
                val state = CollisionGpuAccess.floats(pipeline, "bodies", count * 4)
                val pressures = CollisionGpuAccess.floats(pipeline, "pressures", count)
                var closest = Float.MAX_VALUE
                for (i in 0 until count) for (j in i + 1 until count) {
                    val dx = (state[i * 4] - state[j * 4]) * width
                    val dy = (state[i * 4 + 1] - state[j * 4 + 1]) * height
                    closest = minOf(closest, sqrt(dx * dx + dy * dy))
                }
                println("coincident: closest pair=$closest px pressures=${pressures.toList()}")
                assertTrue(closest > 0.5f * diameter, "bodies separate: closest pair $closest px")
                assertTrue(pressures.all { it == 0f }, "separated bodies are not compressed: ${pressures.toList()}")
                // Momentum stays with the clump.
                assertEquals(count * 30f, (0 until count).sumOf { state[it * 4 + 2] * width.toDouble() }.toFloat(), 1e-2f)
                assertEquals(count * -20f, (0 until count).sumOf { state[it * 4 + 3] * height.toDouble() }.toFloat(), 1e-2f)
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
    }

    @Test
    fun `liquid keeps momentum`() {
        val random = Random(3)
        val count = 20_000
        // A blob of liquid on the torus, gravity and field off, stirred at random.
        val bodies = FloatArray(count * 4) { i ->
            when (i % 4) {
                0, 1 -> 0.3f + random.nextFloat() * 0.4f
                else -> (random.nextFloat() - 0.5f) * 40f / width
            }
        }
        CollisionGpuContext().use {
            GravityPipeline(config(count + 4096, count, density = 0.5f), width, height).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.liquid = true
                CollisionGpuAccess.upload(pipeline, bodies)
                val block = pipeline.spawnBlock(count, 20f, 20f, 50f, 40f)
                val active = count + block
                val before = momentum(pipeline, active)
                pipeline.initializeAcceleration(active)
                repeat(120) { pipeline.step(active) }
                val after = momentum(pipeline, active)
                println("momentum before=${before.toList()} after=${after.toList()}")
                assertEquals(before[0], after[0], 1e-3 * after[2])
                assertEquals(before[1], after[1], 1e-3 * after[2])
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
    }

    /** Total momentum (x, y) and the sum of |m v| as a scale, in px/s. */
    private fun momentum(pipeline: GravityPipeline, active: Int): DoubleArray {
        val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
        val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
        val result = DoubleArray(3)
        for (id in 0 until active) {
            val mass = materials[id * 4].toDouble()
            val vx = bodies[id * 4 + 2] * width.toDouble()
            val vy = bodies[id * 4 + 3] * height.toDouble()
            result[0] += mass * vx
            result[1] += mass * vy
            result[2] += mass * sqrt(vx * vx + vy * vy)
        }
        return result
    }

    /** Top (98th percentile height) and horizontal extent of the settled bodies. */
    private fun settle(bodies: FloatArray): Pair<Float, Float> {
        val count = bodies.size / 4
        var result = Float.NaN to Float.NaN
        CollisionGpuContext().use {
            GravityPipeline(config(count, count, density = 1f), width, height, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.liquid = true
                pipeline.fieldAccelerationY = -300f
                CollisionGpuAccess.upload(pipeline, bodies)
                pipeline.initializeAcceleration(count)
                // The splash sloshes for several seconds before it calms down.
                repeat(1200) { pipeline.step(count) }
                val state = CollisionGpuAccess.floats(pipeline, "bodies", count * 4)
                val ys = (0 until count).map { state[it * 4 + 1] * height }.sorted()
                val xs = (0 until count).map { state[it * 4] * width }.sorted()
                result = ys[count * 98 / 100] to xs[count * 99 / 100] - xs[count / 100]
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
        return result
    }

    /** Vertical travel of a block that starts inside a liquid at rest. */
    private fun blockTravel(density: Float, minY: Float, maxY: Float): Float {
        val minX = 108f
        val maxX = 148f
        val bed = lattice(0f, width.toFloat(), 150f) { x, y ->
            x < minX - diameter || x > maxX + diameter || y < minY - diameter || y > maxY + diameter
        }
        val count = bed.size / 4
        var travel = Float.NaN
        CollisionGpuContext().use {
            GravityPipeline(config(count + 4096, count, density), width, height, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.liquid = true
                pipeline.fieldAccelerationY = -300f
                CollisionGpuAccess.upload(pipeline, bed)
                val block = pipeline.spawnBlock(count, minX, minY, maxX, maxY)
                val active = count + block
                val start = blockCenterY(pipeline, active)
                pipeline.initializeAcceleration(active)
                val trace = StringBuilder("density=$density:")
                for (second in 1..5) {
                    repeat(120) { pipeline.step(active) }
                    trace.append(" t$second=%.0f".format(blockCenterY(pipeline, active)))
                }
                println(trace)
                travel = blockCenterY(pipeline, active) - start
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
        return travel
    }

    private fun blockCenterY(pipeline: GravityPipeline, active: Int): Float {
        val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
        val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
        return (0 until active).filter { materials[it * 4 + 2] != 0f }
            .map { bodies[it * 4 + 1] * height }.average().toFloat()
    }

    /** Hexagonally packed bodies at rest in [minX, maxX] x [0, maxY]. */
    private fun lattice(minX: Float, maxX: Float, maxY: Float, keep: (Float, Float) -> Boolean): FloatArray {
        val rowStep = diameter * sqrt(3f) * 0.5f
        val values = ArrayList<Float>()
        var row = 0
        var y = 0.5f * diameter
        while (y <= maxY) {
            var x = minX + 0.5f * diameter + if (row % 2 == 0) 0f else 0.5f * diameter
            while (x <= maxX - 0.5f * diameter) {
                if (keep(x, y)) values += listOf(x / width, y / height, 0f, 0f)
                x += diameter
            }
            y += rowStep
            row++
        }
        return values.toFloatArray()
    }

    private fun config(capacity: Int, count: Int, density: Float) = GravityConfig(
        particles = ParticleConfig(capacity = capacity, initialBodies = count, seed = 7235),
        gravity = GravityFieldConfig(gridWidth = 128, gridHeight = 128),
        projectiles = ProjectileConfig(blockDensity = density),
    )
}
