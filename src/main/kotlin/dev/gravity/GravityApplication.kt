package dev.gravity

import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_FOCUSED
import org.lwjgl.glfw.GLFW.GLFW_FOCUS_ON_SHOW
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_0
import org.lwjgl.glfw.GLFW.GLFW_KEY_9
import org.lwjgl.glfw.GLFW.GLFW_KEY_P
import org.lwjgl.glfw.GLFW.GLFW_KEY_R
import org.lwjgl.glfw.GLFW.GLFW_KEY_S
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
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
import org.lwjgl.opengl.GL11.GL_POINTS
import org.lwjgl.opengl.GL11.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL11.glPointSize
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL13.GL_MULTISAMPLE
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.opengl.GL31.glDrawArraysInstanced
import org.lwjgl.opengl.GL43
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

private data class ProjectileGesture(
    val originX: Float,
    val originY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val diameterPixels: Float,
)

class GravityApplication(private val config: SimulationConfig) : AutoCloseable {
    private var window = 0L
    private var pipeline: GravityPipeline? = null
    private var renderProgram: GlProgram? = null
    private var trajectoryProgram: GlProgram? = null
    private var projectilePreviewProgram: GlProgram? = null
    private var fpsOverlay: FpsOverlay? = null
    private var vao = 0
    private var timer: GpuTimer? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var resetRequested = false
    private var stopRequested = false
    private var paused = false
    private val simulationSpeed = SimulationSpeed()
    private val trajectoryUpdates = TrajectoryUpdateScheduler()
    private var cursorX = 0.0
    private var cursorY = 0.0
    private var dragOriginX = 0f
    private var dragOriginY = 0f
    private var draggingProjectile = false
    private var pendingProjectile: ProjectileGesture? = null
    private var projectileDiameterPixels = CircularProjectile.DEFAULT_DIAMETER_PIXELS
    private var projectileBodyCount = 0

    fun run() {
        initWindow()
        var simulation = GravityPipeline(
            config,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        pipeline = simulation
        projectileBodyCount = simulation.projectileBodyCount(projectileDiameterPixels)
        renderProgram = GlProgram.graphics("/shaders/particles.vert", "/shaders/particles.frag")
        trajectoryProgram = GlProgram.graphics(
            "/shaders/trajectory.vert",
            "/shaders/trajectory.frag",
        )
        projectilePreviewProgram = GlProgram.graphics(
            "/shaders/projectile_preview.vert",
            "/shaders/trajectory.frag",
        )
        vao = glGenVertexArrays()
        fpsOverlay = FpsOverlay(viewportWidth, viewportHeight)
        timer = GpuTimer()

        var active = config.initialBodies
        initializeSimulation(simulation, active)
        activateFullscreenWindow()
        render(
            activeBodies = active,
            fps = 0.0,
            trajectoryVisible = false,
            futureProjectileSpeed = null,
        )
        glfwSwapBuffers(window)
        glfwPollEvents()
        activateFullscreenWindow()
        var reportAt = System.nanoTime() + 2_000_000_000L
        var frames = 0
        var totalFrames = 0L
        var reportStart = System.nanoTime()
        var overlayWindowStart = reportStart
        var overlayFrames = 0
        var displayedFps = 0.0
        var smoothedGpuMillis = config.targetGpuMillis

        while (!glfwWindowShouldClose(window)) {
            if (resetRequested) {
                active = config.initialBodies
                // A reset starts a new GPU session, just like application
                // startup, instead of retaining resources and timer queries
                // from the previous simulation.
                glFinish()
                timer!!.close()
                timer = null
                simulation.close()
                pipeline = null
                simulation = GravityPipeline(
                    config,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                )
                pipeline = simulation
                projectileBodyCount =
                    simulation.projectileBodyCount(projectileDiameterPixels)
                timer = GpuTimer()
                initializeSimulation(simulation, active)
                pendingProjectile = null
                draggingProjectile = false
                trajectoryUpdates.reset()
                displayedFps = 0.0
                overlayFrames = 0
                val resetTime = System.nanoTime()
                overlayWindowStart = resetTime
                frames = 0
                reportStart = resetTime
                reportAt = resetTime + 2_000_000_000L
                smoothedGpuMillis = config.targetGpuMillis
                resetRequested = false
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
            if (stopRequested) {
                simulation.stopBodies(active)
                stopRequested = false
            }
            timer!!.begin()
            if (simulationSpeed.shouldAdvance(paused)) {
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
            overlayFrames++
            val overlayNow = System.nanoTime()
            if (overlayNow - overlayWindowStart >= 250_000_000L) {
                displayedFps = overlayFrames * 1e9 / (overlayNow - overlayWindowStart)
                overlayFrames = 0
                overlayWindowStart = overlayNow
            }
            val futureProjectileSpeed = when {
                !draggingProjectile -> null
                trajectory == null -> 0.0
                else -> hypot(trajectory.velocityX, trajectory.velocityY).toDouble()
            }
            render(
                activeBodies = active,
                fps = displayedFps,
                trajectoryVisible = trajectory != null,
                futureProjectileSpeed = futureProjectileSpeed,
            )
            val gpuMillis = timer!!.end()
            if (!gpuMillis.isNaN()) {
                smoothedGpuMillis += 0.08 * (gpuMillis - smoothedGpuMillis)
            }

            glfwSwapBuffers(window)
            glfwPollEvents()
            frames++
            totalFrames++
            val now = System.nanoTime()
            if (now >= reportAt) {
                val fps = frames * 1e9 / (now - reportStart)
                System.err.printf(
                    Locale.ROOT,
                    "bodies=%,d  fps=%.1f  gpu=%.2f ms  grid=%dx%d%n",
                    active,
                    fps,
                    smoothedGpuMillis,
                    config.gridWidth,
                    config.gridHeight,
                )
                frames = 0
                reportStart = now
                reportAt = now + 2_000_000_000L
            }
            if (config.maxFrames != null && totalFrames >= config.maxFrames) {
                val cluster = simulation.measureCluster(active)
                System.err.printf(
                    Locale.ROOT,
                    "cluster center=(%.1f, %.1f) covariance-area=%,.0f px^2 " +
                        "equivalent-diameter=%.1f px core90=%,.0f px^2 " +
                        "core99=%,.0f px^2 max-speed=%.1f px/s%n",
                    cluster.centerX,
                    cluster.centerY,
                    cluster.covarianceAreaPixels,
                    cluster.equivalentDiameterPixels,
                    cluster.core90AreaPixels,
                    cluster.core99AreaPixels,
                    cluster.maxSpeedPixelsPerSecond,
                )
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
            if (key in GLFW_KEY_0..GLFW_KEY_9 && action == GLFW_PRESS) {
                projectileDiameterPixels =
                    CircularProjectile.diameterForDigit(key - GLFW_KEY_0)
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
            if (button != GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback
            val physicsX = cursorX.toFloat()
            val physicsY = viewportHeight - cursorY.toFloat()
            when (action) {
                GLFW_PRESS -> {
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
        glPointSize(1f)
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
                "initial=${config.initialBodies}, grid=${config.gridWidth}x${config.gridHeight}, " +
                "collisions=${config.collisionIterations}, " +
                "diameter=${config.contactDiameterPixels}px, " +
                "squareTest=${config.squareTestPixels ?: "off"}, " +
                "foreground=$foreground; LMB drag fires a " +
                "${projectileDiameterPixels.toInt()}px projectile; " +
                "digits 1-9/0 select 10-90/100px; " +
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
        if (hypot(deltaX, deltaY) < 2f) return null
        return ProjectileGesture(
            originX = dragOriginX,
            originY = dragOriginY,
            velocityX = deltaX * CircularProjectile.VELOCITY_PER_DRAG_PIXEL,
            velocityY = deltaY * CircularProjectile.VELOCITY_PER_DRAG_PIXEL,
            diameterPixels = projectileDiameterPixels,
        )
    }

    private fun initializeSimulation(simulation: GravityPipeline, activeBodies: Int) {
        simulation.initializeAcceleration(activeBodies)
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
    ) {
        glClear(GL_COLOR_BUFFER_BIT)
        renderProgram!!.use()
        renderProgram!!.float("uMaximumPressure", config.maximumVisualPressure)
        pipeline!!.bindBodiesForRender()
        glBindVertexArray(vao)
        glDrawArrays(GL_POINTS, 0, activeBodies)
        if (trajectoryVisible) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            trajectoryProgram!!.use()
            pipeline!!.bindTrajectoryForRender()
            glDrawArrays(GL_LINES, 0, GravityPipeline.TRAJECTORY_VERTICES)
            glDisable(GL_BLEND)
        }
        if (draggingProjectile) {
            val previewDashCount = (
                Math.PI * projectileDiameterPixels /
                    PROJECTILE_PREVIEW_PERIOD_PIXELS
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
        fpsOverlay!!.render(
            fps = fps,
            activeBodies = activeBodies,
            projectileBodies = projectileBodyCount,
            timeScale = simulationSpeed.scale,
            futureProjectileSpeed = futureProjectileSpeed,
        )
    }

    override fun close() {
        timer?.close()
        if (vao != 0) glDeleteVertexArrays(vao)
        fpsOverlay?.close()
        projectilePreviewProgram?.close()
        trajectoryProgram?.close()
        renderProgram?.close()
        pipeline?.close()
        if (window != 0L) {
            glfwFreeCallbacks(window)
            glfwDestroyWindow(window)
        }
        glfwTerminate()
        glfwSetErrorCallback(null)?.free()
    }

    private companion object {
        const val PROJECTILE_PREVIEW_PERIOD_PIXELS = 6f
        const val TORUS_PREVIEW_INSTANCES = 9
    }
}
