package com.gridhelper.debug

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.gridhelper.solver.Bitboard
import com.gridhelper.vision.FrameAnalysis
import com.gridhelper.vision.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug mode: stores frames whose recognition failed as PNG (+ a text report) in
 * `Android/data/com.gridhelper/files/failed_frames/` (no storage permission needed; pull with
 * `adb pull` or a file manager). Rate limited and capped so it cannot fill the disk.
 */
class DebugFrameSaver(context: Context, private val scope: CoroutineScope) {

    val directory: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "failed_frames")
    private var lastSavedAt = 0L

    /** Called on the capture thread while [image] is still open. */
    fun maybeSave(image: Image, analysis: FrameAnalysis) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSavedAt < MIN_INTERVAL_MS) return
        lastSavedAt = now
        val bitmap = try {
            image.toBitmap()
        } catch (e: RuntimeException) {
            Log.w(TAG, "copy failed", e)
            return
        }
        val report = describe(analysis)
        scope.launch(Dispatchers.IO) { write(bitmap, report) }
    }

    private fun write(bitmap: Bitmap, report: String) {
        try {
            directory.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            FileOutputStream(File(directory, "frame_$stamp.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(directory, "frame_$stamp.txt").writeText(report)
            prune()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "save failed", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun prune() {
        val pngs = directory.listFiles { f -> f.extension == "png" }?.sortedBy { it.name } ?: return
        if (pngs.size <= MAX_FILES) return
        for (f in pngs.take(pngs.size - MAX_FILES)) {
            f.delete()
            File(f.parentFile, f.nameWithoutExtension + ".txt").delete()
        }
    }

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "DebugFrameSaver"
        private const val MIN_INTERVAL_MS = 5_000L
        private const val MAX_FILES = 50

        fun describe(a: FrameAnalysis): String = buildString {
            appendLine("frame ${a.width}x${a.height}")
            appendLine("status ${a.status} failure=${a.failure}")
            appendLine("candidate ${a.candidate}")
            a.board?.let { b ->
                appendLine("outer ${b.outer}")
                appendLine("grid ${b.grid} score=${b.gridScore}")
                appendLine("emptyCentroid ${b.emptyCentroid} filledCentroid ${b.filledCentroid}")
                appendLine("board:")
                appendLine(Bitboard.render(b.bitboard))
                appendLine("ambiguous:")
                appendLine(Bitboard.render(b.ambiguous, filled = '?'))
            }
            a.tray?.let { t ->
                appendLine("tray cell=${t.cellSize} area=${t.area}")
                t.slots.forEach { appendLine(" slot ${it.index}: ${it.shape?.key ?: "-"} bounds=${it.bounds} uncertain=${it.uncertain}") }
            }
            appendLine("analysis ${"%.1f".format(Locale.US, a.elapsedNanos / 1e6)} ms")
        }
    }
}
