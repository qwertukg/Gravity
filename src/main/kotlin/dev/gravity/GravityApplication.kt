package dev.gravity

import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_FOCUSED
import org.lwjgl.glfw.GLFW.GLFW_FOCUS_ON_SHOW
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_R
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
import org.lwjgl.opengl.GL11.GL_FALSE
import org.lwjgl.opengl.GL11.GL_POINTS
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL11.glPointSize
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL13.GL_MULTISAMPLE
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.opengl.GL43
import java.util.Locale
import kotlin.math.hypot

private data class ProjectileGesture(
    val originX: Float,
    val originY: Float,
    val velocityX: Float,
    val velocityY: Float,
)

class GravityApplication(private val config: SimulationConfig) : AutoCloseable {
    private var window = 0L
    private var pipeline: GravityPipeline? = null
    private var renderProgram: GlProgram? = null
    private var fpsOverlay: FpsOverlay? = null
    private var vao = 0
    private var timer: GpuTimer? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var resetRequested = false
    private var cursorX = 0.0
    private var cursorY = 0.0
    private var dragOriginX = 0f
    private var dragOriginY = 0f
    private var draggingProjectile = false
    private var pendingProjectile: ProjectileGesture? = null

    fun run() {
        initWindow()
        var simulation = GravityPipeline(
            config,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        pipeline = simulation
        renderProgram = GlProgram.graphics("/shaders/particles.vert", "/shaders/particles.frag")
        vao = glGenVertexArrays()
        fpsOverlay = FpsOverlay(viewportWidth, viewportHeight)
        timer = GpuTimer()

        var active = config.initialBodies
        simulation.initializeAcceleration(active)
        activateFullscreenWindow()
        render(active, 0.0)
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
                timer = GpuTimer()
                simulation.initializeAcceleration(active)
                pendingProjectile = null
                draggingProjectile = false
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
                )
                if (spawned > 0) {
                    active += spawned
                    System.err.printf(
                        Locale.ROOT,
                        "projectile=%,d bodies  total=%,d  velocity=(%.1f, %.1f) px/s%n",
                        spawned,
                        active,
                        projectile.velocityX,
                        projectile.velocityY,
                    )
                } else {
                    System.err.println("Projectile skipped: particle capacity is full")
                }
                pendingProjectile = null
            }
            timer!!.begin()
            simulation.step(active)
            overlayFrames++
            val overlayNow = System.nanoTime()
            if (overlayNow - overlayWindowStart >= 250_000_000L) {
                displayedFps = overlayFrames * 1e9 / (overlayNow - overlayWindowStart)
                overlayFrames = 0
                overlayWindowStart = overlayNow
            }
            render(active, displayedFps)
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
            if (key == GLFW_KEY_R && action == GLFW_PRESS) resetRequested = true
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
                }
                org.lwjgl.glfw.GLFW.GLFW_RELEASE -> {
                    if (draggingProjectile) {
                        val deltaX = physicsX - dragOriginX
                        val deltaY = physicsY - dragOriginY
                        if (hypot(deltaX, deltaY) >= 2f) {
                            pendingProjectile = ProjectileGesture(
                                originX = dragOriginX,
                                originY = dragOriginY,
                                velocityX = deltaX *
                                    CircularProjectile.VELOCITY_PER_DRAG_PIXEL,
                                velocityY = deltaY *
                                    CircularProjectile.VELOCITY_PER_DRAG_PIXEL,
                            )
                        }
                    }
                    draggingProjectile = false
                }
            }
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
                "foreground=$foreground; LMB drag fires a 100px projectile; Esc exits"
        )
    }

    private fun activateFullscreenWindow(): Boolean {
        glfwFocusWindow(window)
        val foreground = WindowsForegroundActivator.activate(window)
        glfwPollEvents()
        return foreground && glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0
    }

    private fun render(activeBodies: Int, fps: Double) {
        glClear(GL_COLOR_BUFFER_BIT)
        renderProgram!!.use()
        renderProgram!!.float("uMaximumPressure", config.maximumVisualPressure)
        pipeline!!.bindBodiesForRender()
        glBindVertexArray(vao)
        glDrawArrays(GL_POINTS, 0, activeBodies)
        fpsOverlay!!.render(fps)
    }

    override fun close() {
        timer?.close()
        if (vao != 0) glDeleteVertexArrays(vao)
        fpsOverlay?.close()
        renderProgram?.close()
        pipeline?.close()
        if (window != 0L) {
            glfwFreeCallbacks(window)
            glfwDestroyWindow(window)
        }
        glfwTerminate()
        glfwSetErrorCallback(null)?.free()
    }
}
