package dev.gravity

import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glBufferSubData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL30.GL_R32UI
import org.lwjgl.opengl.GL30.GL_RED_INTEGER
import org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glClearBufferData
import org.lwjgl.opengl.GL43.glDispatchCompute
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memAllocFloat
import org.lwjgl.system.MemoryUtil.memAllocInt
import org.lwjgl.system.MemoryUtil.memFree
import java.nio.ByteBuffer
import kotlin.math.max

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
    contactDiameterPixels: Float,
    /** Collision substep (s): the time over which a pass's pushes act. */
    substepSeconds: Float,
    /** Coulomb friction of blocks, against walls. */
    friction: Float,
    private val width: Int,
    private val height: Int,
    private val bind: (Int, Int) -> Unit,
) : AutoCloseable {
    private val memberCapacity = (capacity + MAXIMUM_GROUPS * (TILE - 1) + TILE - 1) / TILE * TILE
    private val tileCapacity = memberCapacity / TILE
    private val members = buffer(memberCapacity.toLong() * Int.SIZE_BYTES)
    private val rest = buffer(memberCapacity.toLong() * 2 * Float.SIZE_BYTES)
    private val tileGroups = buffer(tileCapacity.toLong() * Int.SIZE_BYTES)
    private val shapeCapacity = max(MINIMUM_SHAPE_VALUES, capacity.toLong() * SHAPE_VALUES_PER_BODY)
    /**
     * Every group (rigid_layout.glsl), then the values of their shapes
     * (GlueShape). The collision passes read and count contacts here.
     */
    val groups = buffer(SHAPES_OFFSET + shapeCapacity * Float.SIZE_BYTES)
    private val partials = buffer(tileCapacity.toLong() * 8 * Float.SIZE_BYTES)
    private val reduce = GlProgram.compute("/shaders/rigid_reduce.comp")
    private val finalize = GlProgram.compute("/shaders/rigid_finalize.comp")
    private val apply = GlProgram.compute("/shaders/rigid_apply.comp")
    private val remapProgram = GlProgram.compute("/shaders/rigid_remap.comp")
    private var groupCount = 0
    private var tileCount = 0
    private var shapeValues = 0L

    val isEmpty get() = groupCount == 0
    /** Groups glued so far; the next one gets this index. */
    val size get() = groupCount

    init {
        listOf(reduce, finalize, apply).forEach { program ->
            program.use()
            program.vec2("uViewport", width.toFloat(), height.toFloat())
        }
        finalize.use()
        finalize.float("uBodyRadius", 0.5f * contactDiameterPixels)
        finalize.float("uSubstepDt", substepSeconds)
        finalize.float("uBlockFriction", friction)
        // The contact memory starts empty.
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, groups)
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, null as ByteBuffer?)
    }

    fun canAdd(bodies: Int, shape: GlueShape) =
        groupCount < MAXIMUM_GROUPS && tileCount + tilesFor(bodies) <= tileCapacity &&
            shapeValues + shape.values.size + shape.hull.size <= shapeCapacity

    /**
     * Glues bodies `firstBody until firstBody + offsets.size`, already placed
     * at centre + offset, with [shape] made from the same offsets. Offsets are
     * in pixels and must sum to zero.
     */
    fun add(firstBody: Int, offsets: List<PixelOffset>, centerX: Float, centerY: Float, shape: GlueShape) {
        require(offsets.isNotEmpty() && canAdd(offsets.size, shape))
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
                .putInt(tileCount).putInt(tiles).putInt((shapeValues + shape.values.size).toInt()).putInt(shape.hull.size / 2)
                .putFloat(shape.originX).putFloat(shape.originY).putFloat(shape.cellPixels).putFloat(shape.borderMinimum)
                .putInt(shapeValues.toInt()).putInt(shape.width).putInt(shape.height).putInt(0)
                .putFloat(offsets.minOf { it.x }).putFloat(offsets.maxOf { it.x })
                .putFloat(offsets.minOf { it.y }).putFloat(offsets.maxOf { it.y })
                .putInt(0).putInt(0).putInt(0).putInt(0)
                .putInt(0).putInt(0).putInt(0).putInt(0)
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
            glBufferSubData(
                GL_SHADER_STORAGE_BUFFER,
                SHAPES_OFFSET + shapeValues * Float.SIZE_BYTES,
                shape.values + shape.hull,
            )
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
        } finally {
            memFree(memberData)
            memFree(restData)
            memFree(tileData)
            memFree(groupData)
        }
        groupCount++
        tileCount += tiles
        shapeValues += shape.values.size + shape.hull.size
    }

    /**
     * Snaps every group onto its rigid motion; [bodies] is updated in place.
     * After a collision pass ([contacts]) the pass's contact memory is settled.
     */
    fun project(bodies: Int, bounded: Boolean, contacts: Boolean) {
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
        finalize.int("uContactPass", if (contacts) 1 else 0)
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
        private const val GROUP_BYTES = 144
        /** Shape values reserved per body of capacity: blocks take about four. */
        private const val SHAPE_VALUES_PER_BODY = 8L
        private const val MINIMUM_SHAPE_VALUES = 1L shl 18
        /** Contact memory of rigid_layout.glsl: five ints per block and partner. */
        private const val CONTACT_KEYS = MAXIMUM_GROUPS.toLong() * (MAXIMUM_GROUPS + 4)
        private const val SHAPES_OFFSET = MAXIMUM_GROUPS.toLong() * GROUP_BYTES + 5 * CONTACT_KEYS * Int.SIZE_BYTES

        private fun buffer(bytes: Long): Int = glGenBuffers().also {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
            glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW)
        }
    }
}
