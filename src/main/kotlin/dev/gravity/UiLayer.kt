package dev.gravity

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawElements
import org.lwjgl.opengl.GL11.glEnable
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
import org.lwjgl.opengl.GL20.glVertexAttribPointer
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.stb.STBEasyFont.stb_easy_font_print
import org.lwjgl.stb.STBEasyFont.stb_easy_font_width

/**
 * Flat UI in window pixels (origin top-left): translucent panels and STB Easy
 * Font text, shared by the overlay and the scenario buttons so they match.
 */
internal class UiLayer(
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val maximumQuads: Int,
) : AutoCloseable {
    private val vertexData = BufferUtils.createByteBuffer(maximumQuads * BYTES_PER_QUAD)
    private val textColor = BufferUtils.createByteBuffer(4)
        .put(0).put(-1).put(0).put(-1).flip()
    private val program = GlProgram.graphics("/shaders/ui.vert", "/shaders/ui.frag")
    private val vao = glGenVertexArrays()
    private val vertexBuffer = glGenBuffers()
    private val indexBuffer = glGenBuffers()
    private var quads = 0
    private var uploadedQuads = 0

    init {
        val indices = BufferUtils.createIntBuffer(maximumQuads * 6)
        repeat(maximumQuads) { quad ->
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
        // stb_easy_font layout: float x, y, z, then RGBA8.
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, VERTEX_BYTES, 0L)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, VERTEX_BYTES, 12L)
        glBindVertexArray(0)
    }

    fun begin() {
        vertexData.clear()
        quads = 0
    }

    fun panel(left: Float, top: Float, right: Float, bottom: Float, color: Int = PANEL_COLOR) {
        if (quads == maximumQuads) return
        for ((x, y) in listOf(left to top, right to top, right to bottom, left to bottom)) {
            vertexData.putFloat(x).putFloat(y).putFloat(0f).putInt(color)
        }
        quads++
    }

    /** Text whose panel has its top-left corner at ([left], [top]), [scale] times the font size. */
    fun text(text: String, left: Float, top: Float, scale: Float = 1f) {
        val start = vertexData.position()
        val textQuads = stb_easy_font_print(0f, 0f, text, textColor, vertexData.slice())
            .coerceAtMost(maximumQuads - quads)
        // Glyphs are emitted from the origin; move them inside the padding.
        for (vertex in 0 until textQuads * 4) {
            val offset = start + vertex * VERTEX_BYTES
            vertexData.putFloat(offset, left + scale * (PADDING_PIXELS + vertexData.getFloat(offset)))
            vertexData.putFloat(
                offset + 4,
                top + scale * (PADDING_PIXELS + TEXT_OFFSET_PIXELS + vertexData.getFloat(offset + 4)),
            )
        }
        vertexData.position(start + textQuads * BYTES_PER_QUAD)
        quads += textQuads
    }

    fun end() {
        uploadedQuads = quads
        vertexData.flip()
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexData)
    }

    fun draw() {
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        program.use()
        program.vec2("uViewport", viewportWidth.toFloat(), viewportHeight.toFloat())
        glBindVertexArray(vao)
        glDrawElements(GL_TRIANGLES, uploadedQuads * 6, GL_UNSIGNED_INT, 0L)
        glDisable(GL_BLEND)
    }

    override fun close() {
        glDeleteBuffers(vertexBuffer)
        glDeleteBuffers(indexBuffer)
        glDeleteVertexArrays(vao)
        program.close()
    }

    companion object {
        const val PADDING_PIXELS = 4f
        // STB glyphs are ~8 px tall inside a 12 px line; centre them.
        const val TEXT_OFFSET_PIXELS = 2f
        private const val VERTEX_BYTES = 16
        private const val BYTES_PER_QUAD = 4 * VERTEX_BYTES

        /** Panel size around [text] laid out in lines of [lineHeight]. */
        fun panelWidth(text: String) = stb_easy_font_width(text) + 2f * PADDING_PIXELS
        fun panelHeight(text: String, lineHeight: Float) =
            text.lineSequence().count() * lineHeight + 2f * PADDING_PIXELS

        // RGBA8 packed little-endian (byte order R, G, B, A in memory).
        fun rgba(r: Int, g: Int, b: Int, a: Int) = r or (g shl 8) or (b shl 16) or (a shl 24)
        val PANEL_COLOR = rgba(25, 25, 25, 170)
    }
}
