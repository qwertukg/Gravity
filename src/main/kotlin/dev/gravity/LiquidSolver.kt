package dev.gravity

import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL30.GL_R32UI
import org.lwjgl.opengl.GL30.GL_RED_INTEGER
import org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glClearBufferData
import org.lwjgl.opengl.GL43.glDispatchCompute
import org.lwjgl.system.MemoryUtil.memAllocInt
import org.lwjgl.system.MemoryUtil.memFree
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Liquid mode: a position-based fluid (Macklin & Mueller 2013). Once per
 * physics step, after the collision substeps have integrated every body,
 * the density constraint of each fluid body is solved by Jacobi iterations
 * on a neighbour grid built once for the step. Corrections become velocity
 * as dv = dx / dt, then XSPH viscosity smooths the flow.
 *
 * Fluid bodies do not collide as hard discs, so they never lock into a
 * packing and flow under any pressure. Block bodies are solid: they count in
 * the density around them and take the momentum-conserving reaction of the
 * liquid, so a block floats or sinks by its density.
 */
internal class LiquidSolver(
    private val config: LiquidConfig,
    contactDiameterPixels: Float,
    capacity: Int,
    private val width: Int,
    private val height: Int,
    private val timeStep: Float,
    private val bind: (Int, Int) -> Unit,
) : AutoCloseable {
    val kernel = LiquidKernel(contactDiameterPixels, config.kernelRadiusDiameters)
    // Cells no smaller than the kernel radius: a 3 x 3 stencil is complete.
    private val gridWidth = max(1, (width / kernel.radiusPixels).toInt())
    private val gridHeight = max(1, (height / kernel.radiusPixels).toInt())
    private val heads = buffer(gridWidth.toLong() * gridHeight * Int.SIZE_BYTES)
    private val next = buffer(capacity.toLong() * Int.SIZE_BYTES)
    /** Grid cell of every body when the grid was built. */
    private val cells = buffer(capacity.toLong() * Int.SIZE_BYTES)
    private val lambdas = buffer(capacity.toLong() * Float.SIZE_BYTES)
    private val grid = GlProgram.compute("/shaders/liquid_grid.comp")
    private val lambda = GlProgram.compute("/shaders/liquid_lambda.comp")
    private val apply = GlProgram.compute("/shaders/liquid_apply.comp")
    private val viscosity = GlProgram.compute("/shaders/liquid_viscosity.comp")

    init {
        // Each program gets only the uniforms its shader actually uses.
        listOf(grid, lambda, apply, viscosity).forEach { program ->
            program.use()
            program.int("uGridWidth", gridWidth)
            program.int("uGridHeight", gridHeight)
        }
        listOf(lambda, apply, viscosity).forEach { program ->
            program.use()
            program.vec2("uViewport", width.toFloat(), height.toFloat())
            program.float("uKernelRadius", kernel.radiusPixels)
            program.float("uRestDensity", kernel.restDensity)
        }
        listOf(lambda, apply, viscosity).forEach { program ->
            program.use()
            program.float("uPoly6", kernel.poly6Coefficient)
        }
        listOf(lambda, apply).forEach { program ->
            program.use()
            program.float("uSpikyGradient", kernel.spikyGradientCoefficient)
            // Virtual wall rows continue the hexagonal packing.
            program.float("uBodyDiameter", contactDiameterPixels)
            program.float("uRowStep", contactDiameterPixels * sqrt(3f) * 0.5f)
        }
        lambda.use()
        lambda.float("uRelaxation", kernel.relaxation)
        // Compression shown like contact pressure: px/s of correction.
        lambda.float("uPressureScale", kernel.radiusPixels / timeStep)
        apply.use()
        apply.float("uDt", timeStep)
        apply.float("uBodyRadius", 0.5f * contactDiameterPixels)
        viscosity.use()
        viscosity.float("uViscosity", config.viscosity)
    }

    /**
     * Solves the liquid for bodies in [input], ping-ponging with [spare], and
     * returns the buffer that holds the result.
     */
    fun resolve(
        input: Int,
        spare: Int,
        materials: Int,
        pressures: Int,
        active: Int,
        bounded: Boolean,
    ): Int {
        val boundedFlag = if (bounded) 1 else 0
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
        val empty = memAllocInt(1).put(0, -1)
        try {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, heads)
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, empty)
        } finally {
            memFree(empty)
        }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_BUFFER_UPDATE_BARRIER_BIT)

        grid.use()
        grid.int("uCount", active)
        bind(0, input)
        bind(1, heads)
        bind(2, next)
        bind(6, cells)
        dispatch(active)

        var current = input
        var other = spare
        bind(3, lambdas)
        bind(4, pressures)
        bind(8, materials)
        repeat(config.iterations) { iteration ->
            lambda.use()
            lambda.int("uCount", active)
            lambda.int("uBounded", boundedFlag)
            lambda.int("uWritePressure", if (iteration == config.iterations - 1) 1 else 0)
            bind(0, current)
            dispatch(active)

            apply.use()
            apply.int("uCount", active)
            apply.int("uBounded", boundedFlag)
            bind(5, other)
            dispatch(active)
            current = other.also { other = current }
        }

        if (config.viscosity > 0f) {
            viscosity.use()
            viscosity.int("uCount", active)
            viscosity.int("uBounded", boundedFlag)
            bind(0, current)
            bind(5, other)
            dispatch(active)
            current = other
        }
        return current
    }

    private fun dispatch(items: Int) {
        glDispatchCompute((items + 255) / 256, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
    }

    override fun close() {
        listOf(grid, lambda, apply, viscosity).forEach(GlProgram::close)
        intArrayOf(heads, next, cells, lambdas).forEach(::glDeleteBuffers)
    }

    private companion object {
        fun buffer(bytes: Long): Int = glGenBuffers().also {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
            glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW)
        }
    }
}
