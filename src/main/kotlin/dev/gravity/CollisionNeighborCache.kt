package dev.gravity

import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL30.GL_R32UI
import org.lwjgl.opengl.GL30.GL_RED_INTEGER
import org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glClearBufferData
import org.lwjgl.opengl.GL43.glClearBufferSubData
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Exact broad-phase reuse. Candidate pairs have diameter + skin reach. Before
 * every contact pass, AFTER integration, all actual positions must remain
 * within half the skin of the build positions. Jacobi corrections are therefore
 * included without estimating velocities or reducing the iteration budget.
 *
 * Fixed-size per-body caches are accelerators, not limits. Overflow traverses
 * the complete retained reference grid. Counts and solver use the same ordered
 * candidates; the contact formulas and float precision are retained. The strict
 * contact predicate uses precise arithmetic in both implementations so driver
 * FMA contraction cannot make counting disagree with solving at the cutoff.
 *
 * Grid heads carry the epoch of their build, so a stale head reads as an empty
 * list and no clearing pass is needed. Each pass's solve schedules the broad
 * phase of the next pass, so a pass is four dispatches.
 */
internal class CollisionNeighborCache(
    private val config: GravityConfig,
    private val width: Int,
    private val height: Int,
    private val gridWidth: Int,
    private val gridHeight: Int,
    private val heads: Int,
    private val contacts: Int,
    private val pressures: Int,
    private val adaptive: Int,
    private val bind: (Int, Int) -> Unit,
    private val dispatch: (Int, Int?) -> Unit,
    private val profiler: GpuProfiler?,
) : AutoCloseable {
    private val collision = config.collisions
    private val capacity = config.particles.capacity
    private val references = buffer(capacity.toLong() * 2 * Float.SIZE_BYTES)
    private val cache = buffer(cacheBytes(capacity))
    private val nodes = buffer(capacity.toLong() * NODE_BYTES)
    private val drift = GlProgram.compute("/shaders/collision_cache_drift.comp")
    private val build = GlProgram.compute("/shaders/collision_cache_build.comp")
    private val count = GlProgram.compute("/shaders/collision_cache_count.comp")
    private val solve = GlProgram.compute("/shaders/collision_cache_solve.comp")

    // Heads store (epoch << idBits) | (id + 1); id + 1 never exceeds capacity.
    private val idBits = 32 - Integer.numberOfLeadingZeros(capacity)
    private val maximumEpoch = ((1L shl (32 - idBits)) - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private var epoch = 0

    init {
        require(idBits < 32) { "capacity leaves no bits for the grid epoch" }
        clearStateWords(0, STATE_WORDS)

        // Guard both the displacement test and candidate cutoff against pixel
        // conversion/minimum-image float rounding. Too small a skin disables
        // reuse instead of weakening the geometric condition.
        val guard = 64f * Math.ulp(max(width, height).toFloat())
        val reuseDistance = max(0f, collision.neighborSkinPixels * 0.5f - guard)
        val candidateRadius = collision.contactDiameterPixels.toDouble() + collision.neighborSkinPixels + guard
        val cellSize = min(width.toDouble() / gridWidth, height.toDouble() / gridHeight)
        val searchRadius = max(collision.neighborCellRadius, ceil(candidateRadius / cellSize).toInt())
            .coerceAtMost(max(gridWidth, gridHeight))
        listOf(drift, build, count, solve).forEach { program ->
            program.use()
            program.int("uAdaptive", if (collision.adaptive) 1 else 0)
            program.int("uCapacity", capacity)
        }
        listOf(build, count, solve).forEach { program ->
            program.use()
            program.int("uGridWidth", gridWidth)
            program.int("uGridHeight", gridHeight)
            program.int("uIdBits", idBits)
        }
        drift.use()
        drift.vec2("uViewport", width.toFloat(), height.toFloat())
        drift.float("uTimeStep", collision.timeStep)
        drift.float("uSubstepDt", collision.timeStep / collision.maximumSubsteps)
        drift.float("uReuseDistanceSquared", reuseDistance * reuseDistance)
        listOf(count, solve).forEach { program ->
            program.use()
            program.int("uSearchRadius", searchRadius)
            program.int("uNeighborCellRadius", collision.neighborCellRadius)
            program.vec2("uViewport", width.toFloat(), height.toFloat())
            program.float("uDiameterPixels", collision.contactDiameterPixels)
        }
        count.use()
        count.float("uCandidateRadiusSquared", (candidateRadius * candidateRadius).toFloat())
        solve.use()
        solve.int("uDenseContactThreshold", collision.denseContactThreshold)
        solve.float("uRestitutionCoefficient", collision.restitutionCoefficient)
        solve.float("uCoincidentDistanceSquaredEpsilon", collision.coincidentDistanceSquaredEpsilon)
        solve.float("uSubstepDt", collision.timeStep / collision.maximumSubsteps)
        solve.float("uTimeStep", collision.timeStep)
    }

    fun resolve(
        input: Int,
        output: Int,
        acceleration: Int,
        active: Int,
        kickDt: Float,
        driftDt: Float,
        writePressure: Boolean,
        substep: Int?,
        bounded: Boolean,
    ) {
        require(active in 1..capacity)
        if (++epoch > maximumEpoch) {
            // Older tags would become ambiguous: start again from empty lists.
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, heads)
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, null as ByteBuffer?)
            invalidate()
            epoch = 1
        }
        profiler?.begin(GpuStage.COLLISION_DRIFT)
        iteration(drift, substep)
        drift.int("uCount", active)
        drift.float("uKickDt", kickDt)
        drift.float("uDriftDt", driftDt)
        drift.int("uBounded", if (bounded) 1 else 0)
        bind(0, input)
        bind(1, references)
        bind(2, cache)
        bind(3, acceleration)
        bind(4, adaptive)
        dispatch(active, substep)
        barrier()
        profiler?.end(GpuStage.COLLISION_DRIFT)

        profiler?.begin(GpuStage.COLLISION_BUILD)
        iteration(build, substep)
        build.int("uCount", active)
        build.int("uEpoch", epoch)
        bind(0, input)
        bind(1, heads)
        bind(2, nodes)
        bind(3, references)
        bind(4, cache)
        bind(5, adaptive)
        dispatch(active, substep)
        barrier()
        profiler?.end(GpuStage.COLLISION_BUILD)

        profiler?.begin(GpuStage.COLLISION_COUNT)
        iteration(count, substep)
        count.int("uCount", active)
        count.int("uBounded", if (bounded) 1 else 0)
        bind(0, input)
        bind(1, heads)
        bind(2, nodes)
        bind(3, contacts)
        bind(4, adaptive)
        bind(5, cache)
        dispatch(active, substep)
        barrier()
        profiler?.end(GpuStage.COLLISION_COUNT)

        profiler?.begin(GpuStage.COLLISION_SOLVE)
        iteration(solve, substep)
        solve.int("uCount", active)
        solve.int("uWritePressure", if (writePressure) 1 else 0)
        solve.int("uBounded", if (bounded) 1 else 0)
        // Sample one body per workgroup. This only chooses between two exact
        // broad phases for the next pass; it never limits contacts.
        solve.int("uDenseSampleThreshold", max(8, ((active + 255) / 256 + 3) / 4))
        bind(0, input)
        bind(1, output)
        bind(2, heads)
        bind(3, nodes)
        bind(4, contacts)
        bind(5, pressures)
        bind(6, adaptive)
        bind(7, cache)
        dispatch(active, substep)
        barrier()
        profiler?.end(GpuStage.COLLISION_SOLVE)
    }

    /** Body IDs changed, e.g. by spatial reordering: the next pass rebuilds. */
    fun invalidate() = clearStateWords(STATE_POPULATION, 1)

    private fun clearStateWords(first: Int, words: Int) {
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, cache)
        glClearBufferSubData(
            GL_SHADER_STORAGE_BUFFER,
            GL_R32UI,
            stateByteOffset(capacity) + first.toLong() * Int.SIZE_BYTES,
            words.toLong() * Int.SIZE_BYTES,
            GL_RED_INTEGER,
            GL_UNSIGNED_INT,
            null as ByteBuffer?,
        )
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
    }

    private fun iteration(program: GlProgram, substep: Int?) {
        program.use()
        program.int("uSubstepIndex", substep ?: -1)
    }

    private fun barrier() = glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

    override fun close() {
        listOf(drift, build, count, solve).forEach(GlProgram::close)
        intArrayOf(references, cache, nodes).forEach(::glDeleteBuffers)
    }

    companion object {
        // Matches collision_cache_layout.glsl: two metadata planes, 32
        // candidates and 8 contacts, then the control words. Overflow always
        // has a complete fallback.
        const val CACHE_PLANES = 42
        const val STATE_WORDS = 6
        const val STATE_POPULATION = 1
        const val STATE_REBUILDS = 2
        const val STATE_DIRECT = 3
        private const val NODE_BYTES = 16L

        fun stateByteOffset(capacity: Int): Long = capacity.toLong() * CACHE_PLANES * Int.SIZE_BYTES

        fun cacheBytes(capacity: Int): Long = stateByteOffset(capacity) + STATE_WORDS * Int.SIZE_BYTES

        private fun buffer(bytes: Long): Int = glGenBuffers().also {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
            glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW)
        }
    }
}
