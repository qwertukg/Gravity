package dev.gravity

import org.jtransforms.fft.DoubleFFT_2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Mirrors the GPU shader's bit-reversal + ping-pong stages and checks every
 * complex bin against an independent, optimized FFT implementation.
 */
class Radix2FftLayoutTest {
    @Test
    fun `shader layout agrees with JTransforms`() {
        val size = 16
        val random = Random(73)
        val expected = DoubleArray(size * size * 2)
        repeat(size * size) { expected[it * 2] = random.nextDouble(-1.0, 1.0) }
        val actual = expected.copyOf()

        DoubleFFT_2D(size.toLong(), size.toLong()).complexForward(expected)
        fft2d(actual, size, inverse = false)

        val maximumError = expected.indices.maxOf { abs(expected[it] - actual[it]) }
        assertTrue(maximumError < 1e-9, "maximum complex-bin error was $maximumError")
    }

    private fun fft2d(values: DoubleArray, size: Int, inverse: Boolean) {
        for (axis in 0..1) {
            val permuted = DoubleArray(values.size)
            val bits = size.countTrailingZeroBits()
            repeat(size * size) { id ->
                val x = id % size
                val y = id / size
                val sx = if (axis == 0) Integer.reverse(x) ushr (32 - bits) else x
                val sy = if (axis == 1) Integer.reverse(y) ushr (32 - bits) else y
                permuted[id * 2] = values[(sx + sy * size) * 2]
                permuted[id * 2 + 1] = values[(sx + sy * size) * 2 + 1]
            }
            var source = permuted
            var destination = DoubleArray(values.size)
            repeat(bits) { stage ->
                val width = 1 shl (stage + 1)
                val half = width / 2
                repeat(size * size) { id ->
                    val x = id % size
                    val y = id / size
                    val coordinate = if (axis == 0) x else y
                    val within = coordinate and (width - 1)
                    val lower = coordinate - (within and half)
                    val upper = lower + half
                    val lowerId = if (axis == 0) lower + y * size else x + lower * size
                    val upperId = if (axis == 0) upper + y * size else x + upper * size
                    val angle = (if (inverse) 2.0 else -2.0) * PI * (within and (half - 1)) / width
                    val wr = cos(angle)
                    val wi = sin(angle)
                    val ar = source[lowerId * 2]
                    val ai = source[lowerId * 2 + 1]
                    val br = source[upperId * 2] * wr - source[upperId * 2 + 1] * wi
                    val bi = source[upperId * 2] * wi + source[upperId * 2 + 1] * wr
                    val sign = if (within < half) 1.0 else -1.0
                    destination[id * 2] = ar + sign * br
                    destination[id * 2 + 1] = ai + sign * bi
                }
                source = destination.also { destination = source }
            }
            source.copyInto(values)
        }
    }
}
