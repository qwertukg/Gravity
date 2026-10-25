package dev.gravity

import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glBufferSubData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glDispatchCompute
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memAllocFloat
import org.lwjgl.system.MemoryUtil.memAllocInt
import org.lwjgl.system.MemoryUtil.memFree

/**
 * Glued blocks: groups of bodies that move as one rigid body. Every body
 * still collides, feels gravity and hits walls on its own; after each
 * collision pass the group is projected onto the rigid motion that best fits
 * its bodies (2D shape matching). The projection keeps the group's momentum
 * and angular momentum, so whatever pushed one body moves and spins the
 * whole block.
 *
 * Members are listed per group and padded to whole tiles of [TILE] entries,
 * so every workgroup reduces a single group with plain shared-memory sums:
 * no float atomics, and the result does not depend on scheduling. Spatial
 * sorting renames body IDs; [remap] makes the members follow.
 */
internal class RigidBlocks(
    capacity: Int,
    private val width: Int,
    private val height: Int,
    private val bind: (Int, Int) -> Unit,
) : AutoCloseable {
    private val memberCapacity = (capacity + MAXIMUM_GROUPS * (TILE - 1) + TILE - 1) / TILE * TILE
    private val tileCapacity = memberCapacity / TILE
    private val members = buffer(memberCapacity.toLong() * Int.SIZE_BYTES)
    private val rest = buffer(memberCapacity.toLong() * 2 * Float.SIZE_BYTES)
    private val tileGroups = buffer(tileCapacity.toLong() * Int.SIZE_BYTES)
    private val groups = buffer(MAXIMUM_GROUPS.toLong() * GROUP_BYTES)
    private val partials = buffer(tileCapacity.toLong() * 8 * Float.SIZE_BYTES)
    private val reduce = GlProgram.compute("/shaders/rigid_reduce.comp")
    private val finalize = GlProgram.compute("/shaders/rigid_finalize.comp")
    private val apply = GlProgram.compute("/shaders/rigid_apply.comp")
    private val remapProgram = GlProgram.compute("/shaders/rigid_remap.comp")
    private var groupCount = 0
    private var tileCount = 0

    val isEmpty get() = groupCount == 0

    init {
        listOf(reduce, finalize, apply).forEach { program ->
            program.use()
            program.vec2("uViewport", width.toFloat(), height.toFloat())
        }
    }

    fun canAdd(bodies: Int) =
        groupCount < MAXIMUM_GROUPS && tileCount + tilesFor(bodies) <= tileCapacity

    /**
     * Glues bodies `firstBody until firstBody + offsets.size`, already placed
     * at centre + offset. Offsets are in pixels and must sum to zero.
     */
    fun add(firstBody: Int, offsets: List<PixelOffset>, centerX: Float, centerY: Float) {
        require(offsets.isNotEmpty() && canAdd(offsets.size))
        val tiles = tilesFor(offsets.size)
        val entries = tiles * TILE
        val memberData = memAllocInt(entries)
        val restData = memAllocFloat(entries * 2)
        val tileData = memAllocInt(tiles)
        val groupData = memAlloc(GROUP_BYTES)
        try {
            var inertia = 0.0
            for (entry in 0 until entries) {
                val offset = offsets.getOrNull(entry)
                memberData.put(if (offset == null) -1 else firstBody + entry)
                restData.put(offset?.x ?: 0f).put(offset?.y ?: 0f)
                if (offset != null) inertia += offset.x * offset.x + offset.y * offset.y
            }
            repeat(tiles) { tileData.put(groupCount) }
            groupData
                .putFloat(centerX).putFloat(centerY).putFloat(centerX).putFloat(centerY)
                .putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f)
                .putFloat(0f).putFloat(inertia.toFloat()).putFloat(offsets.size.toFloat()).putFloat(0f)
                .putInt(tileCount).putInt(tiles).putInt(0).putInt(0)
            memberData.flip()
            restData.flip()
            tileData.flip()
            groupData.flip()
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
            val firstEntry = tileCount.toLong() * TILE
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, members)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, firstEntry * Int.SIZE_BYTES, memberData)
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, rest)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, firstEntry * 2 * Float.SIZE_BYTES, restData)
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, tileGroups)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, tileCount.toLong() * Int.SIZE_BYTES, tileData)
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, groups)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, groupCount.toLong() * GROUP_BYTES, groupData)
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
        } finally {
            memFree(memberData)
            memFree(restData)
            memFree(tileData)
            memFree(groupData)
        }
        groupCount++
        tileCount += tiles
    }

    /** Snaps every group onto its rigid motion; [bodies] is updated in place. */
    fun project(bodies: Int, bounded: Boolean) {
        if (isEmpty) return
        val boundedFlag = if (bounded) 1 else 0
        reduce.use()
        reduce.int("uBounded", boundedFlag)
        bind(0, bodies)
        bind(1, members)
        bind(2, rest)
        bind(3, tileGroups)
        bind(4, groups)
        bind(5, partials)
        glDispatchCompute(tileCount, 1, 1)
        barrier()

        finalize.use()
        finalize.int("uBounded", boundedFlag)
        bind(0, partials)
        bind(1, groups)
        glDispatchCompute(groupCount, 1, 1)
        barrier()

        apply.use()
        apply.int("uBounded", boundedFlag)
        bind(0, bodies)
        bind(1, members)
        bind(2, rest)
        bind(3, tileGroups)
        bind(4, groups)
        glDispatchCompute(tileCount, 1, 1)
        barrier()
    }

    /** [destinations] maps every old body ID to its ID after spatial sorting. */
    fun remap(destinations: Int) {
        if (isEmpty) return
        remapProgram.use()
        remapProgram.int("uCount", tileCount * TILE)
        bind(0, members)
        bind(1, destinations)
        glDispatchCompute(tileCount, 1, 1)
        barrier()
    }

    private fun tilesFor(bodies: Int) = (bodies + TILE - 1) / TILE

    private fun barrier() = glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

    override fun close() {
        listOf(reduce, finalize, apply, remapProgram).forEach(GlProgram::close)
        intArrayOf(members, rest, tileGroups, groups, partials).forEach(::glDeleteBuffers)
    }

    companion object {
        /** Workgroup width of the rigid shaders. */
        const val TILE = 256
        const val MAXIMUM_GROUPS = 256
        /** Matches RigidGroup in rigid_layout.glsl. */
        private const val GROUP_BYTES = 64

        private fun buffer(bytes: Long): Int = glGenBuffers().also {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
            glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW)
        }
    }
}
