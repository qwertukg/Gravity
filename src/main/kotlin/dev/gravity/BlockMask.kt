package dev.gravity

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.min

/**
 * The outline of a block drawn as a picture: its black pixels are solid. The
 * picture is stretched over the block's rectangle, which keeps its aspect
 * ([fit]); the bodies of a [RectangularBlock] whose centres fall on black
 * pixels make the block. Black is dark (luminance below half) and opaque
 * (alpha at least half), so a black drawing on white or on transparency works.
 */
class BlockMask(
    val name: String,
    val width: Int,
    val height: Int,
    /** Row-major, top row first, as in the picture. */
    private val solid: BooleanArray,
) {
    init {
        require(width > 0 && height > 0 && solid.size == width * height)
    }

    /** The largest size of the picture's aspect within [maxWidth] x [maxHeight], px. */
    fun fit(maxWidth: Float, maxHeight: Float): PixelOffset {
        val scale = min(maxWidth / width, maxHeight / height).coerceAtLeast(0f)
        return PixelOffset(width * scale, height * scale)
    }

    /** The bodies of the picture spread over [widthPixels] x [heightPixels], about the rectangle centre. */
    fun offsets(contactDiameterPixels: Float, widthPixels: Float, heightPixels: Float): List<PixelOffset> {
        val offsets = ArrayList<PixelOffset>()
        RectangularBlock.forEachOffset(contactDiameterPixels, widthPixels, heightPixels) { x, y ->
            if (covers(x, y, widthPixels, heightPixels)) offsets += PixelOffset(x, y)
        }
        return offsets
    }

    /** Same as `offsets(...).size`, without allocating; cheap enough per frame. */
    fun bodyCount(contactDiameterPixels: Float, widthPixels: Float, heightPixels: Float): Int {
        var count = 0
        RectangularBlock.forEachOffset(contactDiameterPixels, widthPixels, heightPixels) { x, y ->
            if (covers(x, y, widthPixels, heightPixels)) count++
        }
        return count
    }

    /** Whether the pixel under offset (x, y) of a rectangle of that size is black; y points up. */
    private fun covers(x: Float, y: Float, widthPixels: Float, heightPixels: Float): Boolean {
        val column = floor((x / widthPixels + 0.5f) * width).toInt().coerceIn(0, width - 1)
        val row = floor((0.5f - y / heightPixels) * height).toInt().coerceIn(0, height - 1)
        return solid[column + row * width]
    }

    companion object {
        /** Where the pictures are looked for, relative to the working directory. */
        val DIRECTORY = File("masks")

        /** The pictures of [directory] that Java can read, by name. */
        fun files(directory: File = DIRECTORY): List<File> =
            directory.listFiles { file -> file.isFile && file.extension.lowercase() in EXTENSIONS }
                .orEmpty().sortedBy { it.name.lowercase() }

        fun load(file: File): BlockMask {
            val image = ImageIO.read(file) ?: error("${file.name} is not a picture Java can read")
            return of(file.nameWithoutExtension, image)
        }

        fun of(name: String, image: BufferedImage): BlockMask {
            val width = image.width
            val height = image.height
            val argb = image.getRGB(0, 0, width, height, null, 0, width)
            val solid = BooleanArray(argb.size) { index ->
                val pixel = argb[index]
                val alpha = pixel ushr 24
                val red = pixel shr 16 and 0xff
                val green = pixel shr 8 and 0xff
                val blue = pixel and 0xff
                alpha >= 128 && 299 * red + 587 * green + 114 * blue < 128 * 1000
            }
            return BlockMask(name, width, height, solid)
        }

        private val EXTENSIONS = setOf("png", "bmp", "gif", "jpg", "jpeg")
    }
}
