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

class GpuTimer(private val slots: Int = 4) : AutoCloseable {
    private val queries = IntArray(slots) { glGenQueries() }
    private var write = 0
    private var issued = 0L

    fun begin() {
        glBeginQuery(GL_TIME_ELAPSED, queries[write])
    }

    fun end(): Double? {
        glEndQuery(GL_TIME_ELAPSED)
        issued++
        write = (write + 1) % slots
        if (issued < slots) return null
        val read = write
        if (glGetQueryObjecti(queries[read], GL_QUERY_RESULT_AVAILABLE) == 0) return null
        return glGetQueryObjectui64(queries[read], GL_QUERY_RESULT) / 1_000_000.0
    }

    override fun close() {
        queries.forEach(::glDeleteQueries)
    }
}
