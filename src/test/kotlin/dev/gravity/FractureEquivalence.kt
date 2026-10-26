package dev.gravity

import org.lwjgl.opengl.GL11.glFinish
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

/**
 * Records or compares one fracture pass (rigid_fracture.comp) on fixed
 * impacts: cracks of several budgets and a crater of chips in a big block.
 * Which members break must match exactly; the carved shape and the block's
 * new state to float rounding.
 *
 * Arguments: record|compare, file.
 */
object FractureEquivalence {
    private const val WIDTH = 1920
    private const val HEIGHT = 1080

    private class Case(val name: String, val nucleusX: Float, val nucleusY: Float, val directionX: Float, val directionY: Float,
        val budget: Float, val chipRadius: Float)

    private val cases = listOf(
        Case("long crack", -195f, 0f, 1f, 0.1f, 2000f, 0f),
        Case("short crack", -195f, 40f, 1f, -0.3f, 60f, 0f),
        Case("inner crack", 0f, 0f, 0.3f, 1f, 400f, 0f),
        Case("crater", -195f, -20f, 0f, 0f, 0f, 14f),
        Case("crater and crack", -195f, 20f, 1f, 0f, 800f, 10f),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        val file = Path.of(args[1])
        val results = ArrayList<Pair<String, FloatArray>>()
        CollisionGpuContext().use {
            for (case in cases) results += case.name to run(case)
        }
        if (mode == "record") {
            DataOutputStream(Files.newOutputStream(file)).use { out ->
                out.writeInt(results.size)
                for ((name, values) in results) {
                    out.writeUTF(name)
                    out.writeInt(values.size)
                    values.forEach(out::writeFloat)
                }
            }
            println("Recorded ${results.size} cases to $file")
            return
        }
        var failed = false
        DataInputStream(Files.newInputStream(file)).use { input ->
            repeat(input.readInt()) { index ->
                val name = input.readUTF()
                val expected = FloatArray(input.readInt()) { input.readFloat() }
                val actual = results[index].second
                check(name == results[index].first)
                // Layout: alive flags, then shape values, then the group.
                val members = expected[0].toInt()
                val cells = expected[1].toInt()
                var memberMismatches = 0
                for (k in 0 until members) if (expected[2 + k] != actual[2 + k]) memberMismatches++
                var shapeDifference = 0.0
                for (k in 0 until cells) shapeDifference = max(shapeDifference, abs(expected[2 + members + k] - actual[2 + members + k]).toDouble())
                var groupDifference = 0.0
                for (k in 2 + members + cells until expected.size) groupDifference = max(groupDifference, abs(expected[k] - actual[k]).toDouble())
                val broken = (0 until members).count { expected[2 + it] == 0f }
                val ok = memberMismatches == 0 && shapeDifference <= 1e-4 && groupDifference <= 1e-3
                if (!ok) failed = true
                println("%-18s broken=%d memberMismatches=%d shapeDiff=%.3g groupDiff=%.3g %s".format(
                    name, broken, memberMismatches, shapeDifference, groupDifference, if (ok) "OK" else "DIFFERENT"))
            }
        }
        check(!failed) { "Fracture pass differs from the record" }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(target)
    }

    private fun run(case: Case): FloatArray {
        val config = GravityConfig(particles = ParticleConfig(capacity = 120_000, initialBodies = 1, seed = 1, spatialSortInterval = 0))
        val parked = floatArrayOf(2f / WIDTH, 1f - 2f / HEIGHT, 0f, 0f)
        GravityPipeline(config, WIDTH, HEIGHT, null, parked, bounded = true).use { pipeline ->
            pipeline.mutualGravity = false
            val cx = WIDTH * 0.5f
            val cy = HEIGHT * 0.5f
            val count = pipeline.spawnBlock(1, cx - 200f, cy - 100f, cx + 200f, cy + 100f, 0.5f)
            val blocks = CollisionGpuAccess.rigidBlocks(pipeline)
            // A pass with nothing broken first: drivers compile on first use.
            val warm = blocks.javaClass.getDeclaredMethod("fracturePass", Int::class.java, Int::class.java)
            warm.isAccessible = true
            (field(pipeline, "boundStorageBuffers") as IntArray).fill(-1)
            warm.invoke(blocks, CollisionGpuAccess.buffer(pipeline, "bodies"), CollisionGpuAccess.buffer(pipeline, "materials"))
            blocks.javaClass.getDeclaredField("passes").let { it.isAccessible = true; it.setInt(blocks, 0) }
            glFinish()
            val membersBuffer = field(blocks, "members") as Int
            val restBuffer = field(blocks, "rest") as Int
            val slotsBuffer = field(blocks, "fractureSlots") as Int
            val groupsFlags = field(blocks, "fractureGroups") as Int
            val brokenBuffer = field(blocks, "brokenList") as Int
            val entries = (count + 255) / 256 * 256
            val rest = CollisionGpuAccess.floats(restBuffer, entries * 2)
            fun nearest(x: Float, y: Float) = (0 until count).minBy { (rest[2 * it] - x).let { d -> d * d } + (rest[2 * it + 1] - y).let { d -> d * d } }
            val nucleus = nearest(case.nucleusX, case.nucleusY)
            val chips = if (case.chipRadius > 0f) (0 until count).filter {
                val dx = rest[2 * it] - rest[2 * nucleus]
                val dy = rest[2 * it + 1] - rest[2 * nucleus + 1]
                dx * dx + dy * dy < case.chipRadius * case.chipRadius
            } else emptyList()
            val slots = CollisionGpuAccess.floats(slotsBuffer, entries * 4)
            for (chip in chips) {
                slots[4 * chip + 1] = 1f + 100f
                slots[4 * chip + 2] = 1f
            }
            slots[4 * nucleus + 2] = case.directionX
            slots[4 * nucleus + 3] = case.directionY
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, slotsBuffer)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, slots)
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, brokenBuffer)
            // Per group counts (broken, moved), then each group's list in its own slots.
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, IntArray(2 * RigidBlocks.MAXIMUM_GROUPS).also { it[0] = chips.size } + chips.toIntArray())
            val budget = (case.budget * 16f).toInt() + chips.size * 16
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, groupsFlags)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, intArrayOf(if (case.budget > 0f) budget else 0, if (case.budget > 0f) (1 shl 24) or nucleus else 0, chips.size, 0))
            // The pass runs as an impact of a collision pass would start it (rigid_apply.comp).
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, field(blocks, "fractureDispatch") as Int)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, intArrayOf(1, 1, 1, field(blocks, "tileCount") as Int, 1, 1))
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
            val bodies = CollisionGpuAccess.buffer(pipeline, "bodies")
            val materials = CollisionGpuAccess.buffer(pipeline, "materials")
            val pass = blocks.javaClass.getDeclaredMethod("fracturePass", Int::class.java, Int::class.java)
            pass.isAccessible = true
            (field(pipeline, "boundStorageBuffers") as IntArray).fill(-1)
            glFinish()
            val start = System.nanoTime()
            pass.invoke(blocks, bodies, materials)
            glFinish()
            println("%-18s fracture pass %.3f ms".format(case.name, (System.nanoTime() - start) / 1e6))
            val members = CollisionGpuAccess.ints(membersBuffer, count)
            val groupFloats = CollisionGpuAccess.floats(blocks.groups, 40)
            val first = groupFloats[20].toRawBits()
            val cells = groupFloats[21].toRawBits() * groupFloats[22].toRawBits()
            val shapesOffset = (256 * 160 + 5 * 256 * 260 * 4) / 4
            val shape = CollisionGpuAccess.floats(blocks.groups, shapesOffset + first + cells).copyOfRange(shapesOffset + first, shapesOffset + first + cells)
            return floatArrayOf(count.toFloat(), cells.toFloat()) +
                FloatArray(count) { if (members[it] >= 0) 1f else 0f } + shape + groupFloats.copyOfRange(0, 20)
        }
    }
}
