package dev.gravity

import org.lwjgl.opengl.GL11.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL30.GL_R32UI
import org.lwjgl.opengl.GL30.GL_RED_INTEGER
import org.lwjgl.opengl.GL42.glMemoryBarrier
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT
import org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43.glClearBufferData
import org.lwjgl.opengl.GL43.glDispatchCompute
import java.nio.ByteBuffer

/**
 * Sleeping tiles (sleep_layout.glsl). The screen is cut into tiles of
 * [TILE_PIXELS]; a step's final collision pass marks the tiles of the bodies
 * that moved at least [restingSpeedPixels] × dt in it, or that nothing holds,
 * and [advance] wakes those tiles and their neighbours, and every block with
 * a member in one, for the next step. The collision passes, the kick and the
 * rigid projection leave sleeping bodies and blocks alone. Everything is
 * awake for [WAKE_STEPS] steps after [wake]: at the start and after any
 * change of scene, when no pass has marked the tiles yet.
 */
internal class SleepTiles(
    width: Int,
    height: Int,
    capacity: Int,
    timeStep: Float,
    val enabled: Boolean,
    restingSpeedPixels: Float,
    private val bind: (Int, Int) -> Unit,
) : AutoCloseable {
    val tilesX = (width + TILE_PIXELS - 1) / TILE_PIXELS
    val tilesY = (height + TILE_PIXELS - 1) / TILE_PIXELS
    private val tiles = tilesX * tilesY
    private val tileAwake = buffer(tiles.toLong() * Int.SIZE_BYTES)
    private val tileActive = buffer(tiles.toLong() * Int.SIZE_BYTES)
    private val groupAwake = buffer(RigidBlocks.MAXIMUM_GROUPS.toLong() * Int.SIZE_BYTES)
    private val bodyAwake = buffer(capacity.toLong() * Int.SIZE_BYTES)
    private val tilesProgram = GlProgram.compute("/shaders/sleep_tiles.comp")
    private val displacementSquared = (restingSpeedPixels * timeStep).let { it * it }
    /** Stamp of the current step; 0 is never a stamp, so cleared buffers sleep. */
    private var stamp = 1
    private var wakeSteps = WAKE_STEPS

    init {
        intArrayOf(tileAwake, tileActive, groupAwake, bodyAwake).forEach {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
            glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, null as ByteBuffer?)
        }
    }

    /** Whether any body sleeps this step. */
    val sleeping get() = enabled && wakeSteps == 0

    /** Keeps every body awake for the next [WAKE_STEPS] steps. */
    fun wake() {
        wakeSteps = WAKE_STEPS
    }

    fun bindBuffers() {
        bind(14, bodyAwake)
        bind(15, groupAwake)
        bind(16, tileActive)
        bind(17, tileAwake)
    }

    /** The sleep uniforms of [program], those it uses. */
    fun uniforms(program: GlProgram) {
        program.use()
        if (program.has("uSleep")) program.int("uSleep", if (sleeping) 1 else 0)
        if (program.has("uSleepStamp")) program.uint("uSleepStamp", stamp)
        if (program.has("uSleepTiles")) program.ivec2("uSleepTiles", tilesX, tilesY)
        if (program.has("uSleepDisplacementSquared")) program.float("uSleepDisplacementSquared", displacementSquared)
        if (program.has("uSleepTileMarkSquared")) program.float("uSleepTileMarkSquared", (0.5f * TILE_PIXELS).let { it * it })
    }

    /** After the final collision pass of a step: wakes the tiles of the next step. */
    fun advance(bounded: Boolean) {
        if (!enabled) return
        uniforms(tilesProgram)
        tilesProgram.int("uBounded", if (bounded) 1 else 0)
        bindBuffers()
        glDispatchCompute((tiles + 255) / 256, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
    }

    /** After [advance] and the blocks' wake pass: the next step begins. */
    fun endStep() {
        if (!enabled) return
        stamp++
        if (stamp == 0) stamp = 1
        if (wakeSteps > 0) wakeSteps--
    }

    override fun close() {
        tilesProgram.close()
        intArrayOf(tileAwake, tileActive, groupAwake, bodyAwake).forEach(::glDeleteBuffers)
    }

    companion object {
        const val TILE_PIXELS = 32
        const val WAKE_STEPS = 2

        private fun buffer(bytes: Long): Int = glGenBuffers().also {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, it)
            glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW)
        }
    }
}
