package dev.gravity

import java.nio.FloatBuffer
import java.security.SecureRandom
import kotlin.random.Random

data class UniformInitialState(
    val massPrefix: DoubleArray,
    val normalizationMass: Float,
    val seed: Long,
)

data class NormalizedRegion(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    init {
        require(minX in 0f..<maxX && maxX <= 1f)
        require(minY in 0f..<maxY && maxY <= 1f)
    }

    companion object {
        val FULL_SCREEN = NormalizedRegion(0f, 0f, 1f, 1f)
    }
}

object UniformParticleInitializer {
    private val seedSource = SecureRandom()

    fun fill(
        bodies: FloatBuffer,
        masses: FloatBuffer,
        count: Int,
        seed: Long = seedSource.nextLong(),
        region: NormalizedRegion = NormalizedRegion.FULL_SCREEN,
    ): UniformInitialState {
        require(count > 0)
        require(bodies.remaining() >= count * 4)
        require(masses.remaining() >= count)

        val random = Random(seed)
        val prefix = DoubleArray(count + 1)
        repeat(count) { id ->
            bodies.put(region.minX + random.nextFloat() * (region.maxX - region.minX))
            bodies.put(region.minY + random.nextFloat() * (region.maxY - region.minY))
            bodies.put(0f)
            bodies.put(0f)
            masses.put(1f)
            prefix[id + 1] = (id + 1).toDouble()
        }
        return UniformInitialState(
            massPrefix = prefix,
            normalizationMass = count.toFloat(),
            seed = seed,
        )
    }

    fun placePixelSquare(
        bodies: FloatBuffer,
        count: Int,
        widthPixels: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        seed: Long,
    ) {
        require(bodies.capacity() >= count * 4)
        val heightPixels = (count + widthPixels - 1) / widthPixels
        require(widthPixels <= viewportWidth && heightPixels <= viewportHeight)

        val pixelOrder = IntArray(count) { it }
        val random = Random(seed xor 0x51A7_E5EEL)
        for (index in pixelOrder.lastIndex downTo 1) {
            val other = random.nextInt(index + 1)
            val swap = pixelOrder[index]
            pixelOrder[index] = pixelOrder[other]
            pixelOrder[other] = swap
        }

        val originX = (viewportWidth - widthPixels) / 2
        val originY = (viewportHeight - heightPixels) / 2
        repeat(count) { id ->
            val pixel = pixelOrder[id]
            val x = originX + pixel % widthPixels
            val y = originY + pixel / widthPixels
            bodies.put(id * 4, (x + 0.5f) / viewportWidth)
            bodies.put(id * 4 + 1, (y + 0.5f) / viewportHeight)
            bodies.put(id * 4 + 2, 0f)
            bodies.put(id * 4 + 3, 0f)
        }
    }
}
