package dev.gravity

import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferSubData
import org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Records the result of one liquid solve and of liquid-mode collision passes
 * on fixed dense states, then compares a later revision against it.
 * `record` also solves twice to show the run-to-run noise of the liquid.
 */
object LiquidEquivalenceCheck {
    private const val SIZE = 512
    private val output = Path.of("build", "liquid-equivalence")

    @JvmStatic
    fun main(args: Array<String>) {
        val record = args.getOrNull(0) == "record"
        Files.createDirectories(output)
        CollisionGpuContext().use {
            for ((bounded, blocks) in listOf(true to true, false to true, true to false)) {
                val name = (if (bounded) "walls" else "torus") + if (blocks) "" else "-fluid"
                val (bodies, materials) = state(bounded, blocks)
                val count = bodies.size / 4
                val liquid = run(bodies, materials, bounded) { pipeline -> invoke(pipeline, "resolveLiquid", count) }
                val collide = run(bodies, materials, bounded) { pipeline ->
                    repeat(4) { CollisionGpuAccess.collide(pipeline, count, drift = 1f / 120f / 16f, kick = if (it == 0) 1f / 120f else 0f, pressure = it == 3, substep = it) }
                }
                if (record) {
                    save("$name-liquid", liquid)
                    save("$name-collide", collide)
                    repeat(3) {
                        val again = run(bodies, materials, bounded) { pipeline -> invoke(pipeline, "resolveLiquid", count) }
                        report("$name liquid noise (same code)", liquid, again, count)
                    }
                    val again = run(bodies, materials, bounded) { pipeline ->
                        repeat(4) { CollisionGpuAccess.collide(pipeline, count, drift = 1f / 120f / 16f, kick = if (it == 0) 1f / 120f else 0f, pressure = it == 3, substep = it) }
                    }
                    report("$name collide noise (same code)", collide, again, count)
                } else {
                    report("$name liquid", load("$name-liquid"), liquid, count)
                    report("$name collide", load("$name-collide"), collide, count)
                }
            }
        }
    }

    /** Dense compressed liquid with two blocks, a gas above and a coincident clump. */
    private fun state(bounded: Boolean, blocks: Boolean): Pair<FloatArray, FloatArray> {
        val random = Random(if (bounded) 11 else 12)
        val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS
        val spacing = diameter * 0.9f
        val rowStep = spacing * sqrt(3f) * 0.5f
        val bodies = ArrayList<Float>()
        val materials = ArrayList<Float>()
        fun add(x: Float, y: Float, vx: Float, vy: Float, mass: Float, solid: Boolean) {
            bodies += listOf(x / SIZE, y / SIZE, vx / SIZE, vy / SIZE)
            materials += listOf(mass, 0f, if (solid) 1f else 0f, 0f)
        }
        var row = 0
        var y = 0.5f * diameter
        while (y < 200f) {
            var x = 0.5f * diameter + if (row % 2 == 0) 0f else 0.5f * spacing
            while (x < SIZE - 0.5f * diameter) {
                val light = blocks && x in 100f..160f && y in 40f..80f
                val heavy = blocks && x in 300f..340f && y in 120f..150f
                val jitter = if (light || heavy) 0f else 0.15f
                add(
                    x + (random.nextFloat() - 0.5f) * jitter, y + (random.nextFloat() - 0.5f) * jitter,
                    (random.nextFloat() - 0.5f) * 60f, (random.nextFloat() - 0.5f) * 60f,
                    if (light) 0.5f else if (heavy) 3f else 1f, light || heavy,
                )
                x += spacing
            }
            y += rowStep
            row++
        }
        repeat(8_000) {
            add(random.nextFloat() * SIZE, 220f + random.nextFloat() * 250f,
                (random.nextFloat() - 0.5f) * 400f, (random.nextFloat() - 0.5f) * 400f, 1f, false)
        }
        repeat(6) { add(1f, 1f, 30f, -20f, 1f, false) }
        return bodies.toFloatArray() to materials.toFloatArray()
    }

    private fun run(bodies: FloatArray, materials: FloatArray, bounded: Boolean, action: (GravityPipeline) -> Unit): FloatArray {
        val count = bodies.size / 4
        val config = GravityConfig(
            particles = ParticleConfig(capacity = count, initialBodies = count, seed = 1, spatialSortInterval = 0),
            gravity = GravityFieldConfig(gridWidth = 128, gridHeight = 128),
        )
        var result = FloatArray(0)
        GravityPipeline(config, SIZE, SIZE, bounded = bounded).use { pipeline ->
            pipeline.mutualGravity = false
            pipeline.liquid = true
            CollisionGpuAccess.upload(pipeline, bodies)
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, CollisionGpuAccess.buffer(pipeline, "materials"))
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, materials)
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
            // Bodies made solid behind the pipeline's back, as spawnBlock would.
            GravityPipeline::class.java.declaredFields.firstOrNull { it.name == "solidBodies" }?.let {
                it.isAccessible = true
                it.setBoolean(pipeline, (0 until count).any { id -> materials[id * 4 + 2] != 0f })
            }
            action(pipeline)
            result = CollisionGpuAccess.floats(pipeline, "bodies", count * 4) +
                CollisionGpuAccess.floats(pipeline, "pressures", count)
        }
        return result
    }

    private fun invoke(pipeline: GravityPipeline, method: String, count: Int) {
        val field = GravityPipeline::class.java.getDeclaredField("boundStorageBuffers")
        field.isAccessible = true
        (field.get(pipeline) as IntArray).fill(-1)
        val target = GravityPipeline::class.java.declaredMethods.single { it.name == method }
        target.isAccessible = true
        target.invoke(pipeline, count)
    }

    private fun report(label: String, expected: FloatArray, actual: FloatArray, count: Int) {
        require(expected.size == actual.size)
        var position = 0.0
        var velocity = 0.0
        var speed = 0.0
        var pressure = 0.0
        var pressureScale = 0.0
        var exact = 0
        var positionSquares = 0.0
        var velocitySquares = 0.0
        for (id in 0 until count) {
            val base = id * 4
            var same = true
            for (k in 0 until 4) {
                val difference = abs(expected[base + k].toDouble() - actual[base + k]) * SIZE
                if (k < 2) {
                    position = max(position, difference)
                    positionSquares += difference * difference
                } else {
                    velocity = max(velocity, difference)
                    velocitySquares += difference * difference
                }
                if (expected[base + k].toRawBits() != actual[base + k].toRawBits()) same = false
            }
            speed = max(speed, abs(expected[base + 2].toDouble()) * SIZE)
            val p = count * 4 + id
            pressure = max(pressure, abs(expected[p].toDouble() - actual[p]))
            pressureScale = max(pressureScale, abs(expected[p].toDouble()))
            if (expected[p].toRawBits() != actual[p].toRawBits()) same = false
            if (same) exact++
        }
        println(
            "%-30s bit-exact=%6d/%d  max|dx|=%.2e rms=%.2e px  max|dv|=%.2e rms=%.2e px/s (max speed %.0f)  max|dp|=%.2e (max p %.0f)"
                .format(java.util.Locale.ROOT, label, exact, count, position, sqrt(positionSquares / count), velocity, sqrt(velocitySquares / count), speed, pressure, pressureScale),
        )
    }

    private fun save(name: String, values: FloatArray) {
        DataOutputStream(Files.newOutputStream(output.resolve("$name.bin")).buffered()).use { stream ->
            stream.writeInt(values.size)
            values.forEach(stream::writeFloat)
        }
    }

    private fun load(name: String): FloatArray =
        DataInputStream(Files.newInputStream(output.resolve("$name.bin")).buffered()).use { stream ->
            FloatArray(stream.readInt()) { stream.readFloat() }
        }
}
