package dev.gravity

import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_FOCUSED
import org.lwjgl.glfw.GLFW.GLFW_FOCUS_ON_SHOW
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_0
import org.lwjgl.glfw.GLFW.GLFW_KEY_9
import org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL
import org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD
import org.lwjgl.glfw.GLFW.GLFW_KEY_P
import org.lwjgl.glfw.GLFW.GLFW_KEY_R
import org.lwjgl.glfw.GLFW.GLFW_KEY_S
import org.lwjgl.glfw.GLFW.GLFW_KEY_B
import org.lwjgl.glfw.GLFW.GLFW_KEY_C
import org.lwjgl.glfw.GLFW.GLFW_KEY_G
import org.lwjgl.glfw.GLFW.GLFW_KEY_L
import org.lwjgl.glfw.GLFW.GLFW_KEY_M
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwFocusWindow
import org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor
import org.lwjgl.glfw.GLFW.glfwGetVideoMode
import org.lwjgl.glfw.GLFW.glfwGetWindowAttrib
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFW.glfwSetKeyCallback
import org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback
import org.lwjgl.glfw.GLFW.glfwSetErrorCallback
import org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback
import org.lwjgl.glfw.GLFW.glfwSetScrollCallback
import org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose
import org.lwjgl.glfw.GLFW.glfwShowWindow
import org.lwjgl.glfw.GLFW.glfwSwapBuffers
import org.lwjgl.glfw.GLFW.glfwSwapInterval
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWaitEventsTimeout
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.glfw.GLFW.glfwWindowShouldClose
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_FALSE
import org.lwjgl.opengl.GL11.GL_LINES
import org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_RENDERER
import org.lwjgl.opengl.GL11.GL_VENDOR
import org.lwjgl.opengl.GL11.GL_VERSION
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL11.glPointSize
import org.lwjgl.opengl.GL11.glGetString
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL13.GL_MULTISAMPLE
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.opengl.GL31.glDrawArraysInstanced
import org.lwjgl.opengl.GL43
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

private data class ProjectileGesture(
    val originX: Float,
    val originY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val diameterPixels: Float,
)

/** A screen rectangle in physics pixels (origin bottom-left), filled where [mask] is black if given. */
private data class BlockGesture(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val mask: BlockMask? = null,
)

class GravityApplication(private val config: GravityConfig) : AutoCloseable {
    private var window = 0L
    private var pipeline: GravityPipeline? = null
    private var particleRenderer: ParticleRenderer? = null
    private var trajectoryProgram: GlProgram? = null
    private var projectilePreviewProgram: GlProgram? = null
    private var blockPreviewProgram: GlProgram? = null
    private var fpsOverlay: FpsOverlay? = null
    private var scenarioMenu: ScenarioMenu? = null
    private var scenario: MenuScenario? = config.start.scenario
    private var requestedScenario: MenuScenario? = null
    /** Blocks of the current block scenario, glued after its loose bodies. */
    private var scenarioBlocks: List<ScenarioBlock> = emptyList()
    /**
     * The walls, gravity, liquid and field the user had before a block
     * scenario took them over; they come back with the next cosmic scenario.
     */
    private var savedEnvironment: Environment? = null
    private var sessionConfig = config
    /** Screen-edge walls instead of the torus; kept across resets. */
    private var bounded = config.start.walls && GravityPipeline.supportsWalls(config.gravity).also { supported ->
        if (config.start.walls && !supported) {
            System.err.println("Walls need an FFT grid of at most 1024 cells per axis; starting on the torus")
        }
    }
    private var vao = 0
    private var timer: GpuTimer? = null
    private var profiler: GpuProfiler? = null
    // Benchmarks measure every substep; adaptive collisions choose their own.
    private val substepGovernor = SubstepGovernor(
        config.collisions.maximumSubsteps,
        if (config.collisions.adaptive || config.diagnostics.benchmarkScenario != null) 0.0
        else config.collisions.gpuBudgetMillis,
    )
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var resetRequested = false
    private var stopRequested = false
    private var clearRequested = false
    /** G: gravity between bodies; kept across resets. */
    private var mutualGravity = config.start.mutualGravity
    /** L: bodies outside blocks behave as a liquid; kept across resets. */
    private var liquid = config.start.liquid
    /** Arrow-key fields, each toggled independently; opposite arrows cancel. */
    private val fieldDirections = LinkedHashSet(config.start.field)
    private var paused = false
    private val simulationSpeed = SimulationSpeed(
        scalePerScroll = config.presentation.timeScalePerScroll,
        minimumScale = config.presentation.minimumTimeScale,
    )
    private val trajectoryUpdates = TrajectoryUpdateScheduler(
        updatesPerSecond = config.trajectory.updatesPerSecond,
    )
    private var cursorX = 0.0
    private var cursorY = 0.0
    private var dragOriginX = 0f
    private var dragOriginY = 0f
    private var draggingProjectile = false
    private var pendingProjectile: ProjectileGesture? = null
    private var blockOriginX = 0f
    private var blockOriginY = 0f
    private var draggingBlock = false
    private var pendingBlock: BlockGesture? = null
    /** M: the picture of BlockMask.DIRECTORY the RMB block takes its outline from; null for a rectangle. */
    private var blockMask: BlockMask? = null
    private var projectileDiameterPixels = config.projectiles.defaultDiameterPixels
    private var projectileBodyCount = 0
    private val plusKeyProjectileDiameterPixels by lazy {
        CircularProjectile.diameterForBodyCount(
            config.collisions.contactDiameterPixels,
            config.projectiles.plusKeyBodyCount,
        )
    }

    fun run() {
        initWindow()
        profiler = createProfiler()
        var simulation = createSimulation()
        pipeline = simulation
        projectileBodyCount = simulation.projectileBodyCount(projectileDiameterPixels)
        particleRenderer = ParticleRenderer(
            viewportWidth,
            viewportHeight,
            config.presentation,
            config.collisions.contactDiameterPixels,
        )
        trajectoryProgram = GlProgram.graphics(
            "/shaders/trajectory.vert",
            "/shaders/trajectory.frag",
        )
        projectilePreviewProgram = GlProgram.graphics(
            "/shaders/projectile_preview.vert",
            "/shaders/trajectory.frag",
        )
        blockPreviewProgram = GlProgram.graphics(
            "/shaders/block_preview.vert",
            "/shaders/trajectory.frag",
        )
        vao = glGenVertexArrays()
        fpsOverlay = FpsOverlay(
            viewportWidth,
            viewportHeight,
            config.presentation,
            config.projectiles,
        )
        scenarioMenu = ScenarioMenu(viewportWidth, viewportHeight, config.presentation)
        timer = if (profiler == null) {
            GpuTimer(config.diagnostics.timerRingSlots)
        } else {
            null
        }

        var active = sessionConfig.particles.initialBodies
        active += spawnScenarioBlocks(simulation, active)
        initializeSimulation(simulation, active)
        activateFullscreenWindow()
        render(
            activeBodies = active,
            fps = 0.0,
            trajectoryVisible = false,
            futureProjectileSpeed = null,
            block = null,
        )
        glfwSwapBuffers(window)
        glfwPollEvents()
        activateFullscreenWindow()
        val consoleReportNanos =
            config.presentation.consoleReportMillis * 1_000_000L
        val fpsUpdateNanos =
            config.presentation.fpsUpdateMillis * 1_000_000L
        var reportAt = System.nanoTime() + consoleReportNanos
        var frames = 0
        var totalFrames = 0L
        var reportStart = System.nanoTime()
        var overlayWindowStart = reportStart
        var overlayFrames = 0
        var displayedFps = 0.0
        var smoothedGpuMillis = config.presentation.targetGpuMillis
        val benchmarkStart = config.diagnostics.benchmarkWarmupFrames.toLong()
        val benchmarkEnd = benchmarkStart + config.diagnostics.benchmarkFrames
        var benchmarkProjectileLaunched = false
        var benchmarkProfileReported = false

        while (!glfwWindowShouldClose(window)) {
            if (resetRequested) {
                requestedScenario?.let(::selectScenario)
                requestedScenario = null
                // C clears the screen: a fresh session with no active bodies;
                // projectiles can be fired into it.
                val clearing = clearRequested
                clearRequested = false
                // A reset starts a new GPU session, just like application
                // startup, instead of retaining resources and timer queries
                // from the previous simulation.
                glFinish()
                timer?.close()
                timer = null
                profiler?.close()
                profiler = createProfiler()
                simulation.close()
                pipeline = null
                simulation = createSimulation()
                pipeline = simulation
                active = if (clearing) 0 else sessionConfig.particles.initialBodies
                if (!clearing) active += spawnScenarioBlocks(simulation, active)
                projectileBodyCount =
                    simulation.projectileBodyCount(projectileDiameterPixels)
                timer = if (profiler == null) {
                    GpuTimer(config.diagnostics.timerRingSlots)
                } else {
                    null
                }
                initializeSimulation(simulation, active)
                pendingProjectile = null
                draggingProjectile = false
                pendingBlock = null
                draggingBlock = false
                trajectoryUpdates.reset()
                displayedFps = 0.0
                overlayFrames = 0
                val resetTime = System.nanoTime()
                overlayWindowStart = resetTime
                frames = 0
                reportStart = resetTime
                reportAt = resetTime + consoleReportNanos
                smoothedGpuMillis = config.presentation.targetGpuMillis
                substepGovernor.reset()
                resetRequested = false
            }
            if (config.diagnostics.benchmarkScenario == BenchmarkScenario.PROJECTILE &&
                !benchmarkProjectileLaunched &&
                totalFrames == benchmarkStart
            ) {
                val spawned = simulation.spawnProjectile(
                    firstBody = active,
                    centerX = viewportWidth * config.projectiles.benchmarkOriginX,
                    centerY = viewportHeight * config.projectiles.benchmarkOriginY,
                    velocityX = config.projectiles.benchmarkVelocityX,
                    velocityY = config.projectiles.benchmarkVelocityY,
                    diameterPixels = config.projectiles.benchmarkDiameterPixels,
                )
                check(spawned > 0) {
                    "Benchmark projectile needs spare capacity (active=$active, " +
                        "capacity=${config.particles.capacity})"
                }
                active += spawned
                benchmarkProjectileLaunched = true
                System.err.printf(
                    Locale.ROOT,
                    "benchmark projectile=%,d bodies total=%,d diameter=%.0f px " +
                        "velocity=(%.1f, %.1f) px/s%n",
                    spawned,
                    active,
                    config.projectiles.benchmarkDiameterPixels,
                    config.projectiles.benchmarkVelocityX,
                    config.projectiles.benchmarkVelocityY,
                )
            }
            pendingProjectile?.let { projectile ->
                val spawned = simulation.spawnProjectile(
                    firstBody = active,
                    centerX = projectile.originX,
                    centerY = projectile.originY,
                    velocityX = projectile.velocityX,
                    velocityY = projectile.velocityY,
                    diameterPixels = projectile.diameterPixels,
                )
                if (spawned > 0) {
                    active += spawned
                    System.err.printf(
                        Locale.ROOT,
                        "projectile=%,d bodies  diameter=%.0f px  total=%,d  " +
                            "velocity=(%.1f, %.1f) px/s%n",
                        spawned,
                        projectile.diameterPixels,
                        active,
                        projectile.velocityX,
                        projectile.velocityY,
                    )
                } else {
                    System.err.println("Projectile skipped: particle capacity is full")
                }
                pendingProjectile = null
            }
            pendingBlock?.let { block ->
                val spawned = simulation.spawnBlock(
                    firstBody = active,
                    minX = block.minX,
                    minY = block.minY,
                    maxX = block.maxX,
                    maxY = block.maxY,
                    mask = block.mask,
                )
                if (spawned > 0) {
                    active += spawned
                    System.err.printf(
                        Locale.ROOT,
                        "block=%,d bodies  shape=%s  size=%.0fx%.0f px  total=%,d%n",
                        spawned,
                        block.mask?.name ?: "rectangle",
                        block.maxX - block.minX,
                        block.maxY - block.minY,
                        active,
                    )
                } else if (blockBodyCount(block) > 0) {
                    System.err.println("Block skipped: particle capacity, the ${RigidBlocks.MAXIMUM_GROUPS}-block limit or the room for block shapes is reached")
                }
                pendingBlock = null
            }
            if (stopRequested) {
                simulation.stopBodies(active)
                stopRequested = false
            }
            val collectProfile = config.diagnostics.benchmarkScenario == null ||
                totalFrames in benchmarkStart..<benchmarkEnd
            profiler?.beginFrame(totalFrames, collect = collectProfile)
            timer?.begin()
            if (simulationSpeed.shouldAdvance(paused) && active > 0) {
                simulation.step(active)
            }
            val trajectory = if (draggingProjectile) {
                projectileGesture(
                    targetX = cursorX.toFloat(),
                    targetY = viewportHeight - cursorY.toFloat(),
                )
            } else {
                null
            }
            if (trajectory == null) {
                trajectoryUpdates.reset()
            } else if (trajectoryUpdates.shouldUpdate(System.nanoTime())) {
                simulation.updateTrajectory(
                    activeBodies = active,
                    originX = trajectory.originX,
                    originY = trajectory.originY,
                    velocityX = trajectory.velocityX,
                    velocityY = trajectory.velocityY,
                )
            }
            val benchmarkTrajectoryVisible =
                config.diagnostics.benchmarkScenario == BenchmarkScenario.PROJECTILE &&
                    totalFrames in benchmarkStart..<benchmarkEnd
            if (benchmarkTrajectoryVisible) {
                simulation.updateTrajectory(
                    activeBodies = active,
                    originX = viewportWidth * config.projectiles.benchmarkOriginX,
                    originY = viewportHeight * config.projectiles.benchmarkOriginY,
                    velocityX = config.projectiles.benchmarkVelocityX,
                    velocityY = config.projectiles.benchmarkVelocityY,
                )
            }
            overlayFrames++
            val overlayNow = System.nanoTime()
            if (overlayNow - overlayWindowStart >= fpsUpdateNanos) {
                displayedFps = overlayFrames * 1e9 / (overlayNow - overlayWindowStart)
                overlayFrames = 0
                overlayWindowStart = overlayNow
            }
            val block = if (draggingBlock) currentBlock() else null
            val futureProjectileSpeed = when {
                !draggingProjectile -> null
                trajectory == null -> 0.0
                else -> hypot(trajectory.velocityX, trajectory.velocityY).toDouble()
            }
            profiler?.begin(GpuStage.RENDER)
            render(
                activeBodies = active,
                fps = displayedFps,
                trajectoryVisible = trajectory != null || benchmarkTrajectoryVisible,
                futureProjectileSpeed = futureProjectileSpeed,
                block = block,
            )
            profiler?.end(GpuStage.RENDER)
            profiler?.endFrame()
            val gpuMillis = timer?.end() ?: profiler?.latestFrameMillis ?: Double.NaN
            if (!gpuMillis.isNaN()) {
                smoothedGpuMillis += config.presentation.gpuTimeSmoothing *
                    (gpuMillis - smoothedGpuMillis)
            }
            simulation.collisionSubsteps = substepGovernor.update(gpuMillis)

            glfwSwapBuffers(window)
            glfwPollEvents()
            frames++
            totalFrames++
            val now = System.nanoTime()
            if (now >= reportAt) {
                val fps = frames * 1e9 / (now - reportStart)
                System.err.printf(
                    Locale.ROOT,
                    "bodies=%,d  fps=%.1f  gpu=%.2f ms  substeps=%d  grid=%dx%d%n",
                    active,
                    fps,
                    smoothedGpuMillis,
                    substepGovernor.substeps,
                    config.gravity.gridWidth,
                    config.gravity.gridHeight,
                )
                frames = 0
                reportStart = now
                reportAt = now + consoleReportNanos
            }
            if (config.diagnostics.benchmarkScenario != null &&
                totalFrames >= benchmarkEnd &&
                profiler!!.pendingFrames == 0 &&
                !benchmarkProfileReported
            ) {
                benchmarkProfileReported = true
                reportProfile(
                    summary = profiler!!.summary(),
                    activeBodies = active,
                    requestedFrames = config.diagnostics.benchmarkFrames,
                )
                val cluster = simulation.measureCluster(active)
                reportCluster(cluster)
                if (config.collisions.adaptive) {
                    reportAdaptiveCollisions(
                        simulation.measureAdaptiveCollisions(),
                    )
                }
                glfwSetWindowShouldClose(window, true)
            } else if (
                config.diagnostics.maxFrames != null &&
                totalFrames >= config.diagnostics.maxFrames
            ) {
                profiler?.let {
                    reportProfile(
                        summary = it.summary(),
                        activeBodies = active,
                        requestedFrames = config.diagnostics.maxFrames,
                    )
                }
                val cluster = simulation.measureCluster(active)
                reportCluster(cluster)
                if (config.collisions.adaptive) {
                    reportAdaptiveCollisions(
                        simulation.measureAdaptiveCollisions(),
                    )
                }
                glfwSetWindowShouldClose(window, true)
            }
        }
    }

    private fun initWindow() {
        GLFWErrorCallback.createPrint(System.err).set()
        check(glfwInit()) { "GLFW initialization failed" }
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GL_FALSE)
        glfwWindowHint(GLFW_FOCUSED, 1)
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, 1)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)

        val monitor = glfwGetPrimaryMonitor()
        check(monitor != 0L) { "No primary monitor" }
        val mode = checkNotNull(glfwGetVideoMode(monitor)) { "No video mode" }
        viewportWidth = mode.width()
        viewportHeight = mode.height()
        cursorX = viewportWidth * 0.5
        cursorY = viewportHeight * 0.5
        window = glfwCreateWindow(mode.width(), mode.height(), "Gravity", monitor, 0L)
        check(window != 0L) { "Fullscreen OpenGL window creation failed" }
        glfwSetKeyCallback(window) { target, key, _, action, _ ->
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) glfwSetWindowShouldClose(target, true)
            if (key == GLFW_KEY_P && action == GLFW_PRESS) paused = !paused
            if (key == GLFW_KEY_R && action == GLFW_PRESS) resetRequested = true
            if (key == GLFW_KEY_S && action == GLFW_PRESS) stopRequested = true
            if (key == GLFW_KEY_G && action == GLFW_PRESS) {
                mutualGravity = !mutualGravity
                pipeline?.mutualGravity = mutualGravity
            }
            if (key == GLFW_KEY_L && action == GLFW_PRESS) {
                liquid = !liquid
                pipeline?.liquid = liquid
            }
            if (key == GLFW_KEY_M && action == GLFW_PRESS) nextBlockMask()
            if (key == GLFW_KEY_C && action == GLFW_PRESS) {
                clearRequested = true
                resetRequested = true
            }
            FieldDirection.forKey(key)?.let { direction ->
                if (action == GLFW_PRESS) {
                    if (!fieldDirections.remove(direction)) fieldDirections += direction
                    pipeline?.let(::applyField)
                }
            }
            if (key == GLFW_KEY_B && action == GLFW_PRESS) {
                pipeline?.let {
                    if (it.boundedSpaceSupported) {
                        bounded = !bounded
                        it.bounded = bounded
                        System.err.println("space=${if (bounded) "walls" else "torus"}")
                    } else {
                        System.err.println("Walls need an FFT grid of at most 1024 cells per axis")
                    }
                }
            }
            val selectedDiameter = when {
                action != GLFW_PRESS -> null
                key in GLFW_KEY_0..GLFW_KEY_9 ->
                    config.projectiles.diameterForDigit(key - GLFW_KEY_0)
                // "+" shares the "=" key on the main row; numpad "+" too.
                key == GLFW_KEY_EQUAL || key == GLFW_KEY_KP_ADD ->
                    plusKeyProjectileDiameterPixels
                else -> null
            }
            if (selectedDiameter != null) {
                projectileDiameterPixels = selectedDiameter
                pipeline?.let {
                    projectileBodyCount =
                        it.projectileBodyCount(projectileDiameterPixels)
                }
            }
        }
        glfwSetCursorPosCallback(window) { _, x, y ->
            cursorX = x
            cursorY = y
        }
        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                when (action) {
                    GLFW_PRESS -> {
                        blockOriginX = cursorX.toFloat()
                        blockOriginY = viewportHeight - cursorY.toFloat()
                        draggingBlock = true
                    }
                    org.lwjgl.glfw.GLFW.GLFW_RELEASE -> {
                        if (draggingBlock) pendingBlock = currentBlock()
                        draggingBlock = false
                    }
                }
                return@glfwSetMouseButtonCallback
            }
            if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
            val physicsX = cursorX.toFloat()
            val physicsY = viewportHeight - cursorY.toFloat()
            when (action) {
                GLFW_PRESS -> {
                    val clicked = scenarioMenu?.scenarioAt(cursorX, cursorY)
                    if (clicked != null) {
                        requestedScenario = clicked
                        resetRequested = true
                        return@glfwSetMouseButtonCallback
                    }
                    dragOriginX = physicsX
                    dragOriginY = physicsY
                    draggingProjectile = true
                    trajectoryUpdates.reset()
                }
                org.lwjgl.glfw.GLFW.GLFW_RELEASE -> {
                    if (draggingProjectile) {
                        pendingProjectile = projectileGesture(physicsX, physicsY)
                    }
                    draggingProjectile = false
                    trajectoryUpdates.reset()
                }
            }
        }
        glfwSetScrollCallback(window) { _, _, yOffset ->
            simulationSpeed.scroll(yOffset)
        }
        glfwMakeContextCurrent(window)
        glfwSwapInterval(0)
        GL.createCapabilities()
        check(GL.getCapabilities().OpenGL43) { "OpenGL 4.3 compute shaders are required" }
        glDisable(GL_MULTISAMPLE)
        glViewport(0, 0, mode.width(), mode.height())
        glClearColor(0f, 0f, 0f, 1f)
        glPointSize(config.presentation.bodyPointSizePixels)
        glfwShowWindow(window)
        glfwFocusWindow(window)
        glClear(GL_COLOR_BUFFER_BIT)
        glfwSwapBuffers(window)
        glfwPollEvents()
        var foreground = false
        for (attempt in 0 until 8) {
            foreground = activateFullscreenWindow()
            if (foreground) break
            glfwWaitEventsTimeout(0.01)
        }
        System.err.println(
            "Gravity: viewport=${mode.width()}x${mode.height()}, " +
                "space=${mode.width()}x${mode.height()}, " +
                "initial=${config.particles.initialBodies}, " +
                "grid=${config.gravity.gridWidth}x${config.gravity.gridHeight}, " +
                "collisions=${config.collisions.maximumSubsteps}, " +
                "diameter=${config.collisions.contactDiameterPixels}px, " +
                "squareTest=${config.particles.squareTestPixels ?: "off"}, " +
                "discTest=${config.particles.discTestRadiusPixels ?: "off"}, " +
                "seed=${config.particles.seed?.toString() ?: "random"}, " +
                "profile=${config.diagnostics.profile}, " +
                "benchmark=${config.diagnostics.benchmarkScenario?.name?.lowercase() ?: "off"}, " +
                "foreground=$foreground; RMB drag creates a glued rectangular block; LMB drag fires a " +
                "${projectileDiameterPixels.toInt()}px projectile; " +
                "digits 1-9 use ${config.projectiles.digitDiameterStepPixels.toInt()}px steps; " +
                "0 selects ${config.projectiles.maximumDiameterPixels.toInt()}px; " +
                "wheel controls time speed; P pauses; S stops all bodies; Esc exits"
        )
    }

    private fun activateFullscreenWindow(): Boolean {
        glfwFocusWindow(window)
        val foreground = WindowsForegroundActivator.activate(window)
        glfwPollEvents()
        return foreground && glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0
    }

    private fun projectileGesture(targetX: Float, targetY: Float): ProjectileGesture? {
        val deltaX = targetX - dragOriginX
        val deltaY = targetY - dragOriginY
        if (hypot(deltaX, deltaY) < config.projectiles.minimumDragPixels) return null
        return ProjectileGesture(
            originX = dragOriginX,
            originY = dragOriginY,
            velocityX = deltaX * config.projectiles.velocityPerDragPixel,
            velocityY = deltaY * config.projectiles.velocityPerDragPixel,
            diameterPixels = projectileDiameterPixels,
        )
    }

    /**
     * The rectangle between the RMB press point and the cursor; with a mask,
     * the largest of the picture's aspect within it, from the press point.
     */
    private fun currentBlock(): BlockGesture {
        var x = cursorX.toFloat().coerceIn(0f, viewportWidth.toFloat())
        var y = (viewportHeight - cursorY.toFloat()).coerceIn(0f, viewportHeight.toFloat())
        val mask = blockMask
        if (mask != null) {
            val fitted = mask.fit(abs(x - blockOriginX), abs(y - blockOriginY))
            x = blockOriginX + sign(x - blockOriginX) * fitted.x
            y = blockOriginY + sign(y - blockOriginY) * fitted.y
        }
        return BlockGesture(
            minX = minOf(blockOriginX, x),
            minY = minOf(blockOriginY, y),
            maxX = maxOf(blockOriginX, x),
            maxY = maxOf(blockOriginY, y),
            mask = mask,
        )
    }

    private fun blockBodyCount(block: BlockGesture): Int {
        val diameter = config.collisions.contactDiameterPixels
        val width = block.maxX - block.minX
        val height = block.maxY - block.minY
        return block.mask?.bodyCount(diameter, width, height) ?: RectangularBlock.bodyCount(diameter, width, height)
    }

    /**
     * Takes the next picture of BlockMask.DIRECTORY, by name, for the RMB
     * block; after the last, the rectangle again. The directory is read
     * afresh, so pictures put there while running count.
     */
    private fun nextBlockMask() {
        val files = BlockMask.files()
        if (files.isEmpty()) {
            System.err.println("No pictures in ${BlockMask.DIRECTORY.absolutePath}; blocks stay rectangles")
            blockMask = null
            return
        }
        val current = files.indexOfFirst { it.nameWithoutExtension == blockMask?.name }
        blockMask = null
        for (file in files.drop(current + 1)) {
            try {
                blockMask = BlockMask.load(file)
                break
            } catch (exception: Exception) {
                System.err.println("Mask ${file.name} skipped: ${exception.message}")
            }
        }
        System.err.println(blockMask?.let { "block shape=${it.name} (${it.width}x${it.height} px picture)" } ?: "block shape=rectangle")
    }

    /**
     * Starts the selected scenario, or the configured uniform start when none
     * is selected. Gravity is normalised to the session's initial body count.
     */
    private fun createSimulation(): GravityPipeline {
        val current = scenario
        val space = ScenarioSpace(
            widthPixels = viewportWidth.toFloat(),
            heightPixels = viewportHeight.toFloat(),
            gravitationalConstant = config.gravity.gravitationalConstant,
            contactDiameterPixels = config.collisions.contactDiameterPixels,
        )
        val seed = config.particles.seed ?: java.security.SecureRandom().nextLong()
        val state: FloatArray?
        when (current) {
            is BlockScene -> {
                val setup = current.setup(space, seed)
                state = setup.bodies
                scenarioBlocks = setup.blocks
                // A block scenario brings its own box, field and liquid; the
                // user's come back with the next cosmic scenario.
                if (savedEnvironment == null) {
                    savedEnvironment = Environment(bounded, mutualGravity, liquid, fieldDirections.toSet())
                }
                bounded = GravityPipeline.supportsWalls(config.gravity)
                mutualGravity = false
                liquid = current.liquid
                fieldDirections.clear()
                if (current.field) fieldDirections += FieldDirection.DOWN
            }
            is Scenario -> {
                state = current.bodies(space, seed)
                scenarioBlocks = emptyList()
            }
            null -> {
                state = null
                scenarioBlocks = emptyList()
            }
        }
        val blockBodies = scenarioBlocks.sumOf {
            RectangularBlock.bodyCount(config.collisions.contactDiameterPixels, it.maxX - it.minX, it.maxY - it.minY)
        }
        sessionConfig = if (state == null) {
            config
        } else {
            val bodies = state.size / 4
            System.err.printf(Locale.ROOT, "scenario=%s bodies=%,d%n", current!!.title, bodies + blockBodies)
            config.copy(
                particles = config.particles.copy(
                    capacity = maxOf(config.particles.capacity, bodies + blockBodies),
                    initialBodies = bodies,
                    squareTestPixels = null,
                    discTestRadiusPixels = null,
                ),
                projectiles = if ((current as? BlockScene)?.unbreakable == true) {
                    config.projectiles.copy(blockStrength = Float.POSITIVE_INFINITY)
                } else {
                    config.projectiles
                },
            )
        }
        return GravityPipeline(
            sessionConfig,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            profiler = profiler,
            scenarioState = state,
            bounded = bounded,
        ).also {
            it.mutualGravity = mutualGravity
            it.liquid = liquid
            applyField(it)
        }
    }

    /** Walls, gravity between bodies, liquid mode and the field, as toggled by B, G, L and the arrows. */
    private data class Environment(
        val bounded: Boolean,
        val mutualGravity: Boolean,
        val liquid: Boolean,
        val field: Set<FieldDirection>,
    )

    /**
     * Makes [next] the scenario of the coming reset. Leaving block scenarios
     * brings back the environment they took over (createSimulation).
     */
    private fun selectScenario(next: MenuScenario) {
        if (next !is BlockScene) {
            savedEnvironment?.let {
                bounded = it.bounded
                mutualGravity = it.mutualGravity
                liquid = it.liquid
                fieldDirections.clear()
                fieldDirections += it.field
            }
            savedEnvironment = null
        }
        scenario = next
    }

    /** Glues the blocks of the scenario after [firstBody]; returns their bodies. */
    private fun spawnScenarioBlocks(simulation: GravityPipeline, firstBody: Int): Int {
        var spawned = 0
        for (block in scenarioBlocks) {
            spawned += simulation.spawnBlock(
                firstBody = firstBody + spawned,
                minX = block.minX,
                minY = block.minY,
                maxX = block.maxX,
                maxY = block.maxY,
                density = block.density,
                velocityX = block.velocityX,
                velocityY = block.velocityY,
                spin = block.spin,
            )
        }
        return spawned
    }

    private fun applyField(simulation: GravityPipeline) {
        // Authored for a 1440 px tall screen, like the scenarios.
        val strength = config.gravity.uniformFieldAcceleration * viewportHeight / 1440f
        simulation.fieldAccelerationX = strength * fieldDirections.sumOf { it.x }.toFloat()
        simulation.fieldAccelerationY = strength * fieldDirections.sumOf { it.y }.toFloat()
    }

    private fun initializeSimulation(simulation: GravityPipeline, activeBodies: Int) {
        simulation.initializeAcceleration(activeBodies)
        if (config.diagnostics.validateFft) {
            val validation = simulation.validateFft(activeBodies)
            System.err.printf(
                Locale.ROOT,
                "FFT validation absolute-error=%.9g relative-error=%.9g rms-error=%.9g%n",
                validation.maximumAbsoluteError,
                validation.maximumRelativeError,
                validation.rootMeanSquareError,
            )
        }
        // Warm and validate the preview path outside the first interactive
        // drag so showing it never introduces a one-frame shader hitch.
        simulation.updateTrajectory(
            activeBodies = activeBodies,
            originX = viewportWidth * 0.5f,
            originY = viewportHeight * 0.5f,
            velocityX = 0f,
            velocityY = 0f,
        )
    }

    private fun render(
        activeBodies: Int,
        fps: Double,
        trajectoryVisible: Boolean,
        futureProjectileSpeed: Double?,
        block: BlockGesture?,
    ) {
        particleRenderer!!.render(pipeline!!, activeBodies)
        glBindVertexArray(vao)
        if (trajectoryVisible) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            trajectoryProgram!!.use()
            trajectoryProgram!!.float(
                "uMinimumDashPixels",
                config.trajectory.minimumDashPixels,
            )
            pipeline!!.bindTrajectoryForRender()
            glDrawArrays(GL_LINES, 0, pipeline!!.trajectoryVertexCount)
            glDisable(GL_BLEND)
        }
        if (draggingProjectile) {
            val previewDashCount = (
                Math.PI * projectileDiameterPixels /
                    config.projectiles.previewPeriodPixels
                ).roundToInt().coerceAtLeast(1)
            projectilePreviewProgram!!.use()
            projectilePreviewProgram!!.vec2(
                "uViewport",
                viewportWidth.toFloat(),
                viewportHeight.toFloat(),
            )
            projectilePreviewProgram!!.vec2("uCenterPixels", dragOriginX, dragOriginY)
            projectilePreviewProgram!!.float(
                "uRadiusPixels",
                projectileDiameterPixels * 0.5f,
            )
            projectilePreviewProgram!!.int(
                "uDashCount",
                previewDashCount,
            )
            glDrawArraysInstanced(
                GL_LINES,
                0,
                previewDashCount * 2,
                TORUS_PREVIEW_INSTANCES,
            )
        }
        if (block != null) {
            val period = config.projectiles.previewPeriodPixels
            val horizontalDashes =
                ((block.maxX - block.minX) / period).roundToInt().coerceAtLeast(1)
            val verticalDashes =
                ((block.maxY - block.minY) / period).roundToInt().coerceAtLeast(1)
            blockPreviewProgram!!.use()
            blockPreviewProgram!!.vec2(
                "uViewport",
                viewportWidth.toFloat(),
                viewportHeight.toFloat(),
            )
            blockPreviewProgram!!.vec2("uMinPixels", block.minX, block.minY)
            blockPreviewProgram!!.vec2("uMaxPixels", block.maxX, block.maxY)
            blockPreviewProgram!!.int("uHorizontalDashes", horizontalDashes)
            blockPreviewProgram!!.int("uVerticalDashes", verticalDashes)
            glDrawArrays(GL_LINES, 0, 4 * (horizontalDashes + verticalDashes))
        }
        fpsOverlay!!.render(
            bounded = bounded,
            field = fieldDirections.joinToString(" ") { it.label }.ifEmpty { "off" },
            mutualGravity = mutualGravity,
            liquid = liquid,
            fps = fps,
            activeBodies = activeBodies,
            projectileBodies = projectileBodyCount,
            timeScale = simulationSpeed.scale,
            futureProjectileSpeed = futureProjectileSpeed,
            blockBodies = block?.let(::blockBodyCount),
            blockShape = blockMask?.name ?: "rectangle",
        )
        scenarioMenu!!.render(scenario, cursorX, cursorY)
    }

    private fun reportProfile(
        summary: GpuProfileSummary,
        activeBodies: Int,
        requestedFrames: Int,
    ) {
        System.err.printf(
            Locale.ROOT,
            "GPU_PROFILE scenario=%s initial-bodies=%d active-bodies=%d " +
                "grid=%dx%d viewport=%dx%d seed=%s warmup=%d " +
                "requested-samples=%d collected-samples=%d dropped=%d%n",
            config.diagnostics.benchmarkScenario?.name?.lowercase() ?: "diagnostic",
            config.particles.initialBodies,
            activeBodies,
            config.gravity.gridWidth,
            config.gravity.gridHeight,
            viewportWidth,
            viewportHeight,
            config.particles.seed?.toString() ?: "random",
            if (config.diagnostics.benchmarkScenario == null) {
                0
            } else {
                config.diagnostics.benchmarkWarmupFrames
            },
            requestedFrames,
            summary.frames,
            profiler?.droppedFrames ?: 0,
        )
        System.err.println(
            "GPU renderer: ${glGetString(GL_RENDERER)}; vendor=${glGetString(GL_VENDOR)}; " +
                "OpenGL=${glGetString(GL_VERSION)}"
        )
        System.err.print(summary.markdown())
    }

    private fun reportCluster(cluster: ClusterStatistics) {
        System.err.printf(
            Locale.ROOT,
            "cluster center=(%.1f, %.1f) covariance-area=%,.0f px^2 " +
                "equivalent-diameter=%.1f px core90=%,.0f px^2 " +
                "core99=%,.0f px^2 max-speed=%.1f px/s " +
                "avg-speed=%.1f px/s non-finite=%d%n",
            cluster.centerX,
            cluster.centerY,
            cluster.covarianceAreaPixels,
            cluster.equivalentDiameterPixels,
            cluster.core90AreaPixels,
            cluster.core99AreaPixels,
            cluster.maxSpeedPixelsPerSecond,
            cluster.averageSpeedPixelsPerSecond,
            cluster.nonFiniteBodies,
        )
    }

    private fun reportAdaptiveCollisions(
        statistics: AdaptiveCollisionStatistics,
    ) {
        System.err.printf(
            Locale.ROOT,
            "adaptive-collisions substeps=%d max-speed=%.1f px/s " +
                "max-penetration=%.6f px max-contacts=%d dense-bodies=%d%n",
            statistics.substeps,
            statistics.maximumSpeedPixelsPerSecond,
            statistics.maximumPenetrationPixels,
            statistics.maximumContactCount,
            statistics.denseContactBodies,
        )
    }

    override fun close() {
        timer?.close()
        profiler?.close()
        if (vao != 0) glDeleteVertexArrays(vao)
        fpsOverlay?.close()
        scenarioMenu?.close()
        projectilePreviewProgram?.close()
        blockPreviewProgram?.close()
        trajectoryProgram?.close()
        particleRenderer?.close()
        pipeline?.close()
        if (window != 0L) {
            glfwFreeCallbacks(window)
            glfwDestroyWindow(window)
        }
        glfwTerminate()
        glfwSetErrorCallback(null)?.free()
    }

    private fun createProfiler(): GpuProfiler? =
        if (config.diagnostics.profile) {
            GpuProfiler(
                ringSlots = config.diagnostics.profilerRingSlots,
                maxSegmentsPerFrame =
                    config.diagnostics.profilerMaximumSegmentsPerFrame,
                completedFrameCapacity =
                    config.diagnostics.profilerCompletedFrameCapacity,
            )
        } else {
            null
        }

    private companion object {
        const val TORUS_PREVIEW_INSTANCES = 9
    }
}
