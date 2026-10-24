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
    private val config: PresentationConfig,
    projectileConfig: ProjectileConfig,
) : AutoCloseable {
    private val controlText =
        "LMB drag: aim / fire\n" +
            "1-9: bullet size step ${projectileConfig.digitDiameterStepPixels.toInt()} px\n" +
            "0: bullet size ${projectileConfig.maximumDiameterPixels.toInt()} px\n" +
            "+: bullet of ${projectileConfig.plusKeyBodyCount} bodies\n" +
            "Mouse wheel: time speed\n" +
            "P: pause / resume\n" +
            "S: stop all bodies\n" +
            "R: reset\n" +
            "Esc: exit"
    private val controlLines = controlText.lineSequence().count()
    private val maximumCharacters = config.overlayMaximumCharacters
    private val maximumQuads = maximumCharacters * 16
    private val vertexData = BufferUtils.createByteBuffer(maximumQuads * 4 * 16)
    private val program = GlProgram.graphics("/shaders/overlay.vert", "/shaders/overlay.frag")
    private val vao = glGenVertexArrays()
    private val vertexBuffer = glGenBuffers()
    private val indexBuffer = glGenBuffers()
    private var renderedFpsBits = Long.MIN_VALUE
    private var renderedActiveBodies = -1
    private var renderedProjectileBodies = -1
    private var renderedTimeScaleBits = Long.MIN_VALUE
    private var renderedFutureSpeedBits = Long.MIN_VALUE
    private var renderedQuads = 0

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
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0L)
        glBindVertexArray(0)
    }

    fun render(
        fps: Double,
        activeBodies: Int,
        projectileBodies: Int,
        timeScale: Double,
        futureProjectileSpeed: Double?,
    ) {
        val fpsBits = fps.toRawBits()
        val timeScaleBits = timeScale.toRawBits()
        val futureSpeedBits = futureProjectileSpeed?.toRawBits() ?: NO_SPEED_BITS
        if (fpsBits != renderedFpsBits ||
            activeBodies != renderedActiveBodies ||
            projectileBodies != renderedProjectileBodies ||
            timeScaleBits != renderedTimeScaleBits ||
            futureSpeedBits != renderedFutureSpeedBits
        ) {
            val projectileSpeed = futureProjectileSpeed?.let {
                String.format(Locale.ROOT, "%.1f px/s", it)
            } ?: "-"
            val text = String.format(
                Locale.ROOT,
                "FPS: %.1f\nBodies: %d\nBullet bodies: %d\ndt: %.0f%%\nBullet speed: %s",
                fps,
                activeBodies,
                projectileBodies,
                timeScale * 100.0,
                projectileSpeed,
            )
            vertexData.clear()
            val informationQuads = stb_easy_font_print(
                0f,
                0f,
                text,
                null as ByteBuffer?,
                vertexData,
            )
            vertexData.position(informationQuads * BYTES_PER_QUAD)
            val controlsQuads = stb_easy_font_print(
                0f,
                viewportHeight.toFloat() -
                    2f * config.overlayMarginPixels -
                    controlLines * config.overlayLineHeightPixels,
                controlText,
                null as ByteBuffer?,
                vertexData,
            )
            renderedQuads = informationQuads + controlsQuads
            vertexData.position(0)
            vertexData.limit(renderedQuads * BYTES_PER_QUAD)
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
            glBufferSubData(GL_ARRAY_BUFFER, 0, vertexData)
            renderedFpsBits = fpsBits
            renderedActiveBodies = activeBodies
            renderedProjectileBodies = projectileBodies
            renderedTimeScaleBits = timeScaleBits
            renderedFutureSpeedBits = futureSpeedBits
        }

        program.use()
        program.vec2("uViewport", viewportWidth.toFloat(), viewportHeight.toFloat())
        program.float("uMarginPixels", config.overlayMarginPixels)
        glBindVertexArray(vao)
        glDrawElements(GL_TRIANGLES, renderedQuads * 6, GL_UNSIGNED_INT, 0L)
    }

    override fun close() {
        glDeleteBuffers(vertexBuffer)
        glDeleteBuffers(indexBuffer)
        glDeleteVertexArrays(vao)
        program.close()
    }

    private companion object {
        const val BYTES_PER_QUAD = 4 * 16
        const val NO_SPEED_BITS = Long.MIN_VALUE + 1
    }
}
