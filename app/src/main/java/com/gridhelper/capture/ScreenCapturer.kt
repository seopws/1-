package com.gridhelper.capture

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * VirtualDisplay + ImageReader (RGBA_8888) at the real display resolution. Frames are delivered
 * on a dedicated background thread; [onFrame] must not keep the [Image] after returning.
 *
 * Android 14+ allows a single createVirtualDisplay() per MediaProjection, so pausing detaches
 * the surface instead of releasing the display.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    val width: Int,
    val height: Int,
    private val densityDpi: Int,
    private val onFrame: (Image) -> Unit,
) {
    private val thread = HandlerThread("GridHelper-capture").apply { start() }
    val handler = Handler(thread.looper)
    private val reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private var display: VirtualDisplay? = null

    @Volatile
    var paused: Boolean = false
        private set

    fun start() {
        reader.setOnImageAvailableListener({ r -> drain(r) }, handler)
        display = projection.createVirtualDisplay(
            "GridHelper",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
    }

    /** Stops frame production (screen off, rotation, user pause): saves GPU and battery. */
    fun pause() {
        if (paused) return
        paused = true
        display?.surface = null
    }

    fun resume() {
        if (!paused) return
        paused = false
        display?.surface = reader.surface
    }

    fun release() {
        paused = true
        try {
            display?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "release display", e)
        }
        display = null
        reader.setOnImageAvailableListener(null, null)
        handler.post {
            reader.close()
            thread.quitSafely()
        }
    }

    private fun drain(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "acquireLatestImage", e)
            null
        } ?: return
        try {
            if (!paused) onFrame(image)
        } catch (e: RuntimeException) {
            Log.e(TAG, "frame processing failed", e)
        } finally {
            image.close()
        }
    }

    private companion object {
        const val TAG = "ScreenCapturer"
    }
}
