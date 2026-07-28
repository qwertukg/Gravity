package dev.gravity

import org.lwjgl.opengl.GL15.GL_QUERY_RESULT
import org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE
import org.lwjgl.opengl.GL15.glBeginQuery
import org.lwjgl.opengl.GL15.glDeleteQueries
import org.lwjgl.opengl.GL15.glEndQuery
import org.lwjgl.opengl.GL15.glGenQueries
import org.lwjgl.opengl.GL15.glGetQueryObjecti
import org.lwjgl.opengl.GL33.GL_TIME_ELAPSED
import org.lwjgl.opengl.GL33.glGetQueryObjectui64

class GpuTimer(private val slots: Int = 16) : AutoCloseable {
    init {
        require(slots > 0) { "GPU timer needs at least one query slot" }
    }

    private val queries = IntArray(slots) { glGenQueries() }
    private var write = 0
    private var read = 0
    private var pending = 0
    private var active = false

    fun begin() {
        // Never overwrite a result which the GPU has not completed yet.
        // Timing is diagnostic, so skipping a sample is preferable to
        // stalling the simulation when the command queue is unusually deep.
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
