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
 * A block (ship, barrel or missile) as the GPU last reported it: centre (px),
 * velocity (px/s), rotation, spin (rad/s), mass (members) and the move of its
 * rest frame since it was glued (the origin of its shape, px: RigidBlocks).
 */
data class BlockReading(
    val x: Float,
    val y: Float,
    val velocityX: Float,
    val velocityY: Float,
    val cos: Float,
    val sin: Float,
    val spin: Float,
    val mass: Float,
    /** Sum of the members' squared distances from the centre, px²: times their mass, the moment of inertia. */
    val inertia: Float,
    val shapeX: Float,
    val shapeY: Float,
) {
    /** A rest-frame point of the block, as glued with shape origin ([originX], [originY]), in the world. */
    fun world(restX: Float, restY: Float, originX: Float, originY: Float): PixelOffset {
        val x0 = restX + shapeX - originX
        val y0 = restY + shapeY - originY
        return PixelOffset(x + cos * x0 - sin * y0, y + sin * x0 + cos * y0)
    }

    /** The inverse of [world]: a world point in the rest frame as glued. */
    fun rest(worldX: Float, worldY: Float, originX: Float, originY: Float): PixelOffset {
        val dx = worldX - x
        val dy = worldY - y
        return PixelOffset(cos * dx + sin * dy - shapeX + originX, -sin * dx + cos * dy - shapeY + originY)
    }

    /** A rest-frame direction in the world. */
    fun turn(x0: Float, y0: Float) = PixelOffset(cos * x0 - sin * y0, sin * x0 + cos * y0)
}

/** Everything a probe reports, a step old: the shells by slot, the blocks asked for in turn. */
class NavalReading(val shells: List<ShellReading?>, val blocks: List<BlockReading>)

/**
 * The joint of one gun (naval_drive.comp): barrel block [barrel] turns about
 * its pivot, held to the pivot on ship block [ship], towards [aimX], [aimY]
 * in the ship's frame. Pivots are in the rest frames as glued, whose shapes
 * had the given origins then; [pointingX], [pointingY] is where the barrel
 * points in its rest frame.
 */
class BarrelDrive(
    val barrel: Int,
    val ship: Int,
    val shipPivotX: Float,
    val shipPivotY: Float,
    val shipOriginX: Float,
    val shipOriginY: Float,
    val barrelPivotX: Float,
    val barrelPivotY: Float,
    val barrelOriginX: Float,
    val barrelOriginY: Float,
    val pointingX: Float,
    val pointingY: Float,
    val aimX: Float,
    val aimY: Float,
)

/**
 * A jet of missile block [group] this step (naval_thrust.comp): [wanted] gas
 * bodies thrown out of its nozzle at [speed] px/s relative to it, along
 * [exhaustX], [exhaustY]. Nozzle and direction are in the missile's rest frame
 * as glued, whose shape had origin [originX], [originY] then.
 */
class MissileJet(
    val group: Int,
    val nozzleX: Float,
    val nozzleY: Float,
    val originX: Float,
    val originY: Float,
    val exhaustX: Float,
    val exhaustY: Float,
    val wanted: Int,
    val speed: Float,
    /** Mass of one of the missile's bodies. */
    val density: Float,
)

/**
 * The GPU side of the naval battle (NavalBattle): ship engines, gun joints,
 * shells and their blasts, fire, foam, and the probe that tells the CPU where
 * ships, barrels and shells are. Shells and fire are loose solid bodies whose
 * material names them (naval_layout.glsl), so they follow spatial sorting like
 * any body. The probe is copied out behind a fence and read one frame later,
 * with the next step already queued, so the GPU never waits for the CPU.
 */
internal class NavalGpu(
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val bind: (Int, Int) -> Unit,
) : AutoCloseable {
    private val push = GlProgram.compute("/shaders/naval_push.comp")
    private val probe = GlProgram.compute("/shaders/naval_probe.comp")
    private val detonate = GlProgram.compute("/shaders/naval_detonate.comp")
    private val drive = GlProgram.compute("/shaders/naval_drive.comp")
    private val fire = GlProgram.compute("/shaders/naval_fire.comp")
    private val foam = GlProgram.compute("/shaders/naval_foam.comp")
    private val thrust = GlProgram.compute("/shaders/naval_thrust.comp")
    private val jetsUsed = buffer(MAXIMUM_JETS * Int.SIZE_BYTES.toLong())
    private val sums = buffer(SUM_BYTES)
    private val parked = buffer(Int.SIZE_BYTES.toLong())
    private val copies = Array(2) { RigidBlocks.Readback(READBACK_BYTES) }
    private val fences = LongArray(2)
    /** Blocks each copy holds. */
    private val copiedBlocks = IntArray(2)
    private var probes = 0

    init {
        listOf(probe, detonate, drive, fire, foam, thrust).forEach {
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
        dispatch(activeBodies)
    }

    /** Sets the barrels of [drives] moving, their ships taking the reaction (naval_drive.comp). */
    fun drive(
        bodies: Int, collisionBodies: Int, materials: Int, blockStates: Int, activeBodies: Int, drives: List<BarrelDrive>,
        jointSeconds: Float, maximumTurn: Float, shipDensity: Float, barrelDensity: Float,
    ) {
        require(drives.size <= MAXIMUM_DRIVES)
        if (drives.isEmpty()) return
        drive.use()
        drive.int("uCount", activeBodies)
        drive.int("uDrives", drives.size)
        drive.float("uJointTime", jointSeconds)
        drive.float("uMaximumTurn", maximumTurn)
        drive.float("uShipDensity", shipDensity)
        drive.float("uBarrelDensity", barrelDensity)
        drives.forEachIndexed { index, it ->
            drive.int("uBarrel[$index]", it.barrel)
            drive.int("uShip[$index]", it.ship)
            drive.vec2("uShipPivot[$index]", it.shipPivotX, it.shipPivotY)
            drive.vec2("uShipOrigin[$index]", it.shipOriginX, it.shipOriginY)
            drive.vec2("uBarrelPivot[$index]", it.barrelPivotX, it.barrelPivotY)
            drive.vec2("uBarrelOrigin[$index]", it.barrelOriginX, it.barrelOriginY)
            drive.vec2("uPointing[$index]", it.pointingX, it.pointingY)
            drive.vec2("uAim[$index]", it.aimX, it.aimY)
        }
        bind(0, bodies)
        bind(1, collisionBodies)
        bind(2, materials)
        bind(3, blockStates)
        dispatch(activeBodies)
    }

    /**
     * Fires [jets] (naval_thrust.comp): lights their gas bodies of [gasMass]
     * on the mean, born at step [step], spread [spread] rad, then pushes each
     * missile, of its jets' density per body, by the gas its jets got.
     */
    fun thrust(
        bodies: Int, collisionBodies: Int, materials: Int, blockStates: Int, activeBodies: Int, jets: List<MissileJet>,
        gasMass: Float, spread: Float, step: Long,
    ) {
        require(jets.size <= MAXIMUM_JETS)
        if (jets.isEmpty()) return
        clear(jetsUsed)
        thrust.use()
        thrust.int("uCount", activeBodies)
        thrust.int("uJets", jets.size)
        thrust.float("uGasMass", gasMass)
        thrust.float("uSpread", spread)
        thrust.float("uHotTag", hotTag(step))
        jets.forEachIndexed { index, jet ->
            thrust.int("uGroup[$index]", jet.group)
            thrust.vec2("uNozzle[$index]", jet.nozzleX, jet.nozzleY)
            thrust.vec2("uOrigin[$index]", jet.originX, jet.originY)
            thrust.vec2("uExhaust[$index]", jet.exhaustX, jet.exhaustY)
            thrust.int("uWanted[$index]", jet.wanted)
            thrust.float("uExhaustSpeed[$index]", jet.speed)
            thrust.float("uDensity[$index]", jet.density)
        }
        bind(0, bodies)
        bind(1, collisionBodies)
        bind(2, materials)
        bind(3, blockStates)
        bind(4, jetsUsed)
        for (pass in 0..1) {
            thrust.int("uPass", pass)
            dispatch(activeBodies)
        }
    }

    /**
     * Sets shell [shell] off, or missile block [group] if not -1: their bodies
     * fly apart at up to [speed] px/s from their centre, [radius] px out, as
     * hot fragments born at step [step], and up to [fireBodies] parked fire
     * bodies of [fireMass] on the mean are lit within [fireRadius] px at up to
     * [fireSpeed] px/s.
     */
    fun detonate(
        bodies: Int, collisionBodies: Int, materials: Int, blockStates: Int, activeBodies: Int, shell: Int, group: Int,
        radius: Float, speed: Float, fireBodies: Int, fireRadius: Float, fireSpeed: Float, fireMass: Float, step: Long,
    ) {
        sum(bodies, materials, activeBodies)
        detonate.use()
        detonate.int("uCount", activeBodies)
        detonate.int("uShell", shell)
        detonate.int("uGroup", group)
        detonate.float("uRadius", radius)
        detonate.float("uSpeed", speed)
        detonate.int("uFire", fireBodies)
        detonate.float("uFireRadius", fireRadius)
        detonate.float("uFireSpeed", fireSpeed)
        detonate.float("uFireMass", fireMass)
        detonate.float("uHotTag", hotTag(step))
        bind(0, bodies)
        bind(1, collisionBodies)
        bind(2, materials)
        bind(3, sums)
        bind(4, blockStates)
        dispatch(activeBodies)
    }

    /** The fire of blasts before step [step] (naval_fire.comp); [fire] holds its constants. */
    fun fire(bodies: Int, collisionBodies: Int, materials: Int, activeBodies: Int, step: Long, fire: FireConstants) {
        clear(parked)
        this.fire.use()
        this.fire.int("uCount", activeBodies)
        this.fire.float("uStep", step.toFloat())
        this.fire.float("uDt", fire.timeStep)
        this.fire.float("uField", fire.field)
        this.fire.float("uLift", fire.lift)
        this.fire.float("uHeatTime", fire.heatSeconds)
        this.fire.float("uDrag", fire.drag)
        this.fire.float("uFireLife", fire.lifeSeconds)
        this.fire.float("uFragmentCool", fire.fragmentCoolSeconds)
        this.fire.float("uFragmentDrag", fire.fragmentDrag)
        this.fire.float("uDiameter", fire.diameter)
        this.fire.int("uPerRow", (viewportWidth / fire.diameter).toInt())
        bind(0, bodies)
        bind(1, collisionBodies)
        bind(2, materials)
        bind(3, parked)
        dispatch(activeBodies)
    }

    /** What naval_fire.comp needs: step (s), field and lift (px/s²), heat and life times (s), drag (1/s), body size (px). */
    class FireConstants(
        val timeStep: Float,
        val field: Float,
        val lift: Float,
        val heatSeconds: Float,
        val drag: Float,
        val lifeSeconds: Float,
        val fragmentCoolSeconds: Float,
        /** The air's drag on a glowing fragment, per px: its velocity loses this times its speed per second. */
        val fragmentDrag: Float,
        val diameter: Float,
    )

    /** The foam of the liquid after a step (naval_foam.comp), from the liquid's neighbour [lists]. */
    fun foam(bodies: Int, materials: Int, lists: Int, activeBodies: Int, capacity: Int, bounded: Boolean, kernelRadius: Float, foam: FoamConstants) {
        this.foam.use()
        this.foam.int("uCount", activeBodies)
        this.foam.int("uCapacity", capacity)
        this.foam.int("uBounded", if (bounded) 1 else 0)
        this.foam.float("uKernelRadius", kernelRadius)
        this.foam.float("uDt", foam.timeStep)
        this.foam.float("uFoamTime", foam.lifeSeconds)
        this.foam.float("uBulkNeighbours", foam.bulkNeighbours)
        this.foam.vec2("uIsolation", foam.isolationStart, foam.isolationFull)
        this.foam.vec2("uTrappedAir", foam.trappedAirStart, foam.trappedAirFull)
        bind(0, bodies)
        bind(2, materials)
        bind(5, lists)
        dispatch(activeBodies)
    }

    /** What naval_foam.comp needs. */
    class FoamConstants(
        val timeStep: Float,
        val lifeSeconds: Float,
        val bulkNeighbours: Float,
        val isolationStart: Float,
        val isolationFull: Float,
        val trappedAirStart: Float,
        val trappedAirFull: Float,
    )

    /**
     * Sums the shells and copies them with the state of [blocks] (block
     * indices in [blockStates], RigidBlocks.groups) for the CPU; returns the
     * copy of the previous call, or null on the first.
     */
    fun probe(bodies: Int, materials: Int, activeBodies: Int, blockStates: Int, blocks: IntArray): NavalReading? {
        require(blocks.size <= MAXIMUM_BLOCKS)
        sum(bodies, materials, activeBodies)
        val slot = probes % copies.size
        val target = copies[slot]
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        copy(sums, target.id, 0L, 0L, SUM_BYTES)
        blocks.forEachIndexed { index, group ->
            copy(blockStates, target.id, group.toLong() * GROUP_BYTES, SUM_BYTES + index.toLong() * GROUP_BYTES, GROUP_BYTES)
        }
        if (target.persistent) glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT)
        fences[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        copiedBlocks[slot] = blocks.size
        probes++
        val previous = probes % copies.size
        if (fences[previous] == 0L) return null
        glClientWaitSync(fences[previous], GL_SYNC_FLUSH_COMMANDS_BIT, Long.MAX_VALUE)
        glDeleteSync(fences[previous])
        fences[previous] = 0L
        return read(copies[previous], copiedBlocks[previous])
    }

    private fun read(copy: RigidBlocks.Readback, blocks: Int): NavalReading {
        val sum = copy.ints(0L, SHELLS * SHELL_SUM_INTS)
        val shells = List(SHELLS) { shell ->
            val base = shell * SHELL_SUM_INTS
            val count = sum[base]
            if (count == 0) null else {
                val scale = 1f / (SHELL_SUM_SCALE * count)
                ShellReading(count, sum[base + 1] * scale, sum[base + 2] * scale, sum[base + 3] * scale, sum[base + 4] * scale)
            }
        }
        val states = copy.floats(SUM_BYTES, blocks * GROUP_FLOATS)
        val readings = List(blocks) { block ->
            val base = block * GROUP_FLOATS
            // RigidGroup (rigid_layout.glsl): frame, motion, scalars, tiles, shape.
            BlockReading(
                x = states[base + 2], y = states[base + 3],
                velocityX = states[base + 4], velocityY = states[base + 5],
                cos = states[base + 6], sin = states[base + 7],
                spin = states[base + 8], inertia = states[base + 9], mass = states[base + 10],
                shapeX = states[base + 16], shapeY = states[base + 17],
            )
        }
        return NavalReading(shells, readings)
    }

    private fun sum(bodies: Int, materials: Int, activeBodies: Int) {
        clear(sums)
        probe.use()
        probe.int("uCount", activeBodies)
        bind(0, bodies)
        bind(1, materials)
        bind(2, sums)
        dispatch(activeBodies)
    }

    private fun clear(buffer: Int) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, null as ByteBuffer?)
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
    }

    private fun dispatch(activeBodies: Int) {
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
        listOf(push, probe, detonate, drive, fire, foam, thrust).forEach(GlProgram::close)
        glDeleteBuffers(sums)
        glDeleteBuffers(jetsUsed)
        glDeleteBuffers(parked)
        copies.forEach { glDeleteBuffers(it.id) }
    }

    companion object {
        /** Shells in flight at once; matches naval_layout.glsl. */
        const val SHELLS = 32
        private const val SHELL_SUM_INTS = 8
        private const val SHELL_SUM_SCALE = 16f
        private const val SUM_BYTES = SHELLS * SHELL_SUM_INTS * 4L
        /** Blocks a probe reports: two ships, their barrels and missiles. */
        const val MAXIMUM_BLOCKS = 12
        /** Missile jets at once; matches naval_thrust.comp. */
        const val MAXIMUM_JETS = 8
        /** Gun joints at once; matches naval_drive.comp. */
        const val MAXIMUM_DRIVES = 8
        /** RigidGroup of rigid_layout.glsl (RigidBlocks.GROUP_BYTES). */
        private const val GROUP_BYTES = 160L
        private const val GROUP_FLOATS = (GROUP_BYTES / 4).toInt()
        private const val READBACK_BYTES = SUM_BYTES + MAXIMUM_BLOCKS * GROUP_BYTES
        /** Tags of naval_layout.glsl. */
        const val PARKED_TAG = -60f
        private const val HOT_BASE = 100f

        /** The material tag (.w) of shell [shell]'s bodies. */
        fun shellTag(shell: Int) = -(2f + shell)

        /** The material tag (.w) of hot bodies born at step [step]. */
        fun hotTag(step: Long) = -(HOT_BASE + step)

        private fun buffer(bytes: Long) = glGenBuffers().also {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
            glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW)
        }
    }
}
