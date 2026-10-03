package com.gridhelper.vision

import android.graphics.Bitmap
import android.media.Image

/** Copies a bitmap's pixels once; reads are then plain array accesses. */
fun Bitmap.toPixelSource(): PixelSource {
    val argb = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    val pixels = IntArray(argb.width * argb.height)
    argb.getPixels(pixels, 0, argb.width, 0, 0, argb.width, argb.height)
    return IntArrayPixelSource(argb.width, argb.height, pixels)
}

/** Zero-copy view of an RGBA_8888 [Image] (valid until the image is closed). */
fun Image.toPixelSource(): PixelSource {
    val plane = planes[0]
    return ByteBufferPixelSource(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
}

/** Copies an RGBA_8888 [Image] into an ARGB_8888 bitmap (row padding removed). */
fun Image.toBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val paddedWidth = plane.rowStride / plane.pixelStride
    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    buffer.rewind()
    padded.copyPixelsFromBuffer(buffer)
    if (paddedWidth == width) return padded
    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
    padded.recycle()
    return cropped
}
