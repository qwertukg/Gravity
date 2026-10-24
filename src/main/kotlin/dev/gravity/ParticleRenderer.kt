package dev.gravity

import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_ONE
import org.lwjgl.opengl.GL11.GL_POINTS
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.GL_TEXTURE1
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL30.GL_RG32F
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glCheckFramebufferStatus
import org.lwjgl.opengl.GL30.glDeleteFramebuffers
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.opengl.GL42.glTexStorage2D

/**
 * Draws bodies as points into a float target holding (sum of pressures,
 * body count) per pixel, blurs both channels horizontally, then fills
 * lattice holes and colours the vertically blurred, normalised mean.
 */
class ParticleRenderer(
    viewportWidth: Int,
    viewportHeight: Int,
    private val config: PresentationConfig,
) : AutoCloseable {
    private val splatProgram =
        GlProgram.graphics("/shaders/particles.vert", "/shaders/particles.frag")
    private val blurProgram = GlProgram.graphics(
        "/shaders/particles_resolve.vert",
        "/shaders/particles_blur.frag",
    )
    private val resolveProgram = GlProgram.graphics(
        "/shaders/particles_resolve.vert",
        "/shaders/particles_resolve.frag",
    )
    private val vao = glGenVertexArrays()
    private val accumulation = glGenTextures()
    private val horizontal = glGenTextures()
    private val accumulationFramebuffer = glGenFramebuffers()
    private val horizontalFramebuffer = glGenFramebuffers()

    init {
        attach(accumulationFramebuffer, accumulation, viewportWidth, viewportHeight)
        attach(horizontalFramebuffer, horizontal, viewportWidth, viewportHeight)
        resolveProgram.use()
        resolveProgram.int("uAccumulation", 0)
        resolveProgram.int("uHorizontal", 1)
    }

    fun render(pipeline: GravityPipeline, activeBodies: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, accumulationFramebuffer)
        glClear(GL_COLOR_BUFFER_BIT)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE)
        splatProgram.use()
        splatProgram.float("uMaximumPressure", config.maximumVisualPressure)
        splatProgram.float("uPointSizePixels", config.bodyPointSizePixels)
        pipeline.bindBodiesForRender()
        glBindVertexArray(vao)
        glDrawArrays(GL_POINTS, 0, activeBodies)
        glDisable(GL_BLEND)

        glBindFramebuffer(GL_FRAMEBUFFER, horizontalFramebuffer)
        blurProgram.use()
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, accumulation)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)

        // The resolve writes every pixel, so the default target needs no clear.
        resolveProgram.use()
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, horizontal)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindTexture(GL_TEXTURE_2D, 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    override fun close() {
        glDeleteFramebuffers(horizontalFramebuffer)
        glDeleteFramebuffers(accumulationFramebuffer)
        glDeleteTextures(horizontal)
        glDeleteTextures(accumulation)
        glDeleteVertexArrays(vao)
        resolveProgram.close()
        blurProgram.close()
        splatProgram.close()
    }

    private fun attach(framebuffer: Int, texture: Int, width: Int, height: Int) {
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RG32F, width, height)
        glBindTexture(GL_TEXTURE_2D, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            "Particle render framebuffer is incomplete"
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }
}
