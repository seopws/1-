package com.gridhelper.vision

import java.nio.ByteBuffer

/**
 * Read-only access to a screen image. Coordinates are in pixels of the captured frame, which
 * equals the physical display resolution (the overlay maps them 1:1 back to the screen).
 */
interface PixelSource {
    val width: Int
    val height: Int

    /** Colour at (x, y) as 0xRRGGBB. Callers keep x/y in range. */
    fun rgb(x: Int, y: Int): Int
}

/** ARGB_8888 int array (e.g. `Bitmap.getPixels` or `BufferedImage.getRGB`). */
class IntArrayPixelSource(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
    private val stride: Int = width,
) : PixelSource {
    init {
        require(pixels.size >= (height - 1) * stride + width) { "pixel array too small" }
    }

    override fun rgb(x: Int, y: Int): Int = pixels[y * stride + x] and 0xFFFFFF
}

/**
 * RGBA_8888 byte buffer as delivered by `ImageReader` (byte order R, G, B, A).
 * [rowStride] may be larger than `width * pixelStride` because of padding.
 */
class ByteBufferPixelSource(
    private val buffer: ByteBuffer,
    override val width: Int,
    override val height: Int,
    private val rowStride: Int,
    private val pixelStride: Int = 4,
) : PixelSource {
    override fun rgb(x: Int, y: Int): Int {
        val i = y * rowStride + x * pixelStride
        val r = buffer.get(i).toInt() and 0xFF
        val g = buffer.get(i + 1).toInt() and 0xFF
        val b = buffer.get(i + 2).toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }
}

/** Integer rectangle, right/bottom exclusive. */
data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun intersects(o: RectI): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** Float rectangle, right/bottom exclusive. */
data class RectF2(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
