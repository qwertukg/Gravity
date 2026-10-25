package dev.gravity

import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glBufferSubData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL15.glGetBufferSubData
import org.lwjgl.opengl.GL30.GL_R32UI
import org.lwjgl.opengl.GL30.GL_RED_INTEGER
import org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER
import org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER
import org.lwjgl.opengl.GL31.glCopyBufferSubData
import org.lwjgl.opengl.GL32.GL_SYNC_FLUSH_COMMANDS_BIT
import org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE
import org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED
import org.lwjgl.opengl.GL32.glClientWaitSync
import org.lwjgl.opengl.GL32.glDeleteSync
import org.lwjgl.opengl.GL32.glFenceSync
import org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glClearBufferData
import org.lwjgl.opengl.GL43.glDispatchCompute
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memFree
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeSet
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

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
 *
 * Blocks break ([strength]): an impact faster than the material's critical
 * speed chips the members it strikes off as loose grains, and its energy
 * beyond that runs on as a crack (rigid_fracture.comp). The GPU carves the
 * block's shape and moves its centre of mass at once; whether the block
 * still holds together is checked here, on the CPU, a step or two later
 * ([afterStep]): each piece that came apart becomes a block of its own. The
 * CPU keeps each block's lattice for that, so a look reads back only which
 * members are left and walks only the pieces that came apart.
 */
internal class RigidBlocks(
    capacity: Int,
    private val contactDiameterPixels: Float,
    /** Collision substep (s): the time over which a pass's pushes act. */
    substepSeconds: Float,
    /** Coulomb friction of blocks, against walls. */
    friction: Float,
    /** Critical impact speed of block material, px/s; infinite for unbreakable blocks. */
    strength: Float,
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
     * (GlueShape) with their slot grids and hulls. The collision passes read
     * and count contacts here.
     */
    val groups = buffer(SHAPES_OFFSET + shapeCapacity * Float.SIZE_BYTES)
    private val partials = buffer(tileCapacity.toLong() * 8 * Float.SIZE_BYTES)
    /** The hardest impact of a pass on each member body (collision_rigid_contact.glsl). */
    val fractures = buffer(capacity.toLong() * 4 * Float.SIZE_BYTES)
    private val fractureSlots = buffer(memberCapacity.toLong() * 4 * Float.SIZE_BYTES)
    private val fractureGroups = buffer(FRACTURE_GROUP_BYTES)
    /** Where [fractureGroups] is copied after a step, for the CPU to read without waiting. */
    private val fractureCopy = buffer(FRACTURE_GROUP_BYTES)
    /**
     * Members to break off this pass and members glued into a piece of their
     * own: two counts, then (group << 24 | slot) each, the second list from
     * [memberCapacity] on (rigid_fracture.comp).
     */
    private val brokenList = buffer((2L + 2L * memberCapacity) * Int.SIZE_BYTES)
    /** Each group's move of its rest frame and recoil, for rigid_shift.comp. */
    private val fractureShifts = buffer(MAXIMUM_GROUPS * 2L * 4 * Float.SIZE_BYTES)
    private val reduce = GlProgram.compute("/shaders/rigid_reduce.comp")
    private val finalize = GlProgram.compute("/shaders/rigid_finalize.comp")
    private val apply = GlProgram.compute("/shaders/rigid_apply.comp")
    private val fractureApply = GlProgram.compute("/shaders/rigid_apply.comp", "FRACTURE")
    private val fracture = GlProgram.compute("/shaders/rigid_fracture.comp")
    private val shift = GlProgram.compute("/shaders/rigid_shift.comp")
    private val adopt = GlProgram.compute("/shaders/rigid_adopt.comp")
    private val remapProgram = GlProgram.compute("/shaders/rigid_remap.comp")
    /** Blocks can break. */
    val breakable = strength.isFinite()
    /** Group indices used so far: every one below may be in use or free. */
    private var groupCount = 0
    /** Tiles used so far; the rigid passes cover all of them. */
    private var tileCount = 0
    private val layouts = arrayOfNulls<Layout>(MAXIMUM_GROUPS)
    private val freeGroups = TreeSet<Int>()
    private val tileSpace = RangeAllocator(tileCapacity.toLong())
    private val shapeSpace = RangeAllocator(shapeCapacity)
    private var passes = 0
    private var copyFence = 0L
    private var stepsSinceRefresh = 0
    private var cacheBuffer = fractureCopy
    private var cacheInvalidation = -1

    val isEmpty get() = groupCount == 0
    /** Group indices used so far: the collision passes cover all of them. */
    val size get() = groupCount
    /** The group index the next block glued gets. */
    val nextGroup get() = freeGroups.firstOrNull() ?: groupCount

    /** Where a group's members and shape live: tiles and shape values. */
    private class Placement(val firstTile: Int, val tiles: Int, val firstValue: Long, val values: Long)

    /** A group's placement and what the CPU keeps of its lattice; null for a free index. */
    private class Layout(
        val place: Placement,
        /** Rest centres of the slots as uploaded, px; the GPU's move with the shape's origin. */
        val restX: FloatArray,
        val restY: FloatArray,
        val originX: Float,
        val originY: Float,
        val strengths: FloatArray,
        /** BlockPieces.BONDS lattice neighbours per slot, -1 for none. */
        val bonds: IntArray,
        /** Slots that held a member at the last look. */
        val alive: BooleanArray,
        /** Slots row by row (one height each), each row from left to right: [rowSlots] from [rowStarts]. */
        val rowStarts: IntArray,
        val rowSlots: IntArray,
        /** Slots on the hull; empty if unknown. */
        var hull: IntArray,
    )

    /** A member to list: its body, rest offset (px) and strength factor. */
    private class Member(val body: Int, val x: Float, val y: Float, val strength: Float)

    /** A group's rigid motion: centre (px), velocity (px/s), rotation and spin (rad/s). */
    private class Motion(
        val centerX: Float,
        val centerY: Float,
        val velocityX: Float,
        val velocityY: Float,
        val cos: Float,
        val sin: Float,
        val spin: Float,
    )

    init {
        require(memberCapacity <= NUCLEUS_SLOTS) { "capacity exceeds the member slots of a crack nucleus" }
        listOf(reduce, finalize, apply, fractureApply, fracture, shift).forEach { program ->
            program.use()
            program.vec2("uViewport", width.toFloat(), height.toFloat())
        }
        finalize.use()
        finalize.float("uBodyRadius", 0.5f * contactDiameterPixels)
        finalize.float("uSubstepDt", substepSeconds)
        finalize.float("uBlockFriction", friction)
        fractureApply.use()
        fractureApply.float("uFractureSpeed", strength)
        fracture.use()
        fracture.float("uDiameterPixels", contactDiameterPixels)
        fracture.int("uMovedBase", memberCapacity)
        // The contact memory starts empty, and so do the impact records.
        intArrayOf(groups, fractures, fractureGroups, fractureCopy, brokenList, fractureShifts).forEach {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, null as ByteBuffer?)
        }
    }

    /**
     * The neighbour cache leaves pairs of members of one block out of its
     * candidate lists: writing 0 to word [word] of [buffer] makes it rebuild
     * them once members break loose.
     */
    fun invalidates(buffer: Int, word: Int) {
        cacheBuffer = buffer
        cacheInvalidation = word
    }

    fun canAdd(bodies: Int, shape: GlueShape) =
        nextGroup < MAXIMUM_GROUPS && tileSpace.fits(tilesFor(bodies).toLong()) &&
            shapeSpace.fits(regionValues(shape))

    /**
     * Glues bodies `firstBody until firstBody + offsets.size`, already placed
     * at centre + offset, with [shape] made from the same offsets, as group
     * [nextGroup]. Offsets are in pixels and must sum to zero.
     */
    fun add(firstBody: Int, offsets: List<PixelOffset>, centerX: Float, centerY: Float, shape: GlueShape) {
        require(offsets.isNotEmpty() && canAdd(offsets.size, shape))
        val group = nextGroup
        val random = Random(firstBody.toLong() * 0x9e3779b97f4a7c15uL.toLong() + group)
        val listed = offsets.mapIndexed { index, offset ->
            Member(firstBody + index, offset.x, offset.y, weibullStrength(random))
        }
        val place = Placement(
            firstTile = tileSpace.allocate(tilesFor(offsets.size).toLong())!!.toInt(),
            tiles = tilesFor(offsets.size),
            firstValue = shapeSpace.allocate(regionValues(shape))!!,
            values = regionValues(shape),
        )
        // The bodies are uploaded with velocity; the first projection fits it.
        upload(group, place, listed, shape, Motion(centerX, centerY, 0f, 0f, 1f, 0f, 0f))
        adoptGroup(group, place, materials = null)
    }

    /**
     * Snaps every group onto its rigid motion; [bodies] is updated in place.
     * After a collision pass ([contacts]) the pass's contact memory is
     * settled, and what its impacts broke breaks off ([materials]).
     */
    fun project(bodies: Int, materials: Int, bounded: Boolean, contacts: Boolean) {
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

        val fracturing = breakable && contacts
        val snap = if (fracturing) fractureApply else apply
        snap.use()
        snap.int("uBounded", boundedFlag)
        bind(0, bodies)
        bind(1, members)
        bind(2, rest)
        bind(3, tileGroups)
        bind(4, groups)
        if (fracturing) {
            bind(5, fractures)
            bind(6, fractureSlots)
            bind(7, fractureGroups)
            bind(8, brokenList)
        }
        glDispatchCompute(tileCount, 1, 1)
        barrier()
        if (fracturing) fracturePass(bodies, materials)
    }

    /** Lets go of what broke off each block, carves its shape and moves its centre (rigid_fracture.comp). */
    private fun fracturePass(bodies: Int, materials: Int) {
        fracture.use()
        fracture.int("uSeed", passes++)
        fracture.int("uCacheInvalidation", cacheInvalidation)
        bind(0, bodies)
        bind(1, members)
        bind(2, rest)
        bind(3, groups)
        bind(4, fractureSlots)
        bind(5, fractureGroups)
        bind(6, materials)
        bind(7, cacheBuffer)
        bind(8, brokenList)
        bind(9, fractureShifts)
        glDispatchCompute(groupCount, 1, 1)
        barrier()

        shift.use()
        bind(0, bodies)
        bind(1, members)
        bind(2, rest)
        bind(3, tileGroups)
        bind(4, groups)
        bind(5, fractureShifts)
        bind(6, brokenList)
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

    /**
     * After a physics step: reads which blocks lost members from a copy the
     * GPU made a step or two ago (a fence on it, so nothing waits), and
     * splits what came apart ([refresh]): soon where a broken member's
     * neighbours may have come apart, at the latest after [SETTLE_STEPS] for
     * any other loss. Returns whether bodies changed block, so cached
     * contact lists are stale.
     */
    fun afterStep(bodies: Int, materials: Int, bounded: Boolean): Boolean {
        if (!breakable || isEmpty) return false
        stepsSinceRefresh++
        var changed = false
        if (copyFence != 0L) {
            if (glClientWaitSync(copyFence, GL_SYNC_FLUSH_COMMANDS_BIT, 0L) == GL_TIMEOUT_EXPIRED) return false
            glDeleteSync(copyFence)
            copyFence = 0L
            val flags = readInts(fractureCopy, 0L, groupCount * 4)
            val lost = (0 until groupCount).map { flags[4 * it + 3] }
            val due = lost.any { it and CHANGED_MAY_SPLIT == CHANGED_MAY_SPLIT } ||
                lost.any { it != 0 } && stepsSinceRefresh >= SETTLE_STEPS
            if (due) {
                changed = refresh(bodies, materials, bounded)
                stepsSinceRefresh = 0
            }
        }
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        glBindBuffer(GL_COPY_READ_BUFFER, fractureGroups)
        glBindBuffer(GL_COPY_WRITE_BUFFER, fractureCopy)
        glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0L, 0L, groupCount * 4L * Int.SIZE_BYTES)
        copyFence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        return changed
    }

    /**
     * Splits every block that lost members since the last look into the
     * pieces that still hold together. Blocking: it reads back which members
     * are left, which waits for the GPU. The largest piece stays the block;
     * every other piece of at least [MINIMUM_FRAGMENT_BODIES] becomes a block
     * of its own, with the same rigid motion, and smaller ones crumble into
     * grains. The fracture pass then carves what left out of the block's
     * shape at once, before the next collision pass sees it. Returns whether
     * bodies changed block.
     */
    fun refresh(bodies: Int, materials: Int, bounded: Boolean): Boolean {
        if (isEmpty) return false
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        val flags = readInts(fractureGroups, 0L, groupCount * 4)
        val touched = (0 until groupCount).filter { layouts[it] != null && flags[4 * it + 3] != 0 }
        if (touched.isEmpty()) return false
        touched.forEach { writeInts(fractureGroups, (4L * it + 3) * Int.SIZE_BYTES, intArrayOf(0)) }
        val state = readFloats(groups, 0L, groupCount * GROUP_FLOATS)
        val broken = ArrayList<Int>()
        val moved = ArrayList<Int>()
        var changed = false
        for (group in touched) changed = split(group, state, materials, bounded, broken, moved) || changed
        if (changed) {
            writeInts(brokenList, 0L, intArrayOf(broken.size, moved.size) + broken.toIntArray())
            writeInts(brokenList, (2L + memberCapacity) * Int.SIZE_BYTES, moved.toIntArray())
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
            fracturePass(bodies, materials)
        }
        return changed
    }

    /**
     * Splits [group]: the members of pieces glued into blocks of their own are
     * added to [moved], those of crumbs to [broken] (rigid_fracture.comp).
     */
    private fun split(
        group: Int,
        state: FloatArray,
        materials: Int,
        bounded: Boolean,
        broken: MutableList<Int>,
        moved: MutableList<Int>,
    ): Boolean {
        val layout = layouts[group]!!
        val firstEntry = layout.place.firstTile * TILE
        val listed = readInts(members, firstEntry.toLong() * Int.SIZE_BYTES, layout.place.tiles * TILE)
        // The members gone since the last look, and their neighbours left.
        val seeds = LinkedHashSet<Int>()
        var left = 0
        for (slot in listed.indices) {
            if (layout.alive[slot] && listed[slot] < 0) {
                layout.alive[slot] = false
                for (bond in 0 until BlockPieces.BONDS) {
                    val other = layout.bonds[slot * BlockPieces.BONDS + bond]
                    if (other >= 0) seeds += other
                }
            }
            if (layout.alive[slot]) left++
        }
        if (left == 0) {
            release(group)
            return false
        }
        seeds.removeAll { !layout.alive[it] }
        val base = group * GROUP_FLOATS
        // The GPU's rest frame has moved with the shape's origin since the upload.
        val shiftX = state[base + 16] - layout.originX
        val shiftY = state[base + 17] - layout.originY
        val pieces = BlockPieces.separated(layout.bonds, layout.alive, seeds.toIntArray())
        if (pieces.isEmpty()) {
            writeHull(group, layout, state, shiftX, shiftY)
            return false
        }
        val motion = Motion(
            state[base + 2], state[base + 3], state[base + 4], state[base + 5],
            state[base + 6], state[base + 7], state[base + 8],
        )
        var flagged = 0
        for (piece in pieces) {
            for (slot in piece) layout.alive[slot] = false
            val glued = piece.size >= MINIMUM_FRAGMENT_BODIES && glue(
                piece.map { Member(listed[it], layout.restX[it] + shiftX, layout.restY[it] + shiftY, layout.strengths[it]) },
                motion, materials, bounded,
            )
            for (slot in piece) (if (glued) moved else broken) += group shl 24 or (firstEntry + slot)
            flagged += piece.size
        }
        writeHull(group, layout, state, shiftX, shiftY)
        writeInts(fractureGroups, group.toLong() * 4 * Int.SIZE_BYTES, intArrayOf(0, 0, flagged, 0))
        return true
    }

    /** Glues [piece] of a block moving with [motion] into a new group, if there is room. */
    private fun glue(piece: List<Member>, motion: Motion, materials: Int, bounded: Boolean): Boolean {
        val group = nextGroup
        if (group >= MAXIMUM_GROUPS) return false
        val center = centroid(piece)
        val tiles = tilesFor(piece.size)
        if (!tileSpace.fits(tiles.toLong())) return false
        val shape = GlueShape.of(piece.map { PixelOffset(it.x - center.x, it.y - center.y) }, contactDiameterPixels)
        if (!shapeSpace.fits(regionValues(shape))) return false
        val place = Placement(
            tileSpace.allocate(tiles.toLong())!!.toInt(), tiles,
            shapeSpace.allocate(regionValues(shape))!!, regionValues(shape),
        )
        upload(group, place, piece, shape, moved(motion, center, bounded))
        adoptGroup(group, place, materials)
        return true
    }

    /**
     * The hull of the members left, if a member on the old one broke off, in
     * the GPU's rest frame (the upload's moved by [shiftX], [shiftY]). Only
     * the ends of each row can be corners: the first and last members left.
     */
    private fun writeHull(group: Int, layout: Layout, state: FloatArray, shiftX: Float, shiftY: Float) {
        if (layout.hull.isNotEmpty() && layout.hull.all { layout.alive[it] }) return
        val ends = IntArray(2 * (layout.rowStarts.size - 1))
        var count = 0
        for (row in 0 until layout.rowStarts.size - 1) {
            var first = layout.rowStarts[row]
            var last = layout.rowStarts[row + 1] - 1
            while (first <= last && !layout.alive[layout.rowSlots[first]]) first++
            while (last > first && !layout.alive[layout.rowSlots[last]]) last--
            if (first > last) continue
            ends[count++] = layout.rowSlots[first]
            if (last > first) ends[count++] = layout.rowSlots[last]
        }
        val corners = GlueShape.hullIndices(
            FloatArray(count) { layout.restX[ends[it]] },
            FloatArray(count) { layout.restY[ends[it]] },
        ).map { ends[it] }.toIntArray()
        val hull = FloatArray(2 * corners.size)
        corners.forEachIndexed { index, slot ->
            hull[2 * index] = layout.restX[slot] + shiftX
            hull[2 * index + 1] = layout.restY[slot] + shiftY
        }
        val firstHull = state[group * GROUP_FLOATS + 14].toRawBits().toLong()
        // Never past the shape's region: the old hull, carved along, stays.
        if (firstHull + hull.size > layout.place.firstValue + layout.place.values) return
        layout.hull = corners
        writeFloats(groups, SHAPES_OFFSET + firstHull * Float.SIZE_BYTES, hull)
        writeInts(groups, group.toLong() * GROUP_BYTES + 15 * Int.SIZE_BYTES, intArrayOf(corners.size))
    }

    /** The motion of the rigid body at rest offset [center] of a body moving with [motion]. */
    private fun moved(motion: Motion, center: PixelOffset, bounded: Boolean): Motion {
        val armX = motion.cos * center.x - motion.sin * center.y
        val armY = motion.sin * center.x + motion.cos * center.y
        var x = motion.centerX + armX
        var y = motion.centerY + armY
        if (!bounded) {
            x = ToroidalSpace.wrapPixels(x, width)
            y = ToroidalSpace.wrapPixels(y, height)
        }
        return Motion(
            x, y, motion.velocityX - motion.spin * armY, motion.velocityY + motion.spin * armX,
            motion.cos, motion.sin, motion.spin,
        )
    }

    private fun centroid(listed: List<Member>) = PixelOffset(
        (listed.sumOf { it.x.toDouble() } / listed.size).toFloat(),
        (listed.sumOf { it.y.toDouble() } / listed.size).toFloat(),
    )

    /** Frees a group whose members all broke off. */
    private fun release(group: Int) {
        val place = layouts[group]!!.place
        tileSpace.free(place.firstTile.toLong(), place.tiles.toLong())
        shapeSpace.free(place.firstValue, place.values)
        layouts[group] = null
        freeGroups += group
    }

    /**
     * Writes group [group] at [place]: [listed] in its slots, about their
     * centroid (which [motion] is the motion of), with [shape] made from them.
     */
    private fun upload(group: Int, place: Placement, listed: List<Member>, shape: GlueShape, motion: Motion) {
        val center = centroid(listed)
        val firstEntry = place.firstTile * TILE
        val entries = place.tiles * TILE
        val memberData = IntArray(entries) { -1 }
        val restX = FloatArray(entries)
        val restY = FloatArray(entries)
        val strengths = FloatArray(entries)
        val restData = FloatArray(entries * 2)
        val slotData = FloatArray(entries * 4)
        var inertia = 0.0
        listed.forEachIndexed { index, member ->
            val x = member.x - center.x
            val y = member.y - center.y
            memberData[index] = member.body
            restX[index] = x
            restY[index] = y
            strengths[index] = member.strength
            restData[2 * index] = x
            restData[2 * index + 1] = y
            slotData[4 * index] = member.strength
            inertia += x * x + y * y
        }
        // The slot grid on the shape's nodes (rigid_layout.glsl).
        val cells = shape.width * shape.height
        val region = FloatArray(place.values.toInt())
        shape.values.copyInto(region)
        region.fill(-1f, cells, 2 * cells)
        for (index in listed.indices) {
            val cellX = floor((restX[index] - shape.originX) / shape.cellPixels).toInt().coerceIn(0, shape.width - 1)
            val cellY = floor((restY[index] - shape.originY) / shape.cellPixels).toInt().coerceIn(0, shape.height - 1)
            region[cells + cellX + cellY * shape.width] = (firstEntry + index).toFloat()
        }
        // The hull of the members as listed; the shape's own if that does not fit.
        var corners = GlueShape.hullIndices(restX.copyOf(listed.size), restY.copyOf(listed.size))
        if (2L * corners.size > place.values - 2 * cells) corners = IntArray(0)
        val hull = if (corners.isEmpty()) shape.hull else FloatArray(2 * corners.size) {
            if (it % 2 == 0) restX[corners[it / 2]] else restY[corners[it / 2]]
        }
        hull.copyInto(region, 2 * cells)
        val bonds = BlockPieces.bonds(restX.copyOf(listed.size), restY.copyOf(listed.size), contactDiameterPixels)
            .copyOf(entries * BlockPieces.BONDS)
        bonds.fill(-1, listed.size * BlockPieces.BONDS)
        // Rows of one height, each from left to right.
        val rowSlots = listed.indices.sortedWith(compareBy({ restY[it] }, { restX[it] })).toIntArray()
        val rowStarts = ArrayList<Int>()
        rowSlots.forEachIndexed { index, slot -> if (index == 0 || restY[slot] != restY[rowSlots[index - 1]]) rowStarts += index }
        rowStarts += rowSlots.size
        val groupData = memAlloc(GROUP_BYTES).order(ByteOrder.nativeOrder())
        try {
            groupData
                .putFloat(motion.centerX).putFloat(motion.centerY).putFloat(motion.centerX).putFloat(motion.centerY)
                .putFloat(motion.velocityX).putFloat(motion.velocityY).putFloat(motion.cos).putFloat(motion.sin)
                .putFloat(motion.spin).putFloat(inertia.toFloat()).putFloat(listed.size.toFloat()).putFloat(0f)
                .putInt(place.firstTile).putInt(place.tiles)
                .putInt((place.firstValue + 2 * cells).toInt()).putInt(hull.size / 2)
                .putFloat(shape.originX).putFloat(shape.originY).putFloat(shape.cellPixels).putFloat(shape.borderMinimum)
                .putInt(place.firstValue.toInt()).putInt(shape.width).putInt(shape.height).putInt(0)
                .putFloat(listed.minOf { it.x } - center.x).putFloat(listed.maxOf { it.x } - center.x)
                .putFloat(listed.minOf { it.y } - center.y).putFloat(listed.maxOf { it.y } - center.y)
                .putInt(0).putInt(0).putInt(0).putInt(0)
                .putInt(0).putInt(0).putInt(0).putInt(0)
            groupData.flip()
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
            writeInts(members, firstEntry.toLong() * Int.SIZE_BYTES, memberData)
            writeFloats(rest, firstEntry.toLong() * 2 * Float.SIZE_BYTES, restData)
            writeFloats(fractureSlots, firstEntry.toLong() * 4 * Float.SIZE_BYTES, slotData)
            writeInts(tileGroups, place.firstTile.toLong() * Int.SIZE_BYTES, IntArray(place.tiles) { group })
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, groups)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, group.toLong() * GROUP_BYTES, groupData)
            writeFloats(groups, SHAPES_OFFSET + place.firstValue * Float.SIZE_BYTES, region)
            writeInts(fractureGroups, group.toLong() * 4 * Int.SIZE_BYTES, IntArray(4))
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
        } finally {
            memFree(groupData)
        }
        layouts[group] = Layout(
            place, restX, restY, shape.originX, shape.originY, strengths, bonds,
            BooleanArray(entries) { it < listed.size }, rowStarts.toIntArray(), rowSlots, corners,
        )
        freeGroups -= group
        groupCount = max(groupCount, group + 1)
        tileCount = max(tileCount, place.firstTile + place.tiles)
    }

    /** Starts group [group] afresh: its members name it ([materials], if given), its contact memory is empty. */
    private fun adoptGroup(group: Int, place: Placement, materials: Int?) {
        val slots = if (materials == null) 0 else place.tiles * TILE
        adopt.use()
        adopt.int("uGroup", group)
        adopt.int("uFirstSlot", place.firstTile * TILE)
        adopt.int("uSlots", slots)
        bind(0, members)
        bind(1, materials ?: fractureCopy)
        bind(2, groups)
        glDispatchCompute((max(slots, CONTACT_PARTNERS) + TILE - 1) / TILE, 1, 1)
        barrier()
    }

    private fun tilesFor(bodies: Int) = (bodies + TILE - 1) / TILE

    /** Shape values, slot grid and room for a hull up to twice the grid's perimeter. */
    private fun regionValues(shape: GlueShape): Long {
        val cells = shape.width.toLong() * shape.height
        return 2 * cells + max(shape.hull.size.toLong(), 4L * (shape.width + shape.height))
    }

    private fun barrier() = glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

    override fun close() {
        if (copyFence != 0L) glDeleteSync(copyFence)
        listOf(reduce, finalize, apply, fractureApply, fracture, shift, adopt, remapProgram).forEach(GlProgram::close)
        intArrayOf(
            members, rest, tileGroups, groups, partials, fractures, fractureSlots, fractureGroups, fractureCopy,
            brokenList, fractureShifts,
        ).forEach(::glDeleteBuffers)
    }

    /** First-fit ranges of a buffer; freed neighbours merge. */
    private class RangeAllocator(capacity: Long) {
        /** Free ranges, start to length. */
        private val free = java.util.TreeMap<Long, Long>().apply { put(0L, capacity) }

        fun fits(length: Long) = free.values.any { it >= length }

        fun allocate(length: Long): Long? {
            val (start, size) = free.entries.firstOrNull { it.value >= length } ?: return null
            free.remove(start)
            if (size > length) free[start + length] = size - length
            return start
        }

        fun free(start: Long, length: Long) {
            if (length <= 0L) return
            var first = start
            var size = length
            free.floorEntry(start)?.let { if (it.key + it.value == start) { first = it.key; size += it.value; free.remove(it.key) } }
            free[start + length]?.let { size += it; free.remove(start + length) }
            free[first] = size
        }
    }

    companion object {
        /** Workgroup width of the rigid shaders. */
        const val TILE = 256
        const val MAXIMUM_GROUPS = 256
        /** Matches RigidGroup in rigid_layout.glsl. */
        private const val GROUP_BYTES = 144
        private const val GROUP_FLOATS = GROUP_BYTES / Float.SIZE_BYTES
        /** Shape values reserved per body of capacity: blocks take about eight with their slot grids. */
        private const val SHAPE_VALUES_PER_BODY = 16L
        private const val MINIMUM_SHAPE_VALUES = 1L shl 18
        /** Contact memory of rigid_layout.glsl: five ints per block and partner. */
        private const val CONTACT_PARTNERS = MAXIMUM_GROUPS + 4
        private const val CONTACT_KEYS = MAXIMUM_GROUPS.toLong() * CONTACT_PARTNERS
        private const val SHAPES_OFFSET = MAXIMUM_GROUPS.toLong() * GROUP_BYTES + 5 * CONTACT_KEYS * Int.SIZE_BYTES
        /** Fracture state of every group (rigid_layout.glsl). */
        private const val FRACTURE_GROUP_BYTES = MAXIMUM_GROUPS * 4L * Int.SIZE_BYTES
        /** CHANGED_MAY_SPLIT of rigid_layout.glsl: a broken member's neighbours may have come apart. */
        private const val CHANGED_MAY_SPLIT = 3
        /** Most steps a block that lost members waits for a look, should a split have gone unnoticed. */
        private const val SETTLE_STEPS = 8
        /** Member slots a crack nucleus can name (NUCLEUS_SLOT_MASK). */
        private const val NUCLEUS_SLOTS = 1 shl 24
        /** A piece that broke off with fewer bodies crumbles into grains. */
        const val MINIMUM_FRAGMENT_BODIES = 16
        /**
         * Energy a crack takes per member, in units of the member's mass x
         * the critical speed squared: well above the half that would only
         * stop it, since most of an impact's energy goes into heat and sound.
         */
        const val FRACTURE_TOUGHNESS = 20f
        /** Weibull modulus of the members' strengths: the scatter of flaws in a brittle solid. */
        private const val WEIBULL_MODULUS = 6.0
        /** Gamma(1 + 1/WEIBULL_MODULUS): the mean of a unit Weibull strength. */
        private const val WEIBULL_MEAN = 0.9277193

        /**
         * Members a contact's energy breaks, per unit of its effective mass x
         * (v^2 - vc^2) and per unit of the member's mass (collision_rigid_contact.glsl).
         */
        fun fractureEnergy(strength: Float): Float =
            if (strength.isFinite()) 1f / (strength * strength * FRACTURE_TOUGHNESS) else 0f

        /** Strength factor of one member, about 1 on average. */
        private fun weibullStrength(random: Random): Float =
            ((-ln(1.0 - random.nextDouble())).pow(1.0 / WEIBULL_MODULUS) / WEIBULL_MEAN)
                .coerceIn(0.5, 2.0).toFloat()

        private fun buffer(bytes: Long): Int = glGenBuffers().also {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
            glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW)
        }

        private fun readInts(buffer: Int, byteOffset: Long, length: Int) = IntArray(length).also {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
            glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, byteOffset, it)
        }

        private fun readFloats(buffer: Int, byteOffset: Long, length: Int) = FloatArray(length).also {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
            glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, byteOffset, it)
        }

        private fun writeInts(buffer: Int, byteOffset: Long, values: IntArray) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, byteOffset, values)
        }

        private fun writeFloats(buffer: Int, byteOffset: Long, values: FloatArray) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, byteOffset, values)
        }
    }
}
