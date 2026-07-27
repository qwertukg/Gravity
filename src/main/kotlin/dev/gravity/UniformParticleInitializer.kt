package dev.gravity

import java.nio.FloatBuffer
import java.security.SecureRandom
import kotlin.random.Random

data class UniformInitialState(
    val massPrefix: DoubleArray,
    val normalizationMass: Float,
    val seed: Long,
)

object UniformParticleInitializer {
    private val seedSource = SecureRandom()

    fun fill(
        bodies: FloatBuffer,
        masses: FloatBuffer,
        count: Int,
        seed: Long = seedSource.nextLong(),
    ): UniformInitialState {
        require(count > 0)
        require(bodies.remaining() >= count * 4)
        require(masses.remaining() >= count)

        val random = Random(seed)
        val prefix = DoubleArray(count + 1)
        repeat(count) { id ->
            bodies.put(random.nextFloat())
            bodies.put(random.nextFloat())
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
}
