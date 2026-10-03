package com.gridhelper.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/** Physical display size in pixels (portrait orientation) and density. */
data class DisplayInfo(val width: Int, val height: Int, val densityDpi: Int) {
    companion object {
        /**
         * Context suitable for adding overlay windows from a Service. On Android 11+ a window
         * context is required for correct display metrics and to avoid StrictMode violations.
         */
        fun overlayContext(context: Context): Context {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return context
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            return context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        }

        fun of(windowContext: Context): DisplayInfo {
            val wm = windowContext.getSystemService(WindowManager::class.java)
            val dpi = windowContext.resources.displayMetrics.densityDpi
            val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.maximumWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                dm.widthPixels to dm.heightPixels
            }
            // The board is analysed in portrait; capture with the portrait geometry.
            return DisplayInfo(min(w, h), max(w, h), dpi)
        }
    }
}
