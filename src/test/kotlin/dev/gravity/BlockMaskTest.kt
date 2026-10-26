package dev.gravity

import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockMaskTest {
    private val diameter = 1.12838f

    /** A picture of [width] x [height] pixels, white where [black] is false. */
    private fun picture(width: Int, height: Int, alpha: Boolean = false, black: (Int, Int) -> Boolean) =
        BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB).also {
            for (y in 0 until height) for (x in 0 until width) {
                it.setRGB(x, y, if (black(x, y)) 0xff000000.toInt() else if (alpha) 0 else 0xffffffff.toInt())
            }
        }

    @Test
    fun `a black picture fills the rectangle`() {
        val mask = BlockMask.of("full", picture(8, 4) { _, _ -> true })

        assertEquals(RectangularBlock.offsets(diameter, 80f, 40f), mask.offsets(diameter, 80f, 40f))
    }

    @Test
    fun `the top rows of a picture are the top of the block`() {
        val mask = BlockMask.of("top", picture(4, 4) { _, y -> y < 2 })
        val offsets = mask.offsets(diameter, 40f, 40f)

        assertTrue(offsets.isNotEmpty())
        assertTrue(offsets.all { it.y > 0f })
    }

    @Test
    fun `dark opaque pixels are solid, light or transparent ones are not`() {
        val image = BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xff202020.toInt())
        image.setRGB(1, 0, 0xffc0c0c0.toInt())
        image.setRGB(2, 0, 0x00000000)
        image.setRGB(3, 0, 0xff000000.toInt())
        val mask = BlockMask.of("grey", image)
        val columns = mask.offsets(diameter, 40f, 10f).map { ((it.x / 40f + 0.5f) * 4).toInt() }.toSet()

        assertEquals(setOf(0, 3), columns)
    }

    @Test
    fun `body count matches offsets without allocating`() {
        val mask = BlockMask.of("ring", picture(30, 30, alpha = true) { x, y ->
            val r = (x - 14.5) * (x - 14.5) + (y - 14.5) * (y - 14.5)
            r < 15.0 * 15.0 && r > 7.0 * 7.0
        })
        for (size in listOf(0f, 3f, 17.5f, 60f, 200f)) {
            assertEquals(mask.offsets(diameter, size, size).size, mask.bodyCount(diameter, size, size), "size=$size")
        }
    }

    @Test
    fun `separate patches fall into separate pieces, a hole leaves one`() {
        val apart = BlockMask.of("apart", picture(9, 3) { x, _ -> x < 3 || x >= 6 })
        assertEquals(2, BlockPieces.of(apart.offsets(diameter, 90f, 30f), diameter).size)

        val ring = BlockMask.of("ring", picture(9, 9) { x, y -> x !in 3..5 || y !in 3..5 })
        assertEquals(1, BlockPieces.of(ring.offsets(diameter, 90f, 90f), diameter).size)
    }

    @Test
    fun `the fit keeps the picture's aspect`() {
        val mask = BlockMask.of("wide", picture(40, 10) { _, _ -> true })

        assertEquals(PixelOffset(200f, 50f), mask.fit(200f, 300f))
        assertEquals(PixelOffset(120f, 30f), mask.fit(500f, 30f))
    }

    @Test
    fun `the pictures of the masks directory load`() {
        val files = BlockMask.files(File("masks"))

        assertTrue(files.isNotEmpty())
        files.forEach { assertTrue(BlockMask.load(it).bodyCount(diameter, 200f, 200f) > 0, it.name) }
    }
}
