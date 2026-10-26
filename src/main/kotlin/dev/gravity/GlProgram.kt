package dev.gravity

import org.lwjgl.opengl.GL20.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20.GL_LINK_STATUS
import org.lwjgl.opengl.GL20.glAttachShader
import org.lwjgl.opengl.GL20.glCompileShader
import org.lwjgl.opengl.GL20.glCreateProgram
import org.lwjgl.opengl.GL20.glCreateShader
import org.lwjgl.opengl.GL20.glDeleteProgram
import org.lwjgl.opengl.GL20.glDeleteShader
import org.lwjgl.opengl.GL20.glGetProgramInfoLog
import org.lwjgl.opengl.GL20.glGetProgrami
import org.lwjgl.opengl.GL20.glGetShaderInfoLog
import org.lwjgl.opengl.GL20.glGetShaderi
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glLinkProgram
import org.lwjgl.opengl.GL20.glShaderSource
import org.lwjgl.opengl.GL20.glUniform1f
import org.lwjgl.opengl.GL20.glUniform1i
import org.lwjgl.opengl.GL30.glUniform1ui
import org.lwjgl.opengl.GL20.glUniform2f
import org.lwjgl.opengl.GL20.glUniform2i
import org.lwjgl.opengl.GL20.glUseProgram

class GlProgram private constructor(private val id: Int) : AutoCloseable {
    private class UniformState(val location: Int) {
        var initialized = false
        var first = 0
        var second = 0
    }

    private val uniforms = HashMap<String, UniformState>()
    private val inactive = HashSet<String>()

    /** Whether the linked program uses uniform [name]. */
    fun has(name: String): Boolean {
        if (name in uniforms) return true
        if (name in inactive) return false
        val location = glGetUniformLocation(id, name)
        if (location < 0) {
            inactive += name
            return false
        }
        uniforms[name] = UniformState(location)
        return true
    }

    fun ivec2(name: String, x: Int, y: Int) {
        val uniform = uniform(name)
        if (!uniform.initialized || uniform.first != x || uniform.second != y) {
            glUniform2i(uniform.location, x, y)
            uniform.first = x
            uniform.second = y
            uniform.initialized = true
        }
    }

    fun use() {
        if (activeProgram != id) {
            glUseProgram(id)
            activeProgram = id
        }
    }

    fun int(name: String, value: Int) {
        val uniform = uniform(name)
        if (!uniform.initialized || uniform.first != value) {
            glUniform1i(uniform.location, value)
            uniform.first = value
            uniform.initialized = true
        }
    }

    fun uint(name: String, value: Int) {
        val uniform = uniform(name)
        if (!uniform.initialized || uniform.first != value) {
            glUniform1ui(uniform.location, value)
            uniform.first = value
            uniform.initialized = true
        }
    }

    fun float(name: String, value: Float) {
        val uniform = uniform(name)
        val bits = value.toRawBits()
        if (!uniform.initialized || uniform.first != bits) {
            glUniform1f(uniform.location, value)
            uniform.first = bits
            uniform.initialized = true
        }
    }

    fun vec2(name: String, x: Float, y: Float) {
        val uniform = uniform(name)
        val first = x.toRawBits()
        val second = y.toRawBits()
        if (!uniform.initialized ||
            uniform.first != first ||
            uniform.second != second) {
            glUniform2f(uniform.location, x, y)
            uniform.first = first
            uniform.second = second
            uniform.initialized = true
        }
    }

    private fun uniform(name: String): UniformState =
        uniforms.getOrPut(name) {
            val location = glGetUniformLocation(id, name)
            require(location >= 0) { "Uniform '$name' is not active" }
            UniformState(location)
        }

    override fun close() {
        if (activeProgram == id) stopUsing()
        glDeleteProgram(id)
    }

    companion object {
        private var activeProgram = 0

        fun stopUsing() {
            if (activeProgram != 0) {
                glUseProgram(0)
                activeProgram = 0
            }
        }

        /** [defines] are names defined right after the #version line. */
        fun compute(resource: String, vararg defines: String): GlProgram =
            link(compile(org.lwjgl.opengl.GL43.GL_COMPUTE_SHADER, resource, defines.toList()))

        fun graphics(vertexResource: String, fragmentResource: String): GlProgram =
            link(
                compile(org.lwjgl.opengl.GL20.GL_VERTEX_SHADER, vertexResource),
                compile(org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER, fragmentResource),
            )

        private fun compile(type: Int, resource: String, defines: List<String> = emptyList()): Int {
            // The #version line comes first; the defines follow it.
            val included = shaderSource(resource)
            val source = if (defines.isEmpty()) included else
                included.substringBefore('\n') + defines.joinToString("") { "\n#define $it" } +
                    "\n" + included.substringAfter('\n')
            val shader = glCreateShader(type)
            glShaderSource(shader, source)
            glCompileShader(shader)
            check(glGetShaderi(shader, GL_COMPILE_STATUS) != 0) {
                "Shader $resource failed:\n${glGetShaderInfoLog(shader)}"
            }
            return shader
        }

        private fun shaderSource(resource: String, parents: Set<String> = emptySet()): String {
            check(resource !in parents) { "Cyclic shader include: $resource" }
            val source = GlProgram::class.java.getResource(resource)?.readText()
                ?: error("Missing shader resource: $resource")
            return Regex("(?m)^#include \"([^\"]+)\"\\s*$").replace(source) { match ->
                shaderSource(resource.substringBeforeLast('/') + "/" + match.groupValues[1], parents + resource)
            }
        }

        private fun link(vararg shaders: Int): GlProgram {
            val program = glCreateProgram()
            shaders.forEach { glAttachShader(program, it) }
            glLinkProgram(program)
            shaders.forEach(::glDeleteShader)
            check(glGetProgrami(program, GL_LINK_STATUS) != 0) {
                "Program link failed:\n${glGetProgramInfoLog(program)}"
            }
            return GlProgram(program)
        }
    }
}
