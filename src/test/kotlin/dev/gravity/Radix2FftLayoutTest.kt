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
    fun `shared-memory shader layout agrees with JTransforms forward and inverse`() {
        for ((width, height) in listOf(
            16 to 16,
            32 to 16,
            16 to 64,
            128 to 128,
            512 to 128,
            128 to 512,
            2048 to 128,
        )) {
            val random = Random(73 + width * 31 + height)
            val input = DoubleArray(width * height * 2) {
                random.nextDouble(-1.0, 1.0)
            }
            for (inverse in listOf(false, true)) {
                val expected = input.copyOf()
                val actual = input.copyOf()
                val reference = DoubleFFT_2D(height.toLong(), width.toLong())
                if (inverse) {
                    reference.complexInverse(expected, false)
                } else {
                    reference.complexForward(expected)
                }
                fft2d(actual, width, height, inverse)
                assertError(expected, actual, width, height, inverse)
            }
        }
    }

    @Test
    fun `periodic convolution agrees with direct rectangular convolution`() {
        val width = 16
        val height = 8
        val random = Random(991)
        val left = DoubleArray(width * height * 2)
        val right = DoubleArray(width * height * 2)
        repeat(width * height) {
            left[it * 2] = random.nextDouble(-1.0, 1.0)
            right[it * 2] = random.nextDouble(-1.0, 1.0)
        }
        val expected = directCircularConvolution(left, right, width, height)
        fft2d(left, width, height, inverse = false)
        fft2d(right, width, height, inverse = false)
        repeat(width * height) { index ->
            val ar = left[index * 2]
            val ai = left[index * 2 + 1]
            val br = right[index * 2]
            val bi = right[index * 2 + 1]
            left[index * 2] = ar * br - ai * bi
            left[index * 2 + 1] = ar * bi + ai * br
        }
        fft2d(left, width, height, inverse = true)
        val scale = 1.0 / (width * height)
        left.indices.forEach { left[it] *= scale }
        assertError(expected, left, width, height, inverse = true)
    }

    private fun fft2d(values: DoubleArray, width: Int, height: Int, inverse: Boolean) {
        val twiddles = twiddles(width, height)
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
                    val twiddleBase = if (axis == 0) 0 else width - 1
                    val twiddleIndex = twiddleBase + half - 1 +
                        (within and (half - 1))
                    val component = twiddleIndex * 4 + if (inverse) 2 else 0
                    val wr = twiddles[component]
                    val wi = twiddles[component + 1]
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

    private fun twiddles(width: Int, height: Int): DoubleArray {
        val result = DoubleArray((width + height - 2) * 4)
        var offset = 0
        for (axisSize in intArrayOf(width, height)) {
            var halfWidth = 1
            while (halfWidth < axisSize) {
                repeat(halfWidth) { index ->
                    val angle = 2.0 * PI * index / (halfWidth * 2)
                    val destination = (offset + index) * 4
                    result[destination] = cos(-angle)
                    result[destination + 1] = sin(-angle)
                    result[destination + 2] = cos(angle)
                    result[destination + 3] = sin(angle)
                }
                offset += halfWidth
                halfWidth *= 2
            }
        }
        return result
    }

    private fun directCircularConvolution(
        left: DoubleArray,
        right: DoubleArray,
        width: Int,
        height: Int,
    ): DoubleArray {
        val result = DoubleArray(left.size)
        repeat(height) { y ->
            repeat(width) { x ->
                var sum = 0.0
                repeat(height) { sourceY ->
                    repeat(width) { sourceX ->
                        val kernelX = Math.floorMod(x - sourceX, width)
                        val kernelY = Math.floorMod(y - sourceY, height)
                        sum += left[(sourceX + sourceY * width) * 2] *
                            right[(kernelX + kernelY * width) * 2]
                    }
                }
                result[(x + y * width) * 2] = sum
            }
        }
        return result
    }

    private fun assertError(
        expected: DoubleArray,
        actual: DoubleArray,
        width: Int,
        height: Int,
        inverse: Boolean,
    ) {
        val maximumAbsolute = expected.indices.maxOf { abs(expected[it] - actual[it]) }
        // L-infinity relative error remains meaningful when an individual
        // expected component is exactly (or numerically very close to) zero.
        val referenceMagnitude = expected.maxOf(::abs).coerceAtLeast(1e-12)
        val maximumRelative = maximumAbsolute / referenceMagnitude
        assertTrue(
            maximumAbsolute < 1e-9,
            "$width x $height inverse=$inverse absolute error was $maximumAbsolute",
        )
        assertTrue(
            maximumRelative < 1e-10,
            "$width x $height inverse=$inverse relative error was $maximumRelative",
        )
    }
}
