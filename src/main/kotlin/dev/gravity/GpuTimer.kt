package dev.gravity

import org.lwjgl.opengl.GL15.GL_QUERY_RESULT
import org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE
import org.lwjgl.opengl.GL15.glBeginQuery
import org.lwjgl.opengl.GL15.glDeleteQueries
import org.lwjgl.opengl.GL15.glEndQuery
import org.lwjgl.opengl.GL15.glGenQueries
import org.lwjgl.opengl.GL15.glGetQueryObjecti
import org.lwjgl.opengl.GL33.GL_TIMESTAMP
import org.lwjgl.opengl.GL33.GL_TIME_ELAPSED
import org.lwjgl.opengl.GL33.glGetQueryObjectui64
import org.lwjgl.opengl.GL33.glQueryCounter
import java.util.ArrayDeque
import kotlin.math.ceil

enum class GpuStage(val displayName: String) {
    SPATIAL_SORT("spatial sort"),
    COLLISION_DRIFT("collision drift + cache validation"),
    COLLISION_CLEAR("collision clear"),
    COLLISION_BUILD("collision build"),
    COLLISION_COUNT("collision count"),
    COLLISION_SOLVE("collision solve"),
    COLLISION_SUBSTEPS("collision substeps total"),
    COLLISION_ADAPTIVE("collision adaptive selection"),
    RIGID_PROJECTION("rigid projection"),
    RIGID_FRACTURE("block fracture"),
    LIQUID("liquid"),
    GRAVITY_GRID("gravity grid"),
    FORWARD_FFT("forward FFT"),
    CONVOLUTION("convolution"),
    INVERSE_FFT_X("inverse FFT X"),
    INVERSE_FFT_Y("inverse FFT Y"),
    GRAVITY_KICK("gravity kick + near correction"),
    TRAJECTORY("trajectory"),
    RENDER("render"),
    PHYSICS_STEP("physics step"),
    FRAME("frame"),
}

data class GpuFrameProfile(
    val frame: Long,
    val milliseconds: DoubleArray,
)

data class GpuStageStatistics(
    val samples: Int,
    val averageMillis: Double,
    val medianMillis: Double,
    val p95Millis: Double,
    val minimumMillis: Double,
    val maximumMillis: Double,
)

class GpuProfileSummary internal constructor(
    val frames: Int,
    private val statistics: Map<GpuStage, GpuStageStatistics>,
) {
    operator fun get(stage: GpuStage): GpuStageStatistics? = statistics[stage]

    fun markdown(): String = buildString {
        appendLine("| GPU stage | Samples | Mean, ms | P50, ms | P95, ms | Min, ms | Max, ms |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|")
        GpuStage.entries.forEach { stage ->
            val value = statistics[stage] ?: return@forEach
            append("| ${stage.displayName} | ${value.samples} ")
            append("| %.3f | %.3f | %.3f | %.3f | %.3f |%n".format(
                java.util.Locale.ROOT,
                value.averageMillis,
                value.medianMillis,
                value.p95Millis,
                value.minimumMillis,
                value.maximumMillis,
            ))
        }
    }

    companion object {
        fun from(frames: List<GpuFrameProfile>): GpuProfileSummary {
            val byStage = buildMap {
                GpuStage.entries.forEach { stage ->
                    val values = frames
                        .map { it.milliseconds[stage.ordinal] }
                        .filter(Double::isFinite)
                        .sorted()
                    if (values.isEmpty()) return@forEach
                    put(
                        stage,
                        GpuStageStatistics(
                            samples = values.size,
                            averageMillis = values.average(),
                            medianMillis = percentile(values, 0.50),
                            p95Millis = percentile(values, 0.95),
                            minimumMillis = values.first(),
                            maximumMillis = values.last(),
                        ),
                    )
                }
            }
            return GpuProfileSummary(frames.size, byStage)
        }

        private fun percentile(sorted: List<Double>, fraction: Double): Double {
            val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

/**
 * An asynchronous GPU timestamp profiler.
 *
 * Every ring slot owns its query objects, so an unresolved frame is never
 * overwritten. Results are read only after the final timestamp reports
 * GL_QUERY_RESULT_AVAILABLE; command submission therefore never waits for
 * the GPU. Timestamp pairs can be nested, unlike GL_TIME_ELAPSED queries.
 */
class GpuProfiler(
    private val ringSlots: Int,
    private val maxSegmentsPerFrame: Int,
    private val completedFrameCapacity: Int,
) : AutoCloseable {
    init {
        require(ringSlots > 0)
        require(maxSegmentsPerFrame > 0)
        require(completedFrameCapacity > 0)
    }

    private class Slot(maxSegments: Int) {
        val queries = IntArray(maxSegments * 2) { glGenQueries() }
        val stage = IntArray(maxSegments)
        val startQuery = IntArray(maxSegments)
        val endQuery = IntArray(maxSegments)
        val activeStart = IntArray(GpuStage.entries.size) { -1 }
        var frame = 0L
        var queryCount = 0
        var segmentCount = 0
        var pending = false

        fun reset(frameNumber: Long) {
            frame = frameNumber
            queryCount = 0
            segmentCount = 0
            activeStart.fill(-1)
        }
    }

    private val slots = Array(ringSlots) { Slot(maxSegmentsPerFrame) }
    private val pending = ArrayDeque<Int>()
    private val completed = ArrayDeque<GpuFrameProfile>()
    private var nextSlot = 0
    private var current: Slot? = null
    private var currentIndex = -1
    var droppedFrames: Long = 0
        private set
    var latestFrameMillis: Double = Double.NaN
        private set

    val pendingFrames: Int
        get() = pending.size

    fun beginFrame(frame: Long, collect: Boolean = true) {
        check(current == null) { "GPU profile frame is already active" }
        poll()
        if (!collect) return

        var selected = -1
        for (offset in 0 until ringSlots) {
            val candidate = (nextSlot + offset) % ringSlots
            if (!slots[candidate].pending) {
                selected = candidate
                break
            }
        }
        if (selected < 0) {
            droppedFrames++
            return
        }

        currentIndex = selected
        current = slots[selected].also { it.reset(frame) }
        nextSlot = (selected + 1) % ringSlots
        begin(GpuStage.FRAME)
    }

    fun begin(stage: GpuStage) {
        val slot = current ?: return
        val ordinal = stage.ordinal
        check(slot.activeStart[ordinal] < 0) {
            "GPU stage ${stage.displayName} is already active"
        }
        check(slot.queryCount < slot.queries.size) {
            "GPU profile exceeded $maxSegmentsPerFrame segments per frame"
        }
        val queryIndex = slot.queryCount++
        glQueryCounter(slot.queries[queryIndex], GL_TIMESTAMP)
        slot.activeStart[ordinal] = queryIndex
    }

    fun end(stage: GpuStage) {
        val slot = current ?: return
        val ordinal = stage.ordinal
        val start = slot.activeStart[ordinal]
        check(start >= 0) { "GPU stage ${stage.displayName} is not active" }
        check(slot.segmentCount < maxSegmentsPerFrame)
        check(slot.queryCount < slot.queries.size)

        val end = slot.queryCount++
        glQueryCounter(slot.queries[end], GL_TIMESTAMP)
        val segment = slot.segmentCount++
        slot.stage[segment] = ordinal
        slot.startQuery[segment] = start
        slot.endQuery[segment] = end
        slot.activeStart[ordinal] = -1
    }

    fun endFrame() {
        val slot = current ?: return
        end(GpuStage.FRAME)
        check(slot.activeStart.all { it < 0 }) { "GPU profile has unfinished stages" }
        slot.pending = true
        pending.addLast(currentIndex)
        current = null
        currentIndex = -1
        poll()
    }

    fun poll(): Int {
        var resolved = 0
        while (pending.isNotEmpty()) {
            val index = pending.first()
            val slot = slots[index]
            val lastQuery = slot.queries[slot.queryCount - 1]
            if (glGetQueryObjecti(lastQuery, GL_QUERY_RESULT_AVAILABLE) == 0) break

            val timestamps = LongArray(slot.queryCount) { query ->
                glGetQueryObjectui64(slot.queries[query], GL_QUERY_RESULT)
            }
            val milliseconds = DoubleArray(GpuStage.entries.size) { Double.NaN }
            repeat(slot.segmentCount) { segment ->
                val stage = slot.stage[segment]
                val elapsed = (
                    timestamps[slot.endQuery[segment]] -
                        timestamps[slot.startQuery[segment]]
                    ) / 1_000_000.0
                milliseconds[stage] =
                    if (milliseconds[stage].isNaN()) elapsed else milliseconds[stage] + elapsed
            }
            completed.addLast(GpuFrameProfile(slot.frame, milliseconds))
            if (completed.size > completedFrameCapacity) completed.removeFirst()
            latestFrameMillis = milliseconds[GpuStage.FRAME.ordinal]
            slot.pending = false
            pending.removeFirst()
            resolved++
        }
        return resolved
    }

    fun summary(): GpuProfileSummary = GpuProfileSummary.from(completed.toList())

    override fun close() {
        check(current == null) { "Cannot close an active GPU profile frame" }
        slots.forEach { slot -> slot.queries.forEach(::glDeleteQueries) }
    }

}

/**
 * Low-overhead frame timer retained for normal interactive runs. Detailed
 * profiling uses [GpuProfiler] instead because elapsed queries cannot nest.
 */
class GpuTimer(private val slots: Int) : AutoCloseable {
    init {
        require(slots > 0)
    }

    private val queries = IntArray(slots) { glGenQueries() }
    private var write = 0
    private var read = 0
    private var pending = 0
    private var active = false

    fun begin() {
        if (pending == slots) return
        glBeginQuery(GL_TIME_ELAPSED, queries[write])
        active = true
    }

    fun end(): Double {
        if (active) {
            glEndQuery(GL_TIME_ELAPSED)
            active = false
            pending++
            write = (write + 1) % slots
        }
        if (pending == 0 ||
            glGetQueryObjecti(queries[read], GL_QUERY_RESULT_AVAILABLE) == 0) {
            return Double.NaN
        }
        val result = glGetQueryObjectui64(queries[read], GL_QUERY_RESULT) / 1_000_000.0
        read = (read + 1) % slots
        pending--
        return result
    }

    override fun close() {
        queries.forEach(::glDeleteQueries)
    }
}
