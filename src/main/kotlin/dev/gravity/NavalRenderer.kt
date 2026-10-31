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
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL30.GL_RGBA32F
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glCheckFramebufferStatus
import org.lwjgl.opengl.GL30.glDeleteFramebuffers
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.opengl.GL32.GL_PROGRAM_POINT_SIZE
import org.lwjgl.opengl.GL42.glTexStorage2D
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The naval battle's picture: every body in the colour of what it is
 * (naval_particles.vert) — water by depth and foam, grey ships, glowing fire —
 * as discs of its contact diameter, averaged per pixel over a sky. Like
 * ParticleRenderer, without the pressure ramp.
 */
internal class NavalRenderer(
    viewportWidth: Int,
    viewportHeight: Int,
    bodyDiameterPixels: Float,
) : AutoCloseable {
    private val splatProgram = GlProgram.graphics("/shaders/naval_particles.vert", "/shaders/naval_particles.frag")
    private val resolveProgram = GlProgram.graphics("/shaders/particles_resolve.vert", "/shaders/naval_resolve.frag")
    private val vao = glGenVertexArrays()
    private val accumulation = glGenTextures()
    private val framebuffer = glGenFramebuffers()

    init {
        glBindTexture(GL_TEXTURE_2D, accumulation)
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, viewportWidth, viewportHeight)
        glBindTexture(GL_TEXTURE_2D, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, accumulation, 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Naval render framebuffer is incomplete" }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        splatProgram.use()
        splatProgram.float("uRadiusPixels", 0.5f * bodyDiameterPixels)
        splatProgram.float("uFireRadiusPixels", FIRE_GLOW_DIAMETERS * 0.5f * bodyDiameterPixels)
        splatProgram.vec2("uViewport", viewportWidth.toFloat(), viewportHeight.toFloat())
        resolveProgram.use()
        resolveProgram.int("uAccumulation", 0)
        resolveProgram.int("uGapPixels", max(1, (0.25f * bodyDiameterPixels).roundToInt()))
        resolveProgram.vec2("uViewport", viewportWidth.toFloat(), viewportHeight.toFloat())
    }

    fun render(pipeline: GravityPipeline, activeBodies: Int, battle: NavalBattle, target: Int = 0) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glClear(GL_COLOR_BUFFER_BIT)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE)
        splatProgram.use()
        splatProgram.float("uSurface", battle.surface)
        splatProgram.float("uDepth", battle.surface)
        splatProgram.float("uStep", battle.steps.toFloat())
        splatProgram.float("uDt", battle.timeStep)
        splatProgram.float("uFireLife", NavalBattle.FIRE_LIFE_SECONDS)
        splatProgram.float("uFireMass", NavalBattle.FIRE_MASS)
        splatProgram.float("uFragmentCool", NavalBattle.FRAGMENT_COOL_SECONDS)
        val barrels = battle.barrelGroups()
        splatProgram.int("uBarrels", barrels.size)
        barrels.forEachIndexed { index, group -> splatProgram.int("uBarrel[$index]", group) }
        val missiles = battle.missileGroups()
        splatProgram.int("uMissiles", missiles.size)
        missiles.forEachIndexed { index, group -> splatProgram.int("uMissile[$index]", group) }
        pipeline.bindForNavalRender()
        glBindVertexArray(vao)
        glEnable(GL_PROGRAM_POINT_SIZE)
        glDrawArrays(GL_POINTS, 0, activeBodies)
        glDisable(GL_PROGRAM_POINT_SIZE)
        glDisable(GL_BLEND)

        glBindFramebuffer(GL_FRAMEBUFFER, target)
        resolveProgram.use()
        resolveProgram.float("uSurface", battle.surface)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, accumulation)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    private companion object {
        /** Fire glows this many body diameters wide. */
        const val FIRE_GLOW_DIAMETERS = 7f
    }

    override fun close() {
        glDeleteFramebuffers(framebuffer)
        glDeleteTextures(accumulation)
        glDeleteVertexArrays(vao)
        resolveProgram.close()
        splatProgram.close()
    }
}
