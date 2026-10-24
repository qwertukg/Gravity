package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glGetTexImage
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL30.GL_RG

/** GPU rendering checks; part of gpuTest because it needs an OpenGL context. */
class ParticleRendererTest {
    private val width = 256
    private val height = 128

    @Test
    fun `every body adds exactly its disc area wherever it sits`() {
        CollisionGpuContext().use {
            for (pointSize in listOf(1f, 1.5f, 6f)) {
                val config = GravityConfig.parse(arrayOf("--point-size=$pointSize"), emptyMap())
                    .let { it.copy(particles = it.particles.copy(capacity = 1, initialBodies = 1)) }
                val diameter = config.collisions.contactDiameterPixels
                val area = Math.PI.toFloat() * diameter * diameter / 4f
                GravityPipeline(config, width, height).use { pipeline ->
                    ParticleRenderer(width, height, config.presentation, diameter).use { renderer ->
                        // Centred on a pixel, on a pixel corner, and in between.
                        for (center in listOf(100.5f to 60.5f, 100f to 60f, 100.3f to 60.7f)) {
                            CollisionGpuAccess.upload(pipeline, floatArrayOf(center.first / width, center.second / height, 0f, 0f))
                            glViewport(0, 0, width, height)
                            renderer.render(pipeline, 1)
                            val accumulation = renderer.javaClass.getDeclaredField("accumulation")
                                .apply { isAccessible = true }.getInt(renderer)
                            val data = BufferUtils.createFloatBuffer(width * height * 2)
                            glBindTexture(GL_TEXTURE_2D, accumulation)
                            glGetTexImage(GL_TEXTURE_2D, 0, GL_RG, GL_FLOAT, data)
                            var coverage = 0f
                            for (i in 0 until width * height) {
                                val pixel = data.get(i * 2 + 1)
                                assertTrue(pixel in 0f..1f, "pixel coverage $pixel")
                                coverage += pixel
                            }
                            // 8x8 subsamples: within 8% of the area even at point size 1.
                            assertEquals(area, coverage, area * 0.08f, "point size $pointSize at $center")
                        }
                    }
                }
            }
        }
    }
}
