package dev.gravity

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL11.glDrawElements
import org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER
import org.lwjgl.opengl.GL15.GL_STATIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glBufferSubData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL20.glEnableVertexAttribArray
import org.lwjgl.opengl.GL20.glUseProgram
import org.lwjgl.opengl.GL20.glVertexAttribPointer
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.stb.STBEasyFont.stb_easy_font_print
import java.nio.ByteBuffer
import java.util.Locale

class FpsOverlay(
    private val viewportWidth: Int,
    private val viewportHeight: Int,
) : AutoCloseable {
    private val maximumCharacters = 32
    private val vertexData = BufferUtils.createByteBuffer(maximumCharacters * 4 * 16)
    private val program = GlProgram.graphics("/shaders/overlay.vert", "/shaders/overlay.frag")
    private val vao = glGenVertexArrays()
    private val vertexBuffer = glGenBuffers()
    private val indexBuffer = glGenBuffers()

    init {
        val indices = BufferUtils.createIntBuffer(maximumCharacters * 6)
        repeat(maximumCharacters) { quad ->
            val base = quad * 4
            indices.put(base).put(base + 1).put(base + 2)
            indices.put(base + 2).put(base + 3).put(base)
        }
        indices.flip()

        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
        glBufferData(GL_ARRAY_BUFFER, vertexData.capacity().toLong(), GL_DYNAMIC_DRAW)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0L)
        glBindVertexArray(0)
    }

    fun render(fps: Double) {
        vertexData.clear()
        val text = String.format(Locale.ROOT, "FPS %.1f", fps)
        val quads = stb_easy_font_print(0f, 0f, text, null as ByteBuffer?, vertexData)
        vertexData.position(0)
        vertexData.limit(quads * 4 * 16)

        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexData)
        program.use()
        program.vec2("uViewport", viewportWidth.toFloat(), viewportHeight.toFloat())
        glBindVertexArray(vao)
        glDrawElements(GL_TRIANGLES, quads * 6, GL_UNSIGNED_INT, 0L)
        glBindVertexArray(0)
        glUseProgram(0)
    }

    override fun close() {
        glDeleteBuffers(vertexBuffer)
        glDeleteBuffers(indexBuffer)
        glDeleteVertexArrays(vao)
        program.close()
    }
}
