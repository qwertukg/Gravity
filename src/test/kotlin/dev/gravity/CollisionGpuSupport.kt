package dev.gravity

import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL42.*
import org.lwjgl.opengl.GL43.*
import java.nio.ByteBuffer

/** Explicit GPU tasks fail if the required context is unavailable; never skip silently. */
internal class CollisionGpuContext : AutoCloseable {
    private val errors = GLFWErrorCallback.createPrint(System.err).set()
    private val window: Long
    init {
        check(glfwInit()) { "GLFW unavailable: gpuTest requires an OpenGL 4.3 driver" }
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        window = glfwCreateWindow(64, 64, "Gravity GPU verification", 0, 0)
        check(window != 0L) { "Cannot create hidden GPU verification context" }
        glfwMakeContextCurrent(window)
        GL.createCapabilities()
        check(GL.getCapabilities().OpenGL43)
        println("GPU: ${glGetString(GL_RENDERER)}; ${glGetString(GL_VERSION)}")
    }

    override fun close() {
        GlProgram.stopUsing()
        GL.setCapabilities(null)
        glfwMakeContextCurrent(0)
        glfwDestroyWindow(window)
        glfwTerminate()
        glfwSetErrorCallback(null)
        errors.free()
    }
}

internal object CollisionGpuAccess {
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(target)
    }
    fun buffer(pipeline: GravityPipeline, name: String): Int = field(pipeline, name) as Int

    fun upload(pipeline: GravityPipeline, bodies: FloatArray, clearHeads: Boolean = false) {
        (field(pipeline, "boundStorageBuffers") as IntArray).fill(-1)
        // Tests replay the same input into reference/optimized pipelines without
        // adding diagnostic hooks or readbacks to the production frame loop.
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        for (name in listOf("bodies", "collisionBodies")) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer(pipeline, name))
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, bodies)
        }
        if (clearHeads) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer(pipeline, "collisionHeads"))
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, null as ByteBuffer?)
        }
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
    }

    fun collide(pipeline: GravityPipeline, count: Int, drift: Float = 0f, kick: Float = 0f, pressure: Boolean = true, substep: Int? = null) {
        (field(pipeline, "boundStorageBuffers") as IntArray).fill(-1)
        val method = GravityPipeline::class.java.declaredMethods.single { it.name == "resolveCollisions" }
        method.isAccessible = true
        method.invoke(pipeline, count, kick, drift, pressure, substep)
    }

    fun adaptiveSteps(pipeline: GravityPipeline, active: Int, selected: Int, maximum: Int) {
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer(pipeline, "collisionAdaptiveState"))
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, intArrayOf(0, 0, selected, 0, 0))
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer(pipeline, "collisionDispatchCommands"))
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, IntArray(maximum * 3) { i ->
            if (i % 3 == 0 && i / 3 < selected) (active + 255) / 256 else 1
        })
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
    }

    fun floats(pipeline: GravityPipeline, name: String, length: Int): FloatArray = floats(buffer(pipeline, name), length)
    fun floats(buffer: Int, length: Int): FloatArray {
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        val mapped = glMapBuffer(GL_SHADER_STORAGE_BUFFER, GL_READ_ONLY, length.toLong() * 4, null)!!.asFloatBuffer()
        val result = FloatArray(length)
        mapped.get(result)
        check(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))
        return result
    }

    fun ints(pipeline: GravityPipeline, name: String, length: Int): IntArray = ints(buffer(pipeline, name), length)
    fun ints(buffer: Int, length: Int): IntArray {
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        val mapped = glMapBuffer(GL_SHADER_STORAGE_BUFFER, GL_READ_ONLY, length.toLong() * 4, null)!!.asIntBuffer()
        val result = IntArray(length)
        mapped.get(result)
        check(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))
        return result
    }

    fun rebuilds(pipeline: GravityPipeline): Int {
        val cache = field(pipeline, "neighborCache") ?: error("Cache was not allocated")
        return ints(field(cache, "state") as Int, 3)[2]
    }

    fun directGrid(pipeline: GravityPipeline): Boolean {
        val cache = field(pipeline, "neighborCache") ?: error("Cache was not allocated")
        return ints(field(cache, "state") as Int, 5)[3] != 0
    }

    fun cachedContacts(pipeline: GravityPipeline, capacity: Int, count: Int): Array<IntArray> {
        val neighborCache = field(pipeline, "neighborCache") ?: error("Cache was not allocated")
        val data = ints(field(neighborCache, "cache") as Int, capacity * 42)
        val counts = ints(pipeline, "collisionContactCounts", count)
        return Array(count) { id ->
            IntArray(minOf(counts[id], 8)) { slot -> data[(34 + slot) * capacity + id] }
        }
    }
}
