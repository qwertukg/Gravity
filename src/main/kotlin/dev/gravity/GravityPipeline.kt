package dev.gravity

import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.GL_READ_ONLY
import org.lwjgl.opengl.GL15.GL_WRITE_ONLY
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glBufferSubData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL15.glMapBuffer
import org.lwjgl.opengl.GL15.glUnmapBuffer
import org.lwjgl.opengl.GL30.glBindBufferBase
import org.lwjgl.opengl.GL30.GL_R32F
import org.lwjgl.opengl.GL30.GL_R32UI
import org.lwjgl.opengl.GL30.GL_RED_INTEGER
import org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER
import org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER
import org.lwjgl.opengl.GL31.glCopyBufferSubData
import org.lwjgl.opengl.GL32.glGetInteger64
import org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT
import org.lwjgl.opengl.GL42.GL_COMMAND_BARRIER_BIT
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_DISPATCH_INDIRECT_BUFFER
import org.lwjgl.opengl.GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glClearBufferData
import org.lwjgl.opengl.GL43.glDispatchCompute
import org.lwjgl.opengl.GL43.glDispatchComputeIndirect
import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import org.lwjgl.system.MemoryUtil.memAllocFloat
import org.lwjgl.system.MemoryUtil.memFree
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

data class ClusterStatistics(
    val centerX: Double,
    val centerY: Double,
    val covarianceAreaPixels: Double,
    val equivalentDiameterPixels: Double,
    val core90AreaPixels: Double,
    val core99AreaPixels: Double,
    val maxSpeedPixelsPerSecond: Double,
    val averageSpeedPixelsPerSecond: Double,
    val nonFiniteBodies: Int,
)

data class FftValidationResult(
    val maximumAbsoluteError: Double,
    val maximumRelativeError: Double,
    val rootMeanSquareError: Double,
)

data class AdaptiveCollisionStatistics(
    val maximumSpeedPixelsPerSecond: Float,
    val maximumPenetrationPixels: Float,
    val substeps: Int,
    val maximumContactCount: Int,
    val denseContactBodies: Int,
)

class GravityPipeline(
    private val config: GravityConfig,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val profiler: GpuProfiler? = null,
    /** Normalised (x, y, vx, vy) of the first bodies, e.g. from a [Scenario]. */
    private val scenarioState: FloatArray? = null,
    bounded: Boolean = false,
) : AutoCloseable {
    private val gridWidth = config.gravity.gridWidth
    private val gridHeight = config.gravity.gridHeight
    private val physicalGridWidth = gridWidth
    private val physicalGridHeight = gridHeight
    private val cells = gridWidth * gridHeight

    /** Walls need a doubled FFT axis, which must fit one shared-memory line. */
    val boundedSpaceSupported =
        2 * maxOf(gridWidth, gridHeight) <= MAXIMUM_FFT_AXIS

    /**
     * Screen edges are walls instead of the periodic torus. A cyclic FFT is
     * the natural convolution for the torus, with mesh == physical grid. With
     * walls the mesh doubles on both axes and the density occupies one
     * quadrant (Hockney zero padding), so gravity does not act across edges.
     */
    var bounded = bounded
        set(value) {
            if (field == value) return
            require(!value || boundedSpaceSupported) {
                "Walls need an FFT grid of at most ${MAXIMUM_FFT_AXIS / 2} cells per axis"
            }
            field = value
            initializeKernel()
        }

    /** Uniform field in px/s² added to every body's acceleration (+y is up). */
    var fieldAccelerationX = 0f
    var fieldAccelerationY = 0f

    /**
     * Gravity between bodies. When off, the grid, FFT and near-field passes
     * are skipped and only the uniform field accelerates bodies.
     */
    var mutualGravity = true

    private val meshWidth get() = if (bounded) 2 * gridWidth else gridWidth
    private val meshHeight get() = if (bounded) 2 * gridHeight else gridHeight
    private val meshCells get() = meshWidth * meshHeight
    private val maximumMeshCells = if (boundedSpaceSupported) 4 * cells else cells
    private val physicalCells = physicalGridWidth * physicalGridHeight
    private val collisionGridWidth =
        ToroidalSpace.collisionGridCells(
            viewportWidth,
            config.collisions.contactDiameterPixels,
        )
    private val collisionGridHeight =
        ToroidalSpace.collisionGridCells(
            viewportHeight,
            config.collisions.contactDiameterPixels,
        )
    private val collisionCells = collisionGridWidth * collisionGridHeight
    private val domainAspect = viewportWidth.toFloat() / viewportHeight
    private val viewportWidthFloat = viewportWidth.toFloat()
    private val viewportHeightFloat = viewportHeight.toFloat()
    private val collisionSubstep =
        config.collisions.timeStep / config.collisions.maximumSubsteps
    private val neighborRadiusX =
        ceil(
            config.gravity.localSearchSigmaMultiplier *
                config.gravity.softeningCells * physicalGridWidth /
                (physicalGridHeight * domainAspect),
        ).toInt().coerceIn(
            config.gravity.minimumNeighborRadiusCells,
            config.gravity.maximumNeighborRadiusCells,
        )
    private val neighborRadiusY =
        ceil(
            config.gravity.softeningCells *
                config.gravity.localSearchSigmaMultiplier,
        ).toInt().coerceIn(
            config.gravity.minimumNeighborRadiusCells,
            config.gravity.maximumNeighborRadiusCells,
        )
    private val bodyBytes =
        config.particles.capacity.toLong() * 4 * Float.SIZE_BYTES
    private val accelerationBytes =
        config.particles.capacity.toLong() * 2 * Float.SIZE_BYTES
    private val complexBytes = maximumMeshCells.toLong() * 2 * Float.SIZE_BYTES
    private val projectileOffsets = HashMap<Float, List<PixelOffset>>()
    private val boundStorageBuffers = IntArray(8) { -1 }

    fun projectileBodyCount(diameterPixels: Float): Int =
        projectileOffsets(diameterPixels).size

    private var bodies = buffer(bodyBytes)
    private var collisionBodies = buffer(bodyBytes)
    private var acceleration = buffer(accelerationBytes)
    // Spatial reordering writes into the free ping-pong body buffer and this
    // spare, then swaps them in.
    private var spareAcceleration = buffer(accelerationBytes)
    private val sortWidthBits = sortAxisBits(viewportWidth)
    private val sortHeightBits = sortAxisBits(viewportHeight)
    private val sortTiles = 1 shl (sortWidthBits + sortHeightBits)
    private val sortBlocks = (sortTiles + SORT_SCAN_BLOCK - 1) / SORT_SCAN_BLOCK
    private val sortCounts = buffer(sortTiles.toLong() * Int.SIZE_BYTES)
    private val sortOffsets = buffer(sortTiles.toLong() * Int.SIZE_BYTES)
    private val sortBlockTotals = buffer(sortBlocks.toLong() * Int.SIZE_BYTES)
    private val sortBlockOffsets = buffer(sortBlocks.toLong() * Int.SIZE_BYTES)
    private val sortTotal = buffer(Int.SIZE_BYTES.toLong())
    private var physicsSteps = 0L
    private val density = buffer(maximumMeshCells.toLong() * Int.SIZE_BYTES)
    private val cellHeads = buffer(physicalCells.toLong() * Int.SIZE_BYTES)
    private val cellCounts = buffer(physicalCells.toLong() * Int.SIZE_BYTES)
    private val nextBody =
        buffer(config.particles.capacity.toLong() * Int.SIZE_BYTES)
    private val collisionHeads = buffer(collisionCells.toLong() * Int.SIZE_BYTES)
    private val collisionNext =
        buffer(config.particles.capacity.toLong() * Int.SIZE_BYTES)
    private val collisionContactCounts =
        buffer(config.particles.capacity.toLong() * Int.SIZE_BYTES)
    private val collisionAdaptiveState = buffer(5L * Int.SIZE_BYTES)
    private val collisionDispatchCommands =
        buffer(
            config.collisions.maximumSubsteps.toLong() * 3 * Int.SIZE_BYTES,
        )
    private val pressures =
        buffer(config.particles.capacity.toLong() * Float.SIZE_BYTES)
    private val fft = buffer(complexBytes)
    private val forceX = buffer(complexBytes)
    private val forceY = buffer(complexBytes)
    private val kernelX = buffer(complexBytes)
    private val kernelY = buffer(complexBytes)
    private val maximumFftAxis =
        maxOf(gridWidth, gridHeight) * if (boundedSpaceSupported) 2 else 1
    private val twiddles = buffer(
        (maximumFftAxis - 1).toLong() * 4 * Float.SIZE_BYTES,
    )
    val trajectoryVertexCount =
        config.trajectory.steps * TRAJECTORY_VERTICES_PER_STEP
    private val trajectory = buffer(
        trajectoryVertexCount.toLong() * 4 * Float.SIZE_BYTES,
    )

    private val clear = GlProgram.compute("/shaders/clear_grid.comp")
    private val build = GlProgram.compute("/shaders/build_grid.comp")
    private val fftInit = GlProgram.compute("/shaders/fft_init.comp")
    private val sharedFft = GlProgram.compute("/shaders/fft_shared.comp")
    private val twiddleInit = GlProgram.compute("/shaders/twiddle_init.comp")
    private val kernelInit = GlProgram.compute("/shaders/kernel_init.comp")
    private val convolve = GlProgram.compute("/shaders/convolve.comp")
    private val kick = GlProgram.compute("/shaders/kick.comp")
    private val collisionClear = GlProgram.compute("/shaders/collision_clear.comp")
    private val collisionBuild = GlProgram.compute("/shaders/collision_build.comp")
    private val collisionCount = GlProgram.compute("/shaders/collision_count.comp")
    private val collisionSolve = GlProgram.compute("/shaders/collision_solve.comp")
    private val collisionAdaptiveReset =
        GlProgram.compute("/shaders/collision_adaptive_reset.comp")
    private val collisionAdaptiveReduce =
        GlProgram.compute("/shaders/collision_adaptive_reduce.comp")
    private val collisionAdaptiveFinalize =
        GlProgram.compute("/shaders/collision_adaptive_finalize.comp")
    private val stopBodiesProgram = GlProgram.compute("/shaders/stop_bodies.comp")
    private val trajectoryCompute = GlProgram.compute("/shaders/trajectory.comp")
    private val sortCount = GlProgram.compute("/shaders/spatial_sort_count.comp")
    private val sortScan = GlProgram.compute("/shaders/spatial_sort_scan.comp")
    private val sortScatter = GlProgram.compute("/shaders/spatial_sort_scatter.comp")
    /**
     * Gravity is normalised to the bodies simulated right now, not to the
     * initial count: fired projectiles share the same total mass instead of
     * adding to it, so N bodies always pull alike however they were created.
     */
    private var normalizationMass = config.particles.initialBodies.toFloat()
    private val neighborCache = if (
        config.collisions.neighborCache && CollisionNeighborCache.cacheBytes(config.particles.capacity) <=
        glGetInteger64(GL_MAX_SHADER_STORAGE_BLOCK_SIZE)
    ) {
        CollisionNeighborCache(
            config = config,
            width = viewportWidth,
            height = viewportHeight,
            gridWidth = collisionGridWidth,
            gridHeight = collisionGridHeight,
            heads = collisionHeads,
            contacts = collisionContactCounts,
            pressures = pressures,
            adaptive = collisionAdaptiveState,
            bind = ::bind,
            dispatch = ::dispatchCollisionBodies,
            profiler = profiler,
        )
    } else null

    init {
        if (config.collisions.neighborCache && neighborCache == null) {
            System.err.println("Neighbor cache exceeds GPU buffer limit; using complete linked-grid collisions")
        }
        seedBodies()
        clearFloatBuffer(acceleration)
        clearFloatBuffer(pressures)
        clearUnsignedIntBuffer(collisionHeads)
        clearUnsignedIntBuffer(collisionAdaptiveState)
        clearUnsignedIntBuffer(collisionDispatchCommands)
        // The scatter counts every tile back down to zero, so this is the
        // only clear the tile counts ever need.
        clearUnsignedIntBuffer(sortCounts)
        listOf(sortCount, sortScatter).forEach { program ->
            program.use()
            program.int("uSortWidthBits", sortWidthBits)
            program.int("uSortHeightBits", sortHeightBits)
        }
        initializeTwiddles()
        initializeKernel()
    }

    fun initializeAcceleration(activeBodies: Int) {
        normalizeTo(activeBodies)
        if (mutualGravity) buildForce(activeBodies)
        applyKick(activeBodies, initial = true)
    }

    /**
     * Blocking readback used only by the explicit --fft-validate diagnostic.
     * It compares the actual GPU density spectrum with a double-precision
     * radix-2 reference. The reference layout is independently checked against
     * JTransforms by Radix2FftLayoutTest.
     */
    fun validateFft(activeBodies: Int): FftValidationResult {
        check(!bounded) { "FFT validation covers the periodic mesh only" }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, density)
        val densityMap = glMapBuffer(
            GL_SHADER_STORAGE_BUFFER,
            GL_READ_ONLY,
            cells.toLong() * Int.SIZE_BYTES,
            null,
        )?.asIntBuffer() ?: error("Unable to map density for FFT validation")
        val expected = DoubleArray(cells * 2)
        val inverseMassScale = 1.0 / massScaleFor(activeBodies.toFloat())
        repeat(cells) { index ->
            expected[index * 2] =
                (densityMap[index].toLong() and 0xffff_ffffL) * inverseMassScale
        }
        check(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))
        cpuFft2d(expected, inverse = false)

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, fft)
        val spectrumMap = glMapBuffer(
            GL_SHADER_STORAGE_BUFFER,
            GL_READ_ONLY,
            complexBytes,
            null,
        )?.asFloatBuffer() ?: error("Unable to map spectrum for FFT validation")
        var maximumAbsolute = 0.0
        var maximumReference = 0.0
        var squaredError = 0.0
        repeat(expected.size) { index ->
            val difference = kotlin.math.abs(expected[index] - spectrumMap[index])
            maximumAbsolute = max(maximumAbsolute, difference)
            maximumReference = max(maximumReference, kotlin.math.abs(expected[index]))
            squaredError += difference * difference
        }
        check(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))
        return FftValidationResult(
            maximumAbsoluteError = maximumAbsolute,
            maximumRelativeError = maximumAbsolute / maximumReference.coerceAtLeast(1e-30),
            rootMeanSquareError = sqrt(squaredError / expected.size),
        )
    }

    /**
     * Blocking end-of-run diagnostic. Normal simulation and profiling never
     * read adaptive state back to the CPU.
     */
    fun measureAdaptiveCollisions(): AdaptiveCollisionStatistics {
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, collisionAdaptiveState)
        val state = glMapBuffer(
            GL_SHADER_STORAGE_BUFFER,
            GL_READ_ONLY,
            5L * Int.SIZE_BYTES,
            null,
        )?.asIntBuffer() ?: error("Unable to map adaptive collision state")
        val result = AdaptiveCollisionStatistics(
            maximumSpeedPixelsPerSecond = Float.fromBits(state[0]),
            maximumPenetrationPixels = Float.fromBits(state[1]),
            substeps = state[2],
            maximumContactCount = state[3],
            denseContactBodies = state[4],
        )
        check(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))
        return result
    }

    fun step(activeBodies: Int) {
        normalizeTo(activeBodies)
        profiler?.begin(GpuStage.PHYSICS_STEP)
        val sortInterval = config.particles.spatialSortInterval
        if (sortInterval > 0 && physicsSteps++ % sortInterval == 0L) sortBodies(activeBodies)
        if (config.collisions.adaptive) {
            profiler?.begin(GpuStage.COLLISION_ADAPTIVE)
            prepareAdaptiveCollisions(activeBodies)
            profiler?.end(GpuStage.COLLISION_ADAPTIVE)
        }
        profiler?.begin(GpuStage.COLLISION_SUBSTEPS)
        repeat(config.collisions.maximumSubsteps) { substep ->
            resolveCollisions(
                activeBodies = activeBodies,
                kickDt = if (substep == 0) config.collisions.timeStep else 0f,
                driftDt = collisionSubstep,
                writePressure = false,
                substepIndex = substep,
            )
        }
        profiler?.end(GpuStage.COLLISION_SUBSTEPS)
        if (mutualGravity) buildForce(activeBodies)
        applyKick(activeBodies, initial = false)
        // The final gravity kick can create new inward contact velocity in the
        // dense core. A zero-drift pass removes it before rendering/next step.
        resolveCollisions(
            activeBodies = activeBodies,
            kickDt = 0f,
            driftDt = 0f,
            writePressure = true,
            substepIndex = null,
        )
        profiler?.end(GpuStage.PHYSICS_STEP)
    }

    fun bindBodiesForRender() {
        bind(0, bodies)
        bind(1, pressures)
    }

    fun updateTrajectory(
        activeBodies: Int,
        originX: Float,
        originY: Float,
        velocityX: Float,
        velocityY: Float,
    ) {
        normalizeTo(activeBodies)
        profiler?.begin(GpuStage.TRAJECTORY)
        val wrappedOriginX = placeX(originX)
        val wrappedOriginY = placeY(originY)

        trajectoryCompute.use()
        bind(0, trajectory)
        bind(1, forceX)
        bind(2, forceY)
        bind(3, cellHeads)
        bind(4, nextBody)
        bind(5, cellCounts)
        bind(7, bodies)
        trajectoryCompute.int("uStepCount", config.trajectory.steps)
        trajectoryCompute.int("uMeshWidth", meshWidth)
        trajectoryCompute.int("uMeshHeight", meshHeight)
        trajectoryCompute.int("uBounded", if (bounded) 1 else 0)
        trajectoryCompute.int("uMutualGravity", if (mutualGravity) 1 else 0)
        trajectoryCompute.vec2(
            "uFieldAcceleration",
            fieldAccelerationX / viewportWidthFloat,
            fieldAccelerationY / viewportHeightFloat,
        )
        trajectoryCompute.float(
            "uRestitutionCoefficient",
            config.collisions.restitutionCoefficient,
        )
        trajectoryCompute.int("uPhysicalWidth", physicalGridWidth)
        trajectoryCompute.int("uPhysicalHeight", physicalGridHeight)
        trajectoryCompute.int("uNeighborRadiusX", neighborRadiusX)
        trajectoryCompute.int("uNeighborRadiusY", neighborRadiusY)
        trajectoryCompute.vec2("uViewport", viewportWidthFloat, viewportHeightFloat)
        trajectoryCompute.vec2("uOriginPixels", wrappedOriginX, wrappedOriginY)
        trajectoryCompute.vec2("uVelocityPixels", velocityX, velocityY)
        trajectoryCompute.float("uMinimumDt", config.collisions.timeStep)
        trajectoryCompute.float(
            "uMaximumDt",
            config.trajectory.maximumTimeStep,
        )
        trajectoryCompute.float(
            "uTargetSegmentPixels",
            config.trajectory.targetSegmentPixels,
        )
        trajectoryCompute.float(
            "uMaximumPathPixels",
            config.trajectory.maximumPathViewportDiagonals *
                sqrt(
                    viewportWidthFloat * viewportWidthFloat +
                        viewportHeightFloat * viewportHeightFloat,
                ),
        )
        trajectoryCompute.float(
            "uGravity",
            config.gravity.gravitationalConstant,
        )
        trajectoryCompute.float("uSigmaCells", config.gravity.softeningCells)
        trajectoryCompute.int(
            "uExactCellThreshold",
            config.gravity.exactNeighborBodyLimit,
        )
        trajectoryCompute.int(
            "uMaximumHomeInteractions",
            config.gravity.maximumHomeBodyInteractions,
        )
        trajectoryCompute.float(
            "uMinimumDashPixels",
            config.trajectory.minimumDashPixels,
        )
        trajectoryCompute.float(
            "uMinimumDistanceCellFraction",
            config.gravity.minimumDistanceCellFraction,
        )
        trajectoryCompute.float("uDomainAspect", domainAspect)
        trajectoryCompute.float("uTotalMass", normalizationMass)
        dispatch(1)
        barrier()
        profiler?.end(GpuStage.TRAJECTORY)
    }

    fun bindTrajectoryForRender() {
        bind(0, trajectory)
    }

    fun stopBodies(activeBodies: Int) {
        stopBodiesProgram.use()
        bind(0, bodies)
        bind(1, collisionBodies)
        stopBodiesProgram.int("uCount", activeBodies)
        dispatchBodies(activeBodies)
        barrier()
    }

    fun spawnProjectile(
        firstBody: Int,
        centerX: Float,
        centerY: Float,
        velocityX: Float,
        velocityY: Float,
        diameterPixels: Float,
    ): Int {
        val offsets = projectileOffsets(diameterPixels)
        if (firstBody !in 0..config.particles.capacity ||
            offsets.size > config.particles.capacity - firstBody) {
            return 0
        }

        val radius = offsets.maxOf { hypot(it.x, it.y) } +
            0.5f * config.collisions.contactDiameterPixels
        val insideX = if (bounded) centerX.coerceIn(radius, viewportWidthFloat - radius) else centerX
        val insideY = if (bounded) centerY.coerceIn(radius, viewportHeightFloat - radius) else centerY
        val upload = memAllocFloat(offsets.size * 4)
        try {
            offsets.forEach { offset ->
                upload.put(placeX(insideX + offset.x) / viewportWidth)
                upload.put(placeY(insideY + offset.y) / viewportHeight)
                upload.put(velocityX / viewportWidth)
                upload.put(velocityY / viewportHeight)
            }
            upload.flip()
            val byteOffset = firstBody.toLong() * 4 * Float.SIZE_BYTES
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, bodies)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, byteOffset, upload)
            upload.rewind()
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, collisionBodies)
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, byteOffset, upload)
        } finally {
            memFree(upload)
        }
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
        return offsets.size
    }

    /** A screen coordinate wrapped onto the torus, or clamped inside the walls. */
    private fun placeX(pixels: Float) =
        if (bounded) pixels.coerceIn(0f, viewportWidthFloat * MAXIMUM_UNIT)
        else ToroidalSpace.wrapPixels(pixels, viewportWidth)

    private fun placeY(pixels: Float) =
        if (bounded) pixels.coerceIn(0f, viewportHeightFloat * MAXIMUM_UNIT)
        else ToroidalSpace.wrapPixels(pixels, viewportHeight)

    private fun projectileOffsets(diameterPixels: Float): List<PixelOffset> =
        projectileOffsets.getOrPut(diameterPixels) {
            CircularProjectile.offsets(
                config.collisions.contactDiameterPixels,
                diameterPixels,
            )
        }

    /**
     * Slow, blocking GPU readback used only by finite diagnostic runs.
     * For a uniform circle, 4π√det(covariance) is exactly its area.
     */
    fun measureCluster(activeBodies: Int): ClusterStatistics {
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, bodies)
        val mapped = glMapBuffer(
            GL_SHADER_STORAGE_BUFFER,
            GL_READ_ONLY,
            activeBodies.toLong() * 4 * Float.SIZE_BYTES,
            null,
        )?.asFloatBuffer() ?: error("Unable to map particle buffer for diagnostics")

        var circleXCos = 0.0
        var circleXSin = 0.0
        var circleYCos = 0.0
        var circleYSin = 0.0
        var maxSpeed = 0.0
        var totalSpeed = 0.0
        var nonFiniteBodies = 0
        val xCoordinates = DoubleArray(activeBodies)
        val yCoordinates = DoubleArray(activeBodies)
        for (index in 0 until activeBodies) {
            val rawX = mapped[index * 4]
            val rawY = mapped[index * 4 + 1]
            val rawVx = mapped[index * 4 + 2]
            val rawVy = mapped[index * 4 + 3]
            val finite = rawX.isFinite() && rawY.isFinite() &&
                rawVx.isFinite() && rawVy.isFinite()
            if (!finite) nonFiniteBodies++
            val x = if (finite) rawX.toDouble() else 0.0
            val y = if (finite) rawY.toDouble() else 0.0
            xCoordinates[index] = x
            yCoordinates[index] = y
            val xAngle = 2.0 * Math.PI * x
            val yAngle = 2.0 * Math.PI * y
            circleXCos += kotlin.math.cos(xAngle)
            circleXSin += kotlin.math.sin(xAngle)
            circleYCos += kotlin.math.cos(yAngle)
            circleYSin += kotlin.math.sin(yAngle)
            val speed = if (finite) kotlin.math.hypot(
                rawVx.toDouble() * viewportWidth,
                rawVy.toDouble() * viewportHeight,
            ) else 0.0
            maxSpeed = max(maxSpeed, speed)
            totalSpeed += speed
        }

        val circularCenterX = ToroidalSpace.wrapUnit(
            kotlin.math.atan2(circleXSin, circleXCos) / (2.0 * Math.PI),
        )
        val circularCenterY = ToroidalSpace.wrapUnit(
            kotlin.math.atan2(circleYSin, circleYCos) / (2.0 * Math.PI),
        )
        var meanX = 0.0
        var meanY = 0.0
        for (index in 0 until activeBodies) {
            val unwrappedX = circularCenterX +
                ToroidalSpace.minimumImage(xCoordinates[index] - circularCenterX)
            val unwrappedY = circularCenterY +
                ToroidalSpace.minimumImage(yCoordinates[index] - circularCenterY)
            xCoordinates[index] = unwrappedX * viewportWidth
            yCoordinates[index] = unwrappedY * viewportHeight
            meanX += xCoordinates[index]
            meanY += yCoordinates[index]
        }
        meanX /= activeBodies
        meanY /= activeBodies

        var xx = 0.0
        var xy = 0.0
        var yy = 0.0
        for (index in 0 until activeBodies) {
            val x = xCoordinates[index] - meanX
            val y = yCoordinates[index] - meanY
            xx += x * x
            xy += x * y
            yy += y * y
        }
        xx /= activeBodies
        xy /= activeBodies
        yy /= activeBodies
        check(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) {
            "Particle buffer became corrupt during diagnostics"
        }

        val area = 4.0 * Math.PI * sqrt(max(xx * yy - xy * xy, 0.0))
        val sortedX = xCoordinates.copyOf()
        val sortedY = yCoordinates.copyOf()
        Arrays.sort(sortedX)
        Arrays.sort(sortedY)
        val medianX = sortedX[activeBodies / 2]
        val medianY = sortedY[activeBodies / 2]
        val radiusSquared = DoubleArray(activeBodies)
        for (index in 0 until activeBodies) {
            val x = xCoordinates[index] - medianX
            val y = yCoordinates[index] - medianY
            radiusSquared[index] = x * x + y * y
        }
        Arrays.sort(radiusSquared)
        fun coreArea(fraction: Double): Double {
            val index = (fraction * activeBodies).toInt().coerceIn(1, activeBodies) - 1
            return Math.PI * radiusSquared[index] / fraction
        }
        return ClusterStatistics(
            centerX = ToroidalSpace.wrapPixels(medianX.toFloat(), viewportWidth).toDouble(),
            centerY = ToroidalSpace.wrapPixels(medianY.toFloat(), viewportHeight).toDouble(),
            covarianceAreaPixels = area,
            equivalentDiameterPixels = 2.0 * sqrt(area / Math.PI),
            core90AreaPixels = coreArea(0.90),
            core99AreaPixels = coreArea(0.99),
            maxSpeedPixelsPerSecond = maxSpeed,
            averageSpeedPixelsPerSecond = totalSpeed / activeBodies,
            nonFiniteBodies = nonFiniteBodies,
        )
    }

    private fun buildForce(activeBodies: Int) {
        val totalMass = activeBodies.toFloat()
        val massScale = massScaleFor(totalMass)
        profiler?.begin(GpuStage.GRAVITY_GRID)
        clear.use()
        bind(0, density)
        bind(1, cellHeads)
        bind(2, cellCounts)
        clear.int("uMeshCells", meshCells)
        clear.int("uParticleCells", physicalCells)
        dispatch(maxOf(meshCells, physicalCells))
        barrier()

        build.use()
        bind(0, bodies)
        bind(1, density)
        bind(2, cellHeads)
        bind(3, nextBody)
        bind(4, cellCounts)
        build.int("uCount", activeBodies)
        build.int("uMeshWidth", meshWidth)
        build.int("uMeshHeight", meshHeight)
        build.int("uPhysicalWidth", physicalGridWidth)
        build.int("uPhysicalHeight", physicalGridHeight)
        build.int("uMassScale", massScale)
        dispatchBodies(activeBodies)
        barrier()

        fftInit.use()
        bind(0, density)
        bind(1, fft)
        fftInit.int("uGridWidth", meshWidth)
        fftInit.int("uGridHeight", meshHeight)
        fftInit.float("uInverseMassScale", 1f / massScale)
        dispatch(meshCells)
        barrier()
        profiler?.end(GpuStage.GRAVITY_GRID)
        profiler?.begin(GpuStage.FORWARD_FFT)
        transform(fft, inverse = false)
        profiler?.end(GpuStage.FORWARD_FFT)

        profiler?.begin(GpuStage.CONVOLUTION)
        convolve.use()
        bind(0, fft)
        bind(1, forceX)
        bind(2, forceY)
        bind(3, kernelX)
        bind(4, kernelY)
        convolve.int("uGridWidth", meshWidth)
        convolve.int("uGridHeight", meshHeight)
        convolve.float(
            "uMinimumAssignmentWindowPower",
            config.gravity.minimumAssignmentWindowPower,
        )
        dispatch(meshCells)
        barrier()
        profiler?.end(GpuStage.CONVOLUTION)

        profiler?.begin(GpuStage.INVERSE_FFT_X)
        inverse(forceX)
        profiler?.end(GpuStage.INVERSE_FFT_X)
        profiler?.begin(GpuStage.INVERSE_FFT_Y)
        inverse(forceY)
        profiler?.end(GpuStage.INVERSE_FFT_Y)
    }

    private fun resolveCollisions(
        activeBodies: Int,
        kickDt: Float,
        driftDt: Float,
        writePressure: Boolean,
        substepIndex: Int?,
    ) {
        neighborCache?.let {
            it.resolve(bodies, collisionBodies, acceleration, activeBodies, kickDt, driftDt, writePressure, substepIndex, bounded)
            val swap = bodies
            bodies = collisionBodies
            collisionBodies = swap
            return
        }
        // collisionBodies is the input buffer used to build the current heads
        // list. Clearing only its occupied cells is exact and avoids scanning
        // millions of empty screen-sized cells on every substep.
        profiler?.begin(GpuStage.COLLISION_CLEAR)
        collisionClear.use()
        bind(0, collisionBodies)
        bind(1, collisionHeads)
        bind(2, collisionContactCounts)
        bind(3, collisionAdaptiveState)
        collisionClear.int("uCount", activeBodies)
        collisionClear.int("uGridWidth", collisionGridWidth)
        collisionClear.int("uGridHeight", collisionGridHeight)
        collisionClear.int(
            "uAdaptive",
            if (config.collisions.adaptive) 1 else 0,
        )
        collisionClear.int("uSubstepIndex", substepIndex ?: -1)
        dispatchCollisionBodies(activeBodies, substepIndex)
        barrier()
        profiler?.end(GpuStage.COLLISION_CLEAR)

        profiler?.begin(GpuStage.COLLISION_BUILD)
        collisionBuild.use()
        bind(0, bodies)
        bind(1, collisionHeads)
        bind(2, collisionNext)
        bind(3, acceleration)
        bind(4, collisionAdaptiveState)
        collisionBuild.int("uCount", activeBodies)
        collisionBuild.int("uGridWidth", collisionGridWidth)
        collisionBuild.int("uGridHeight", collisionGridHeight)
        collisionBuild.float("uKickDt", kickDt)
        collisionBuild.float("uDriftDt", driftDt)
        collisionBuild.float("uTimeStep", config.collisions.timeStep)
        collisionBuild.int(
            "uAdaptive",
            if (config.collisions.adaptive) 1 else 0,
        )
        collisionBuild.int("uSubstepIndex", substepIndex ?: -1)
        collisionBuild.int("uBounded", if (bounded) 1 else 0)
        dispatchCollisionBodies(activeBodies, substepIndex)
        barrier()
        profiler?.end(GpuStage.COLLISION_BUILD)

        profiler?.begin(GpuStage.COLLISION_COUNT)
        collisionCount.use()
        bind(0, bodies)
        bind(1, collisionHeads)
        bind(2, collisionNext)
        bind(3, collisionContactCounts)
        bind(4, collisionAdaptiveState)
        collisionCount.int("uCount", activeBodies)
        collisionCount.int("uGridWidth", collisionGridWidth)
        collisionCount.int("uGridHeight", collisionGridHeight)
        collisionCount.vec2("uViewport", viewportWidthFloat, viewportHeightFloat)
        collisionCount.float(
            "uDiameterPixels",
            config.collisions.contactDiameterPixels,
        )
        collisionCount.int(
            "uNeighborCellRadius",
            config.collisions.neighborCellRadius,
        )
        collisionCount.int(
            "uAdaptive",
            if (config.collisions.adaptive) 1 else 0,
        )
        collisionCount.int("uSubstepIndex", substepIndex ?: -1)
        collisionCount.int("uBounded", if (bounded) 1 else 0)
        dispatchCollisionBodies(activeBodies, substepIndex)
        barrier()
        profiler?.end(GpuStage.COLLISION_COUNT)

        profiler?.begin(GpuStage.COLLISION_SOLVE)
        collisionSolve.use()
        bind(0, bodies)
        bind(1, collisionBodies)
        bind(2, collisionHeads)
        bind(3, collisionNext)
        bind(4, collisionContactCounts)
        bind(5, pressures)
        bind(6, collisionAdaptiveState)
        collisionSolve.int("uCount", activeBodies)
        collisionSolve.int("uGridWidth", collisionGridWidth)
        collisionSolve.int("uGridHeight", collisionGridHeight)
        collisionSolve.vec2("uViewport", viewportWidthFloat, viewportHeightFloat)
        collisionSolve.float(
            "uDiameterPixels",
            config.collisions.contactDiameterPixels,
        )
        collisionSolve.int(
            "uNeighborCellRadius",
            config.collisions.neighborCellRadius,
        )
        collisionSolve.int(
            "uDenseContactThreshold",
            config.collisions.denseContactThreshold,
        )
        collisionSolve.float(
            "uRestitutionCoefficient",
            config.collisions.restitutionCoefficient,
        )
        collisionSolve.float(
            "uCoincidentDistanceSquaredEpsilon",
            config.collisions.coincidentDistanceSquaredEpsilon,
        )
        collisionSolve.float("uSubstepDt", collisionSubstep)
        collisionSolve.float("uTimeStep", config.collisions.timeStep)
        collisionSolve.int(
            "uAdaptive",
            if (config.collisions.adaptive) 1 else 0,
        )
        collisionSolve.int("uWritePressure", if (writePressure) 1 else 0)
        collisionSolve.int("uSubstepIndex", substepIndex ?: -1)
        collisionSolve.int("uBounded", if (bounded) 1 else 0)
        dispatchCollisionBodies(activeBodies, substepIndex)
        barrier()
        profiler?.end(GpuStage.COLLISION_SOLVE)

        val swap = bodies
        bodies = collisionBodies
        collisionBodies = swap
    }

    private fun prepareAdaptiveCollisions(activeBodies: Int) {
        collisionAdaptiveReset.use()
        bind(0, collisionAdaptiveState)
        glDispatchCompute(1, 1, 1)
        barrier()

        collisionAdaptiveReduce.use()
        bind(0, bodies)
        bind(1, collisionAdaptiveState)
        collisionAdaptiveReduce.int("uCount", activeBodies)
        collisionAdaptiveReduce.vec2(
            "uViewport",
            viewportWidthFloat,
            viewportHeightFloat,
        )
        dispatchBodies(activeBodies)
        barrier()

        collisionAdaptiveFinalize.use()
        bind(0, collisionAdaptiveState)
        bind(1, collisionDispatchCommands)
        collisionAdaptiveFinalize.int(
            "uMaximumSubsteps",
            config.collisions.maximumSubsteps,
        )
        collisionAdaptiveFinalize.int(
            "uBodyWorkgroups",
            (activeBodies + 255) / 256,
        )
        collisionAdaptiveFinalize.int(
            "uDenseContactBodyThreshold",
            max(
                config.collisions.minimumDenseBodies,
                ceil(
                    activeBodies * config.collisions.denseBodyFraction,
                ).toInt(),
            ),
        )
        collisionAdaptiveFinalize.float(
            "uTimeStep",
            config.collisions.timeStep,
        )
        collisionAdaptiveFinalize.float(
            "uDiameterPixels",
            config.collisions.contactDiameterPixels,
        )
        collisionAdaptiveFinalize.float(
            "uAllowedDisplacementFraction",
            config.collisions.allowedDisplacementFraction,
        )
        collisionAdaptiveFinalize.float(
            "uAllowedPenetrationFraction",
            config.collisions.allowedPenetrationFraction,
        )
        glDispatchCompute(1, 1, 1)
        barrier()
    }

    private fun applyKick(activeBodies: Int, initial: Boolean) {
        profiler?.begin(GpuStage.GRAVITY_KICK)
        kick.use()
        bind(0, bodies)
        bind(1, acceleration)
        bind(2, forceX)
        bind(3, forceY)
        bind(4, cellHeads)
        bind(5, nextBody)
        bind(6, cellCounts)
        kick.int("uCount", activeBodies)
        kick.float("uTotalMass", normalizationMass)
        kick.int("uMeshWidth", meshWidth)
        kick.int("uMeshHeight", meshHeight)
        kick.int("uBounded", if (bounded) 1 else 0)
        kick.int("uMutualGravity", if (mutualGravity) 1 else 0)
        kick.vec2(
            "uFieldAcceleration",
            fieldAccelerationX / viewportWidthFloat,
            fieldAccelerationY / viewportHeightFloat,
        )
        kick.int("uPhysicalWidth", physicalGridWidth)
        kick.int("uPhysicalHeight", physicalGridHeight)
        kick.int("uInitial", if (initial) 1 else 0)
        kick.int("uNeighborRadiusX", neighborRadiusX)
        kick.int("uNeighborRadiusY", neighborRadiusY)
        kick.float("uDt", config.collisions.timeStep)
        kick.float("uGravity", config.gravity.gravitationalConstant)
        kick.float("uSigmaCells", config.gravity.softeningCells)
        kick.int(
            "uExactCellThreshold",
            config.gravity.exactNeighborBodyLimit,
        )
        kick.int(
            "uMaximumHomeInteractions",
            config.gravity.maximumHomeBodyInteractions,
        )
        kick.float(
            "uMinimumDistanceCellFraction",
            config.gravity.minimumDistanceCellFraction,
        )
        kick.float("uDomainAspect", domainAspect)
        dispatchBodies(activeBodies)
        barrier()
        profiler?.end(GpuStage.GRAVITY_KICK)
    }

    /**
     * Reorders bodies along a Morton curve of screen tiles, so bodies that are
     * close on screen get close IDs and every neighbor search reads nearby
     * memory. A pure permutation: positions, velocities and accelerations are
     * copied bit for bit, and no contact or force depends on an ID's value.
     */
    private fun sortBodies(activeBodies: Int) {
        profiler?.begin(GpuStage.SPATIAL_SORT)
        sortCount.use()
        bind(0, bodies)
        bind(1, sortCounts)
        sortCount.int("uCount", activeBodies)
        dispatchBodies(activeBodies)
        barrier()

        sortScan.use()
        bind(0, sortCounts)
        bind(1, sortOffsets)
        bind(2, sortBlockTotals)
        sortScan.int("uLength", sortTiles)
        glDispatchCompute(sortBlocks, 1, 1)
        barrier()
        bind(0, sortBlockTotals)
        bind(1, sortBlockOffsets)
        bind(2, sortTotal)
        sortScan.int("uLength", sortBlocks)
        glDispatchCompute(1, 1, 1)
        barrier()

        sortScatter.use()
        bind(0, bodies)
        bind(1, acceleration)
        bind(2, collisionBodies)
        bind(3, spareAcceleration)
        bind(4, sortCounts)
        bind(5, sortOffsets)
        bind(6, sortBlockOffsets)
        sortScatter.int("uCount", activeBodies)
        dispatchBodies(activeBodies)
        barrier()
        bodies = collisionBodies.also { collisionBodies = bodies }
        acceleration = spareAcceleration.also { spareAcceleration = acceleration }
        // Cached candidate lists and linked grids name the old IDs.
        neighborCache?.invalidate() ?: clearUnsignedIntBuffer(collisionHeads)
        profiler?.end(GpuStage.SPATIAL_SORT)
    }

    /**
     * Follows the active body count. The spectral kernel holds 1/mass, so it
     * is rebuilt (two forward FFTs) only when the count changes, e.g. when a
     * projectile is fired. An empty screen keeps a unit mass.
     */
    private fun normalizeTo(activeBodies: Int) {
        val mass = max(1, activeBodies).toFloat()
        if (mass == normalizationMass) return
        normalizationMass = mass
        initializeKernel()
    }

    private fun inverse(base: Int) {
        transform(base, inverse = true)
    }

    private fun forward(base: Int) {
        transform(base, inverse = false)
    }

    private fun initializeKernel() {
        kernelInit.use()
        bind(0, kernelX)
        bind(1, kernelY)
        kernelInit.int("uGridWidth", meshWidth)
        kernelInit.int("uGridHeight", meshHeight)
        kernelInit.int("uPhysicalWidth", physicalGridWidth)
        kernelInit.int("uPhysicalHeight", physicalGridHeight)
        kernelInit.float("uDomainAspect", domainAspect)
        kernelInit.float("uGravity", config.gravity.gravitationalConstant)
        kernelInit.float("uTotalMass", normalizationMass)
        kernelInit.float("uSigmaCells", config.gravity.softeningCells)
        dispatch(meshCells)
        barrier()
        forward(kernelX)
        forward(kernelY)
    }

    private fun initializeTwiddles() {
        // One table for every axis length: entries for halfWidth h start at h - 1.
        var halfWidth = 1
        while (halfWidth < maximumFftAxis) {
            twiddleInit.use()
            bind(0, twiddles)
            twiddleInit.int("uOffset", halfWidth - 1)
            twiddleInit.int("uHalfWidth", halfWidth)
            dispatch(halfWidth)
            halfWidth *= 2
        }
        barrier()
    }

    private fun transform(base: Int, inverse: Boolean) {
        sharedFft.use()
        bind(0, base)
        bind(1, twiddles)
        sharedFft.int("uGridWidth", meshWidth)
        sharedFft.int("uGridHeight", meshHeight)
        sharedFft.int("uInverse", if (inverse) 1 else 0)
        sharedFft.int("uAxis", 0)
        glDispatchCompute(meshHeight, 1, 1)
        barrier()
        sharedFft.int("uAxis", 1)
        glDispatchCompute(meshWidth, 1, 1)
        barrier()
    }

    private fun cpuFft2d(values: DoubleArray, inverse: Boolean) {
        for (axis in 0..1) {
            val axisSize = if (axis == 0) gridWidth else gridHeight
            val lines = if (axis == 0) gridHeight else gridWidth
            val bits = axisSize.countTrailingZeroBits()
            val real = DoubleArray(axisSize)
            val imaginary = DoubleArray(axisSize)
            repeat(lines) { line ->
                repeat(axisSize) { coordinate ->
                    val source = Integer.reverse(coordinate) ushr (32 - bits)
                    val index = if (axis == 0) source + line * gridWidth
                    else line + source * gridWidth
                    real[coordinate] = values[index * 2]
                    imaginary[coordinate] = values[index * 2 + 1]
                }
                var stageWidth = 2
                while (stageWidth <= axisSize) {
                    val half = stageWidth / 2
                    val angleScale = (if (inverse) 2.0 else -2.0) * Math.PI / stageWidth
                    var base = 0
                    while (base < axisSize) {
                        repeat(half) { within ->
                            val lower = base + within
                            val upper = lower + half
                            val angle = angleScale * within
                            val wr = kotlin.math.cos(angle)
                            val wi = kotlin.math.sin(angle)
                            val upperReal = real[upper] * wr - imaginary[upper] * wi
                            val upperImaginary = real[upper] * wi + imaginary[upper] * wr
                            val lowerReal = real[lower]
                            val lowerImaginary = imaginary[lower]
                            real[lower] = lowerReal + upperReal
                            imaginary[lower] = lowerImaginary + upperImaginary
                            real[upper] = lowerReal - upperReal
                            imaginary[upper] = lowerImaginary - upperImaginary
                        }
                        base += stageWidth
                    }
                    stageWidth *= 2
                }
                repeat(axisSize) { coordinate ->
                    val index = if (axis == 0) coordinate + line * gridWidth
                    else line + coordinate * gridWidth
                    values[index * 2] = real[coordinate]
                    values[index * 2 + 1] = imaginary[coordinate]
                }
            }
        }
    }

    private fun seedBodies() {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, bodies)
        val mappedBodies = glMapBuffer(GL_SHADER_STORAGE_BUFFER, GL_WRITE_ONLY, bodyBytes, null)
            ?.asFloatBuffer() ?: error("Unable to map particle buffer")
        val initialState = UniformParticleInitializer.fillBodies(
            bodies = mappedBodies,
            count = config.particles.capacity,
            seed = config.particles.seed
                ?: java.security.SecureRandom().nextLong(),
        )
        config.particles.squareTestPixels?.let { side ->
            UniformParticleInitializer.placePixelSquare(
                bodies = mappedBodies,
                count = config.particles.initialBodies,
                widthPixels = side,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                seed = initialState.seed,
            )
        }
        config.particles.discTestRadiusPixels?.let { radius ->
            UniformParticleInitializer.placeRandomDisc(
                bodies = mappedBodies,
                count = config.particles.initialBodies,
                radiusPixels = radius,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                seed = initialState.seed,
            )
        }
        scenarioState?.let { state ->
            require(state.size <= config.particles.capacity * 4)
            mappedBodies.put(0, state)
        }
        System.err.println(
            "Particle seed: ${java.lang.Long.toUnsignedString(initialState.seed)}"
        )

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, bodies)
        check(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) { "Particle buffer became corrupt while seeding" }
        // Initialize both ping-pong buffers, including inactive capacity
        // reserved for projectiles.
        copy(bodies, collisionBodies, bodyBytes)
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

    private fun clearUnsignedIntBuffer(buffer: Int) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        glClearBufferData(
            GL_SHADER_STORAGE_BUFFER,
            GL_R32UI,
            GL_RED_INTEGER,
            GL_UNSIGNED_INT,
            null as ByteBuffer?,
        )
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
    }

    private fun buffer(bytes: Long): Int = glGenBuffers().also {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
        glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW)
    }

    private fun bind(binding: Int, buffer: Int) {
        if (boundStorageBuffers[binding] != buffer) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, buffer)
            boundStorageBuffers[binding] = buffer
        }
    }

    private fun dispatch(items: Int) = glDispatchCompute((items + 255) / 256, 1, 1)
    private fun dispatchBodies(items: Int) = dispatch(items)
    private fun dispatchCollisionBodies(items: Int, substepIndex: Int?) {
        if (config.collisions.adaptive && substepIndex != null) {
            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, collisionDispatchCommands)
            glDispatchComputeIndirect(
                substepIndex.toLong() * 3L * Int.SIZE_BYTES,
            )
        } else {
            dispatchBodies(items)
        }
    }

    private fun barrier() =
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)

    private fun massScaleFor(totalMass: Float): Int =
        (4_000_000_000.0 / totalMass).toLong().coerceIn(1L, 65_536L).toInt()

    private fun copy(source: Int, destination: Int, bytes: Long) {
        glBindBuffer(GL_COPY_READ_BUFFER, source)
        glBindBuffer(GL_COPY_WRITE_BUFFER, destination)
        glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, bytes)
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
    }

    override fun close() {
        neighborCache?.close()
        listOf(
            clear,
            build,
            fftInit,
            sharedFft,
            twiddleInit,
            kernelInit,
            convolve,
            kick,
            collisionClear,
            collisionBuild,
            collisionCount,
            collisionSolve,
            collisionAdaptiveReset,
            collisionAdaptiveReduce,
            collisionAdaptiveFinalize,
            stopBodiesProgram,
            trajectoryCompute,
            sortCount,
            sortScan,
            sortScatter,
        )
            .forEach(GlProgram::close)
        intArrayOf(
            bodies,
            collisionBodies,
            acceleration,
            spareAcceleration,
            sortCounts,
            sortOffsets,
            sortBlockTotals,
            sortBlockOffsets,
            sortTotal,
            density,
            cellHeads,
            cellCounts,
            nextBody,
            collisionHeads,
            collisionNext,
            collisionContactCounts,
            collisionAdaptiveState,
            collisionDispatchCommands,
            pressures,
            fft,
            forceX,
            forceY,
            kernelX,
            kernelY,
            twiddles,
            trajectory,
        )
            .forEach(::glDeleteBuffers)
    }

    companion object {
        const val TRAJECTORY_VERTICES_PER_STEP = 6
        /** Longest line the shared-memory FFT holds (fft_shared.comp). */
        private const val MAXIMUM_FFT_AXIS = 2048
        /** Largest float below 1, as in the collision shaders. */
        private const val MAXIMUM_UNIT = 0.99999994f
        /** Values scanned per workgroup by spatial_sort_scan.comp. */
        private const val SORT_SCAN_BLOCK = 1024
        /** Target reordering tile edge; a tile holds a few dozen dense bodies. */
        private const val SORT_TILE_PIXELS = 4

        /**
         * Power-of-two tiles per axis, at most 1024: two scan levels of 1024
         * values then cover every tile.
         */
        private fun sortAxisBits(extent: Int): Int =
            (31 - Integer.numberOfLeadingZeros(max(1, extent / SORT_TILE_PIXELS))).coerceAtMost(10)
    }
}
