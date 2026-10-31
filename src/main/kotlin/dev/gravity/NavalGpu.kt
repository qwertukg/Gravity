package dev.gravity

import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL30.GL_R32UI
import org.lwjgl.opengl.GL30.GL_RED_INTEGER
import org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER
import org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER
import org.lwjgl.opengl.GL31.glCopyBufferSubData
import org.lwjgl.opengl.GL32.GL_SYNC_FLUSH_COMMANDS_BIT
import org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE
import org.lwjgl.opengl.GL32.glClientWaitSync
import org.lwjgl.opengl.GL32.glDeleteSync
import org.lwjgl.opengl.GL32.glFenceSync
import org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glClearBufferData
import org.lwjgl.opengl.GL43.glDispatchCompute
import org.lwjgl.opengl.GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT
import java.nio.ByteBuffer

/** A shell in flight as the GPU last reported it: its bodies, centre (px) and mean velocity (px/s). */
data class ShellReading(val bodies: Int, val x: Float, val y: Float, val velocityX: Float, val velocityY: Float)

/**
 * A ship (block) as the GPU last reported it: centre (px), velocity (px/s),
 * rotation, mass (members) and the move of its rest frame since it was glued
 * (the origin of its shape, px: RigidBlocks).
 */
data class ShipReading(
    val x: Float,
    val y: Float,
    val velocityX: Float,
    val velocityY: Float,
    val cos: Float,
    val sin: Float,
    val mass: Float,
    val shapeX: Float,
    val shapeY: Float,
)

/** Everything a probe reports, a step old. */
class NavalReading(val shells: List<ShellReading?>, val ships: List<ShipReading>)

/**
 * The GPU side of the naval battle (NavalBattle): ship engines, shells and
 * their detonation, and the probe that tells the CPU where ships and shells
 * are. Shells are loose solid bodies whose material names them
 * (naval_layout.glsl), so they follow spatial sorting like any body. The probe
 * is copied out behind a fence and read one frame later, with the next step
 * already queued, so the GPU never waits for the CPU.
 */
internal class NavalGpu(
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val bind: (Int, Int) -> Unit,
) : AutoCloseable {
    private val push = GlProgram.compute("/shaders/naval_push.comp")
    private val probe = GlProgram.compute("/shaders/naval_probe.comp")
    private val detonate = GlProgram.compute("/shaders/naval_detonate.comp")
    private val sums = glGenBuffers().also {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
        glBufferData(GL_SHADER_STORAGE_BUFFER, SUM_BYTES, GL_DYNAMIC_DRAW)
    }
    private val copies = Array(2) { RigidBlocks.Readback(READBACK_BYTES) }
    private val fences = LongArray(2)
    /** Ships each copy holds. */
    private val copiedShips = IntArray(2)
    private var probes = 0

    init {
        listOf(probe, detonate).forEach {
            it.use()
            it.vec2("uViewport", viewportWidth.toFloat(), viewportHeight.toFloat())
        }
    }

    /** Adds ([deltaX], [deltaY]) px/s to every member of block [group]. */
    fun push(bodies: Int, collisionBodies: Int, materials: Int, activeBodies: Int, group: Int, deltaX: Float, deltaY: Float) {
        push.use()
        push.int("uCount", activeBodies)
        push.float("uGroup", group.toFloat())
        push.vec2("uDelta", deltaX / viewportWidth, deltaY / viewportHeight)
        bind(0, bodies)
        bind(1, collisionBodies)
        bind(2, materials)
        glDispatchCompute((activeBodies + 255) / 256, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
    }

    /** Sets shell [shell] off: its bodies fly apart at up to [speed] px/s from its centre, [radius] px out. */
    fun detonate(bodies: Int, collisionBodies: Int, materials: Int, activeBodies: Int, shell: Int, radius: Float, speed: Float) {
        sum(bodies, materials, activeBodies)
        detonate.use()
        detonate.int("uCount", activeBodies)
        detonate.int("uShell", shell)
        detonate.float("uRadius", radius)
        detonate.float("uSpeed", speed)
        bind(0, bodies)
        bind(1, collisionBodies)
        bind(2, materials)
        bind(3, sums)
        glDispatchCompute((activeBodies + 255) / 256, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
    }

    /**
     * Sums the shells and copies them with the state of [ships] (block
     * indices in [blockStates], RigidBlocks.groups) for the CPU; returns the
     * copy of the previous call, or null on the first.
     */
    fun probe(bodies: Int, materials: Int, activeBodies: Int, blockStates: Int, ships: IntArray): NavalReading? {
        require(ships.size <= MAXIMUM_SHIPS)
        sum(bodies, materials, activeBodies)
        val slot = probes % copies.size
        val target = copies[slot]
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        copy(sums, target.id, 0L, 0L, SUM_BYTES)
        ships.forEachIndexed { index, group ->
            copy(blockStates, target.id, group.toLong() * GROUP_BYTES, SUM_BYTES + index.toLong() * GROUP_BYTES, GROUP_BYTES)
        }
        if (target.persistent) glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT)
        fences[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        copiedShips[slot] = ships.size
        probes++
        val previous = probes % copies.size
        if (fences[previous] == 0L) return null
        glClientWaitSync(fences[previous], GL_SYNC_FLUSH_COMMANDS_BIT, Long.MAX_VALUE)
        glDeleteSync(fences[previous])
        fences[previous] = 0L
        return read(copies[previous], copiedShips[previous])
    }

    private fun read(copy: RigidBlocks.Readback, ships: Int): NavalReading {
        val sum = copy.ints(0L, SHELLS * SHELL_SUM_INTS)
        val shells = List(SHELLS) { shell ->
            val base = shell * SHELL_SUM_INTS
            val count = sum[base]
            if (count == 0) null else {
                val scale = 1f / (SHELL_SUM_SCALE * count)
                ShellReading(count, sum[base + 1] * scale, sum[base + 2] * scale, sum[base + 3] * scale, sum[base + 4] * scale)
            }
        }
        val states = copy.floats(SUM_BYTES, ships * GROUP_FLOATS)
        val readings = List(ships) { ship ->
            val base = ship * GROUP_FLOATS
            // RigidGroup (rigid_layout.glsl): frame, motion, scalars, tiles, shape.
            ShipReading(
                x = states[base + 2], y = states[base + 3],
                velocityX = states[base + 4], velocityY = states[base + 5],
                cos = states[base + 6], sin = states[base + 7],
                mass = states[base + 10],
                shapeX = states[base + 16], shapeY = states[base + 17],
            )
        }
        return NavalReading(shells, readings)
    }

    private fun sum(bodies: Int, materials: Int, activeBodies: Int) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, sums)
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, null as ByteBuffer?)
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
        probe.use()
        probe.int("uCount", activeBodies)
        bind(0, bodies)
        bind(1, materials)
        bind(2, sums)
        glDispatchCompute((activeBodies + 255) / 256, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
    }

    private fun copy(from: Int, to: Int, fromOffset: Long, toOffset: Long, bytes: Long) {
        glBindBuffer(GL_COPY_READ_BUFFER, from)
        glBindBuffer(GL_COPY_WRITE_BUFFER, to)
        glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, fromOffset, toOffset, bytes)
    }

    override fun close() {
        fences.filter { it != 0L }.forEach(::glDeleteSync)
        listOf(push, probe, detonate).forEach(GlProgram::close)
        glDeleteBuffers(sums)
        copies.forEach { glDeleteBuffers(it.id) }
    }

    companion object {
        /** Shells in flight at once; matches naval_layout.glsl. */
        const val SHELLS = 32
        private const val SHELL_SUM_INTS = 8
        private const val SHELL_SUM_SCALE = 16f
        private const val SUM_BYTES = SHELLS * SHELL_SUM_INTS * 4L
        const val MAXIMUM_SHIPS = 2
        /** RigidGroup of rigid_layout.glsl (RigidBlocks.GROUP_BYTES). */
        private const val GROUP_BYTES = 160L
        private const val GROUP_FLOATS = (GROUP_BYTES / 4).toInt()
        private const val READBACK_BYTES = SUM_BYTES + MAXIMUM_SHIPS * GROUP_BYTES

        /** The material tag (.w) of shell [shell]'s bodies. */
        fun shellTag(shell: Int) = -(2f + shell)
    }
}
