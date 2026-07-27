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
        val width = 32
        val height = 16
        val random = Random(73)
        val expected = DoubleArray(width * height * 2)
        repeat(width * height) { expected[it * 2] = random.nextDouble(-1.0, 1.0) }
        val actual = expected.copyOf()

        DoubleFFT_2D(height.toLong(), width.toLong()).complexForward(expected)
        fft2d(actual, width, height, inverse = false)

        val maximumError = expected.indices.maxOf { abs(expected[it] - actual[it]) }
        assertTrue(maximumError < 1e-9, "maximum complex-bin error was $maximumError")
    }

    private fun fft2d(values: DoubleArray, width: Int, height: Int, inverse: Boolean) {
        for (axis in 0..1) {
            val permuted = DoubleArray(values.size)
            val axisSize = if (axis == 0) width else height
            val bits = axisSize.countTrailingZeroBits()
            repeat(width * height) { id ->
                val x = id % width
                val y = id / width
                val sx = if (axis == 0) Integer.reverse(x) ushr (32 - bits) else x
                val sy = if (axis == 1) Integer.reverse(y) ushr (32 - bits) else y
                permuted[id * 2] = values[(sx + sy * width) * 2]
                permuted[id * 2 + 1] = values[(sx + sy * width) * 2 + 1]
            }
            var source = permuted
            var destination = DoubleArray(values.size)
            repeat(bits) { stage ->
                val stageWidth = 1 shl (stage + 1)
                val half = stageWidth / 2
                repeat(width * height) { id ->
                    val x = id % width
                    val y = id / width
                    val coordinate = if (axis == 0) x else y
                    val within = coordinate and (stageWidth - 1)
                    val lower = coordinate - (within and half)
                    val upper = lower + half
                    val lowerId = if (axis == 0) lower + y * width else x + lower * width
                    val upperId = if (axis == 0) upper + y * width else x + upper * width
                    val angle = (if (inverse) 2.0 else -2.0) * PI *
                        (within and (half - 1)) / stageWidth
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
