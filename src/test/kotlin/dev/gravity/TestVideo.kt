package dev.gravity

import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glPixelStorei
import org.lwjgl.opengl.GL11.glReadPixels
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL12.GL_BGR
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL30.GL_RENDERBUFFER
import org.lwjgl.opengl.GL30.GL_RGBA8
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glBindRenderbuffer
import org.lwjgl.opengl.GL30.glCheckFramebufferStatus
import org.lwjgl.opengl.GL30.glDeleteFramebuffers
import org.lwjgl.opengl.GL30.glDeleteRenderbuffers
import org.lwjgl.opengl.GL30.glFramebufferRenderbuffer
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.lwjgl.opengl.GL30.glGenRenderbuffers
import org.lwjgl.opengl.GL30.glRenderbufferStorage

/**
 * Draws bodies with the application's renderer into an offscreen target and
 * keeps each frame as a JPEG; [save] writes them as a Motion JPEG AVI, which
 * needs no external encoder and plays in VLC and the Windows players.
 */
internal class TestVideo(
    private val width: Int,
    private val height: Int,
    config: GravityConfig,
    private val quality: Float = 0.9f,
) : AutoCloseable {
    private val renderer = ParticleRenderer(width, height, config.presentation, config.collisions.contactDiameterPixels)
    private val framebuffer = glGenFramebuffers()
    private val color = glGenRenderbuffers()
    private val pixels = BufferUtils.createByteBuffer(width * height * 3)
    private val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
    private val frames = ArrayList<ByteArray>()

    init {
        glBindRenderbuffer(GL_RENDERBUFFER, color)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height)
        glBindRenderbuffer(GL_RENDERBUFFER, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Video framebuffer is incomplete" }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    /** Records a frame; [draw], if given, draws it into the framebuffer it gets instead of the ParticleRenderer. */
    fun capture(pipeline: GravityPipeline, activeBodies: Int, draw: ((Int) -> Unit)? = null) {
        glViewport(0, 0, width, height)
        if (draw == null) renderer.render(pipeline, activeBodies, framebuffer) else draw(framebuffer)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, width, height, GL_BGR, GL_UNSIGNED_BYTE, pixels)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        // OpenGL rows run bottom-up, image rows top-down.
        val target = (image.raster.dataBuffer as DataBufferByte).data
        val row = width * 3
        for (y in 0 until height) pixels.get((height - 1 - y) * row, target, y * row, row)
        frames += jpeg()
    }

    private fun jpeg(): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val output = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream
            val parameters = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            writer.write(null, IIOImage(image, null, null), parameters)
            writer.dispose()
        }
        return output.toByteArray()
    }

    /** Writes the frame recorded last as a PNG. */
    fun still(file: File) {
        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
        println("Still: ${file.absolutePath}")
    }

    fun save(file: File, framesPerSecond: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(avi(framesPerSecond))
        println("Video: ${file.absolutePath} (${frames.size} frames, ${file.length() / 1024} KiB)")
    }

    private fun avi(framesPerSecond: Int): ByteArray {
        val largest = frames.maxOfOrNull { it.size } ?: 0
        val padded = frames.sumOf { 8 + it.size + (it.size and 1) }
        val hdrl = 4 + (8 + 56) + 8 + (4 + (8 + 56) + (8 + 40))
        val movi = 4 + padded
        val idx1 = 16 * frames.size
        val total = 4 + (8 + hdrl) + (8 + movi) + (8 + idx1)
        val out = ByteBuffer.allocate(8 + total).order(ByteOrder.LITTLE_ENDIAN)
        fun fourcc(code: String) { out.put(code.toByteArray(Charsets.US_ASCII)) }
        fun int(value: Int) { out.putInt(value) }
        fun short(value: Int) { out.putShort(value.toShort()) }

        fourcc("RIFF"); int(total); fourcc("AVI ")
        fourcc("LIST"); int(hdrl); fourcc("hdrl")
        fourcc("avih"); int(56)
        int(1_000_000 / framesPerSecond) // microseconds per frame
        int(largest * framesPerSecond) // maximum bytes per second
        int(0) // padding granularity
        int(0x10) // AVIF_HASINDEX
        int(frames.size)
        int(0) // initial frames
        int(1) // streams
        int(largest) // suggested buffer size
        int(width); int(height)
        repeat(4) { int(0) }
        fourcc("LIST"); int(4 + (8 + 56) + (8 + 40)); fourcc("strl")
        fourcc("strh"); int(56)
        fourcc("vids"); fourcc("MJPG")
        int(0) // flags
        short(0); short(0) // priority, language
        int(0) // initial frames
        int(1); int(framesPerSecond) // scale, rate
        int(0); int(frames.size) // start, length
        int(largest) // suggested buffer size
        int(-1) // quality
        int(0) // sample size
        short(0); short(0); short(width); short(height)
        fourcc("strf"); int(40)
        int(40); int(width); int(height)
        short(1); short(24)
        fourcc("MJPG")
        int(width * height * 3)
        repeat(4) { int(0) }

        fourcc("LIST"); int(movi)
        val moviStart = out.position()
        fourcc("movi")
        val offsets = IntArray(frames.size)
        for ((index, frame) in frames.withIndex()) {
            offsets[index] = out.position() - moviStart
            fourcc("00dc"); int(frame.size); out.put(frame)
            if (frame.size and 1 != 0) out.put(0)
        }
        fourcc("idx1"); int(idx1)
        for ((index, frame) in frames.withIndex()) {
            fourcc("00dc"); int(0x10); int(offsets[index]); int(frame.size) // AVIIF_KEYFRAME
        }
        check(!out.hasRemaining()) { "AVI size mismatch" }
        return out.array()
    }

    override fun close() {
        glDeleteFramebuffers(framebuffer)
        glDeleteRenderbuffers(color)
        renderer.close()
    }
}
