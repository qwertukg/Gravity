package dev.gravity

import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.GL_WRITE_ONLY
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL15.glMapBuffer
import org.lwjgl.opengl.GL15.glUnmapBuffer
import org.lwjgl.opengl.GL30.glBindBufferBase
import org.lwjgl.opengl.GL30.GL_R32F
import org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER
import org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER
import org.lwjgl.opengl.GL31.glCopyBufferSubData
import org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT
import org.lwjgl.opengl.GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glClearBufferData
import org.lwjgl.opengl.GL43.glDispatchCompute
import java.nio.ByteBuffer
import kotlin.math.ceil

class GravityPipeline(
    private val config: SimulationConfig,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
) : AutoCloseable {
    private val gridWidth = config.gridWidth
    private val gridHeight = config.gridHeight
    private val physicalGridWidth = gridWidth / 2
    private val physicalGridHeight = gridHeight / 2
    private val cells = gridWidth * gridHeight
    private val physicalCells = physicalGridWidth * physicalGridHeight
    private val logGridWidth = gridWidth.countTrailingZeroBits()
    private val logGridHeight = gridHeight.countTrailingZeroBits()
    private val domainAspect = viewportWidth.toFloat() / viewportHeight
    private val collisionGridWidth = viewportWidth
    private val collisionGridHeight = viewportHeight
    private val collisionCells = Math.multiplyExact(collisionGridWidth, collisionGridHeight)
    private val bodyBytes = config.capacity.toLong() * 4 * Float.SIZE_BYTES
    private val accelerationBytes = config.capacity.toLong() * 2 * Float.SIZE_BYTES
    private val complexBytes = cells.toLong() * 2 * Float.SIZE_BYTES

    private val bodies = buffer(bodyBytes)
    private val masses = buffer(config.capacity.toLong() * Float.SIZE_BYTES)
    private val acceleration = buffer(accelerationBytes)
    private val density = buffer(cells.toLong() * Int.SIZE_BYTES)
    private val cellHeads = buffer(physicalCells.toLong() * Int.SIZE_BYTES)
    private val cellCounts = buffer(physicalCells.toLong() * Int.SIZE_BYTES)
    private val cellMass = buffer(physicalCells.toLong() * Int.SIZE_BYTES)
    private val nextBody = buffer(config.capacity.toLong() * Int.SIZE_BYTES)
    private val collisionHeads = buffer(collisionCells.toLong() * Int.SIZE_BYTES)
    private val collisionDeltas = buffer(config.capacity.toLong() * 4 * Int.SIZE_BYTES)
    private val collisionContacts = buffer(config.capacity.toLong() * Int.SIZE_BYTES)
    private val fft = buffer(complexBytes)
    private val scratch = buffer(complexBytes)
    private val forceX = buffer(complexBytes)
    private val forceY = buffer(complexBytes)
    private val kernelX = buffer(complexBytes)
    private val kernelY = buffer(complexBytes)

    private val clear = GlProgram.compute("/shaders/clear_grid.comp")
    private val build = GlProgram.compute("/shaders/build_grid.comp")
    private val fftInit = GlProgram.compute("/shaders/fft_init.comp")
    private val permute = GlProgram.compute("/shaders/fft_permute.comp")
    private val fftStage = GlProgram.compute("/shaders/fft_stage.comp")
    private val kernelInit = GlProgram.compute("/shaders/kernel_init.comp")
    private val convolve = GlProgram.compute("/shaders/convolve.comp")
    private val drift = GlProgram.compute("/shaders/drift.comp")
    private val kick = GlProgram.compute("/shaders/kick.comp")
    private val collisionClear = GlProgram.compute("/shaders/collision_clear.comp")
    private val collisionBuild = GlProgram.compute("/shaders/collision_build.comp")
    private val collisionCount = GlProgram.compute("/shaders/collision_count.comp")
    private val collisionResolve = GlProgram.compute("/shaders/collision_resolve.comp")
    private val collisionApply = GlProgram.compute("/shaders/collision_apply.comp")
    private lateinit var massPrefix: DoubleArray
    private var normalizationMass = 0f
    private var initialSeed: Long? = null

    init {
        seedBodies()
        clearFloatBuffer(acceleration)
        initializeKernel()
    }

    fun initializeAcceleration(activeBodies: Int) {
        resolveCollisions(activeBodies)
        buildForce(activeBodies)
        applyKick(activeBodies, initial = true)
    }

    fun reset(activeBodies: Int) {
        seedBodies()
        clearFloatBuffer(acceleration)
        initializeAcceleration(activeBodies)
    }

    fun step(activeBodies: Int) {
        if (config.collisionIterations > 0) clearCollisionGrid(activeBodies)
        drift.use()
        bind(0, bodies)
        bind(1, acceleration)
        bind(2, collisionHeads)
        bind(3, nextBody)
        drift.int("uCount", activeBodies)
        drift.float("uDt", config.timeStep)
        drift.int("uCollisionWidth", collisionGridWidth)
        drift.int("uCollisionHeight", collisionGridHeight)
        drift.int("uBuildCollisionGrid", if (config.collisionIterations > 0) 1 else 0)
        dispatchBodies(activeBodies)
        barrier()

        if (config.collisionIterations > 0) {
            resolveCollisionPairs(activeBodies)
            applyCollisionDeltas(activeBodies)
            repeat(config.collisionIterations - 1) {
                clearCollisionGrid(activeBodies)
                buildCollisionGrid(activeBodies)
                resolveCollisionPairs(activeBodies)
                applyCollisionDeltas(activeBodies)
            }
        }
        buildForce(activeBodies)
        applyKick(activeBodies, initial = false)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT)
    }

    fun bindBodiesForRender() {
        bind(0, bodies)
    }

    private fun resolveCollisions(activeBodies: Int) {
        repeat(config.collisionIterations) {
            clearCollisionGrid(activeBodies)
            buildCollisionGrid(activeBodies)
            resolveCollisionPairs(activeBodies)
            applyCollisionDeltas(activeBodies)
        }
    }

    private fun clearCollisionGrid(activeBodies: Int) {
        collisionClear.use()
        bind(0, collisionHeads)
        bind(1, collisionDeltas)
        bind(2, collisionContacts)
        collisionClear.int("uCells", collisionCells)
        collisionClear.int("uCount", activeBodies)
        dispatch(maxOf(collisionCells, activeBodies))
        barrier()
    }

    private fun buildCollisionGrid(activeBodies: Int) {
        collisionBuild.use()
        bind(0, bodies)
        bind(1, collisionHeads)
        bind(2, nextBody)
        collisionBuild.int("uCount", activeBodies)
        collisionBuild.int("uGridWidth", collisionGridWidth)
        collisionBuild.int("uGridHeight", collisionGridHeight)
        dispatchBodies(activeBodies)
        barrier()
    }

    private fun resolveCollisionPairs(activeBodies: Int) {
        collisionCount.use()
        bind(0, bodies)
        bind(1, collisionHeads)
        bind(2, nextBody)
        bind(3, collisionContacts)
        collisionCount.int("uCount", activeBodies)
        collisionCount.int("uWidth", collisionGridWidth)
        collisionCount.int("uHeight", collisionGridHeight)
        dispatchBodies(activeBodies)
        barrier()

        collisionResolve.use()
        bind(0, bodies)
        bind(1, collisionHeads)
        bind(2, nextBody)
        bind(3, collisionDeltas)
        bind(4, collisionContacts)
        collisionResolve.int("uCount", activeBodies)
        collisionResolve.int("uViewportWidth", viewportWidth)
        collisionResolve.int("uViewportHeight", viewportHeight)
        collisionResolve.int("uGridWidth", collisionGridWidth)
        collisionResolve.int("uGridHeight", collisionGridHeight)
        collisionResolve.int("uCellSizePixels", 1)
        dispatchBodies(activeBodies)
        barrier()
    }

    private fun applyCollisionDeltas(activeBodies: Int) {
        collisionApply.use()
        bind(0, bodies)
        bind(1, collisionDeltas)
        collisionApply.int("uCount", activeBodies)
        collisionApply.int("uWidth", viewportWidth)
        collisionApply.int("uHeight", viewportHeight)
        dispatchBodies(activeBodies)
        barrier()
    }

    private fun buildForce(activeBodies: Int) {
        val totalMass = massPrefix[activeBodies].toFloat()
        val massScale = massScaleFor(totalMass)
        clear.use()
        bind(0, density)
        bind(1, cellHeads)
        bind(2, cellCounts)
        bind(3, cellMass)
        clear.int("uMeshCells", cells)
        clear.int("uParticleCells", physicalCells)
        dispatch(cells)
        barrier()

        build.use()
        bind(0, bodies)
        bind(1, density)
        bind(2, cellHeads)
        bind(3, nextBody)
        bind(4, masses)
        bind(5, cellCounts)
        bind(6, cellMass)
        build.int("uCount", activeBodies)
        build.int("uMeshWidth", gridWidth)
        build.int("uPhysicalWidth", physicalGridWidth)
        build.int("uPhysicalHeight", physicalGridHeight)
        build.int("uMassScale", massScale)
        dispatchBodies(activeBodies)
        barrier()

        fftInit.use()
        bind(0, density)
        bind(1, fft)
        fftInit.int("uGridWidth", gridWidth)
        fftInit.int("uGridHeight", gridHeight)
        fftInit.int("uBits", logGridWidth)
        fftInit.float("uInverseMassScale", 1f / massScale)
        dispatch(cells)
        barrier()
        var current = fftStages(fft, scratch, axis = 0, inverse = false)

        current = permute(current, other(current, fft, scratch), axis = 1)
        current = fftStages(current, other(current, fft, scratch), axis = 1, inverse = false)
        if (current != fft) copy(current, fft, complexBytes)

        convolve.use()
        bind(0, fft)
        bind(1, forceX)
        bind(2, forceY)
        bind(3, kernelX)
        bind(4, kernelY)
        convolve.int("uGridWidth", gridWidth)
        convolve.int("uGridHeight", gridHeight)
        dispatch(cells)
        barrier()

        inverse(forceX)
        inverse(forceY)
    }

    private fun applyKick(activeBodies: Int, initial: Boolean) {
        val totalMass = massPrefix[activeBodies].toFloat()
        kick.use()
        bind(0, bodies)
        bind(1, acceleration)
        bind(2, forceX)
        bind(3, forceY)
        bind(4, cellHeads)
        bind(5, nextBody)
        bind(6, masses)
        bind(7, cellCounts)
        bind(8, cellMass)
        kick.int("uCount", activeBodies)
        kick.float("uInitialTotalMass", normalizationMass)
        kick.float("uInverseMassScale", 1f / massScaleFor(totalMass))
        kick.int("uMeshWidth", gridWidth)
        kick.int("uPhysicalWidth", physicalGridWidth)
        kick.int("uPhysicalHeight", physicalGridHeight)
        kick.int("uInitial", if (initial) 1 else 0)
        kick.int(
            "uNeighborRadiusX",
            ceil(3f * config.softeningCells * physicalGridWidth /
                (physicalGridHeight * domainAspect))
                .toInt().coerceIn(1, 6),
        )
        kick.int(
            "uNeighborRadiusY",
            ceil(config.softeningCells * 3f).toInt().coerceIn(1, 6),
        )
        kick.float("uDt", config.timeStep)
        kick.float("uGravity", config.gravitationalConstant)
        kick.float("uSigmaCells", config.softeningCells)
        kick.float("uDomainAspect", domainAspect)
        dispatchBodies(activeBodies)
        barrier()
    }

    private fun inverse(base: Int) {
        var current = permute(base, scratch, axis = 0)
        current = fftStages(current, other(current, base, scratch), axis = 0, inverse = true)
        current = permute(current, other(current, base, scratch), axis = 1)
        current = fftStages(current, other(current, base, scratch), axis = 1, inverse = true)
        if (current != base) copy(current, base, complexBytes)
    }

    private fun forward(base: Int) {
        var current = permute(base, scratch, axis = 0)
        current = fftStages(current, other(current, base, scratch), axis = 0, inverse = false)
        current = permute(current, other(current, base, scratch), axis = 1)
        current = fftStages(current, other(current, base, scratch), axis = 1, inverse = false)
        if (current != base) copy(current, base, complexBytes)
    }

    private fun initializeKernel() {
        kernelInit.use()
        bind(0, kernelX)
        bind(1, kernelY)
        kernelInit.int("uGridWidth", gridWidth)
        kernelInit.int("uGridHeight", gridHeight)
        kernelInit.int("uPhysicalWidth", physicalGridWidth)
        kernelInit.int("uPhysicalHeight", physicalGridHeight)
        kernelInit.float("uDomainAspect", domainAspect)
        kernelInit.float("uGravity", config.gravitationalConstant)
        kernelInit.float("uInitialTotalMass", normalizationMass)
        kernelInit.float("uSigmaCells", config.softeningCells)
        dispatch(cells)
        barrier()
        forward(kernelX)
        forward(kernelY)
    }

    private fun fftStages(input: Int, output: Int, axis: Int, inverse: Boolean): Int {
        var source = input
        var destination = output
        val stages = if (axis == 0) logGridWidth else logGridHeight
        repeat(stages) { stage ->
            fftStage.use()
            bind(0, source)
            bind(1, destination)
            fftStage.int("uGridWidth", gridWidth)
            fftStage.int("uGridHeight", gridHeight)
            fftStage.int("uStage", stage + 1)
            fftStage.int("uAxis", axis)
            fftStage.int("uInverse", if (inverse) 1 else 0)
            dispatch(cells)
            barrier()
            val swap = source
            source = destination
            destination = swap
        }
        return source
    }

    private fun permute(input: Int, output: Int, axis: Int): Int {
        permute.use()
        bind(0, input)
        bind(1, output)
        permute.int("uGridWidth", gridWidth)
        permute.int("uGridHeight", gridHeight)
        permute.int("uBits", if (axis == 0) logGridWidth else logGridHeight)
        permute.int("uAxis", axis)
        dispatch(cells)
        barrier()
        return output
    }

    private fun seedBodies() {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, bodies)
        val mappedBodies = glMapBuffer(GL_SHADER_STORAGE_BUFFER, GL_WRITE_ONLY, bodyBytes, null)
            ?.asFloatBuffer() ?: error("Unable to map particle buffer")
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, masses)
        val mappedMasses = glMapBuffer(
            GL_SHADER_STORAGE_BUFFER,
            GL_WRITE_ONLY,
            config.capacity.toLong() * Float.SIZE_BYTES,
            null,
        )?.asFloatBuffer() ?: error("Unable to map mass buffer")
        val initialState = initialSeed?.let { seed ->
            UniformParticleInitializer.fill(
                bodies = mappedBodies,
                masses = mappedMasses,
                count = config.capacity,
                seed = seed,
            )
        } ?: UniformParticleInitializer.fill(
            bodies = mappedBodies,
            masses = mappedMasses,
            count = config.capacity,
        )
        if (initialSeed == null) initialSeed = initialState.seed
        massPrefix = initialState.massPrefix
        normalizationMass = initialState.normalizationMass
        System.err.println(
            "Particle seed: ${java.lang.Long.toUnsignedString(initialState.seed)}"
        )

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, bodies)
        check(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) { "Particle buffer became corrupt while seeding" }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, masses)
        check(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) { "Mass buffer became corrupt while seeding" }
    }

    private fun clearFloatBuffer(buffer: Int) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        glClearBufferData(
            GL_SHADER_STORAGE_BUFFER,
            GL_R32F,
            org.lwjgl.opengl.GL11.GL_RED,
            GL_FLOAT,
            null as ByteBuffer?,
        )
    }

    private fun buffer(bytes: Long): Int = glGenBuffers().also {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
        glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW)
    }

    private fun bind(binding: Int, buffer: Int) =
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, buffer)

    private fun dispatch(items: Int) = glDispatchCompute((items + 255) / 256, 1, 1)
    private fun dispatchBodies(items: Int) = dispatch(items)
    private fun barrier() = glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

    private fun massScaleFor(totalMass: Float): Int =
        (4_000_000_000.0 / totalMass).toLong().coerceIn(1L, 65_536L).toInt()

    private fun other(current: Int, first: Int, second: Int): Int =
        if (current == first) second else first

    private fun copy(source: Int, destination: Int, bytes: Long) {
        glBindBuffer(GL_COPY_READ_BUFFER, source)
        glBindBuffer(GL_COPY_WRITE_BUFFER, destination)
        glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, bytes)
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
    }

    override fun close() {
        listOf(
            clear,
            build,
            fftInit,
            permute,
            fftStage,
            kernelInit,
            convolve,
            drift,
            kick,
            collisionClear,
            collisionBuild,
            collisionCount,
            collisionResolve,
            collisionApply,
        )
            .forEach(GlProgram::close)
        intArrayOf(
            bodies,
            masses,
            acceleration,
            density,
            cellHeads,
            cellCounts,
            cellMass,
            nextBody,
            collisionHeads,
            collisionDeltas,
            collisionContacts,
            fft,
            scratch,
            forceX,
            forceY,
            kernelX,
            kernelY,
        )
            .forEach(::glDeleteBuffers)
    }
}
