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
import java.nio.ByteBuffer

/** Column of scenario buttons in the top-right corner, in window pixels. */
class ScenarioMenu(
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val config: PresentationConfig,
) : AutoCloseable {
    private class Button(
        val scenario: Scenario,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun contains(x: Float, y: Float) = x in left..<right && y in top..<bottom
    }

    private val buttonWidth =
        Scenario.entries.maxOf { stb_easy_font_width(it.title) } + 2f * PADDING_PIXELS
    private val buttonHeight =
        config.overlayLineHeightPixels + 2f * PADDING_PIXELS
    private val buttons = Scenario.entries.mapIndexed { index, scenario ->
        val left = viewportWidth - config.overlayMarginPixels - buttonWidth
        val top = config.overlayMarginPixels + index * (buttonHeight + GAP_PIXELS)
        Button(scenario, left, top, left + buttonWidth, top + buttonHeight)
    }
    private val vertexData = BufferUtils.createByteBuffer(MAXIMUM_QUADS * BYTES_PER_QUAD)
    // Same green as the information overlay (overlay.frag).
    private val textColor = BufferUtils.createByteBuffer(4).put(0).put(-1).put(0).put(-1).flip()
    private val program = GlProgram.graphics("/shaders/ui.vert", "/shaders/ui.frag")
    private val vao = glGenVertexArrays()
    private val vertexBuffer = glGenBuffers()
    private val indexBuffer = glGenBuffers()
    private var renderedActive: Scenario? = null
    private var renderedHover: Scenario? = null
    private var renderedQuads = -1

    init {
        val indices = BufferUtils.createIntBuffer(MAXIMUM_QUADS * 6)
        repeat(MAXIMUM_QUADS) { quad ->
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

    /** Scenario under a window-space point (origin top-left), if any. */
    fun scenarioAt(x: Double, y: Double): Scenario? =
        buttons.firstOrNull { it.contains(x.toFloat(), y.toFloat()) }?.scenario

    fun render(active: Scenario?, cursorX: Double, cursorY: Double) {
        val hover = scenarioAt(cursorX, cursorY)
        if (renderedQuads < 0 || active != renderedActive || hover != renderedHover) {
            rebuild(active, hover)
            renderedActive = active
            renderedHover = hover
        }
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        program.use()
        program.vec2("uViewport", viewportWidth.toFloat(), viewportHeight.toFloat())
        glBindVertexArray(vao)
        glDrawElements(GL_TRIANGLES, renderedQuads * 6, GL_UNSIGNED_INT, 0L)
        glDisable(GL_BLEND)
    }

    private fun rebuild(active: Scenario?, hover: Scenario?) {
        vertexData.clear()
        var quads = 0
        for (button in buttons) {
            val color = when (button.scenario) {
                active -> ACTIVE_COLOR
                hover -> HOVER_COLOR
                else -> IDLE_COLOR
            }
            quad(button.left, button.top, button.right, button.bottom, color)
            quads++
            val textStart = vertexData.position()
            val textQuads = stb_easy_font_print(
                0f,
                0f,
                button.scenario.title,
                textColor,
                vertexData.slice(),
            )
            // Glyphs are emitted from the origin; move them into the button.
            for (vertex in 0 until textQuads * 4) {
                val offset = textStart + vertex * VERTEX_BYTES
                vertexData.putFloat(offset, button.left + PADDING_PIXELS +
                    vertexData.getFloat(offset))
                vertexData.putFloat(offset + 4, button.top + PADDING_PIXELS + TEXT_OFFSET_PIXELS +
                    vertexData.getFloat(offset + 4))
            }
            vertexData.position(textStart + textQuads * BYTES_PER_QUAD)
            quads += textQuads
        }
        renderedQuads = quads
        vertexData.flip()
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexData)
    }

    private fun quad(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        for ((x, y) in listOf(left to top, right to top, right to bottom, left to bottom)) {
            vertexData.putFloat(x).putFloat(y).putFloat(0f).putInt(color)
        }
    }

    override fun close() {
        glDeleteBuffers(vertexBuffer)
        glDeleteBuffers(indexBuffer)
        glDeleteVertexArrays(vao)
        program.close()
    }

    private companion object {
        const val PADDING_PIXELS = 4f
        // STB glyphs are ~8 px tall inside a 12 px line; centre them.
        const val TEXT_OFFSET_PIXELS = 2f
        const val GAP_PIXELS = 3f
        const val VERTEX_BYTES = 16
        const val BYTES_PER_QUAD = 4 * VERTEX_BYTES
        const val MAXIMUM_QUADS = 8192

        // RGBA8 packed little-endian (byte order R, G, B, A in memory).
        fun rgba(r: Int, g: Int, b: Int, a: Int) = r or (g shl 8) or (b shl 16) or (a shl 24)
        val IDLE_COLOR = rgba(25, 25, 25, 170)
        val HOVER_COLOR = rgba(60, 60, 60, 210)
        val ACTIVE_COLOR = rgba(0, 75, 0, 220)
    }
}
