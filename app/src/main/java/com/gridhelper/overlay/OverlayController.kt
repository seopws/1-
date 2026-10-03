package com.gridhelper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.gridhelper.core.AppSettings
import com.gridhelper.core.OverlayModel
import java.util.Locale

/**
 * Full-screen, non-touchable, non-focusable overlay window that draws the guides.
 *
 * Coordinate mapping: the window is laid out over the whole display (cut-out and system bars
 * included). At draw time the view's actual position on screen is subtracted, so whatever offset
 * the system still applies (status bar, cut-out, insets) is compensated and capture pixels map
 * 1:1 to screen pixels.
 */
class OverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val view = GuideView(context)
    private var attached = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        title = "GridHelper guides"
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            isFitInsetsIgnoringVisibility = true
        }
        // Android 12+: touches only pass through untrusted overlays with opacity <= 0.8.
        alpha = AppSettings.MAX_OPACITY
    }

    fun attach() {
        if (attached) return
        windowManager.addView(view, params)
        attached = true
    }

    fun detach() {
        if (!attached) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            // already removed by the system
        }
        attached = false
    }

    /** Must be called on the main thread. */
    fun render(model: OverlayModel, settings: AppSettings, paused: Boolean) {
        val alpha = settings.overlayOpacity.coerceIn(AppSettings.MIN_OPACITY, AppSettings.MAX_OPACITY)
        if (attached && params.alpha != alpha) {
            params.alpha = alpha
            windowManager.updateViewLayout(view, params)
        }
        val showGuides = !paused && settings.guidesVisible && model.boardVisible
        val showDebug = !paused && settings.debugMode
        view.update(model, showGuides, showDebug)
    }

    @SuppressLint("ViewConstructor")
    private class GuideView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val renderer = GuideRenderer(density)
        private val location = IntArray(2)
        private val panel = RectF()
        private var model = OverlayModel()
        private var showGuides = false
        private var showDebug = false

        fun update(model: OverlayModel, showGuides: Boolean, showDebug: Boolean) {
            val changed = model != this.model || showGuides != this.showGuides || showDebug != this.showDebug
            this.model = model
            this.showGuides = showGuides
            this.showDebug = showDebug
            visibility = if (showGuides || showDebug) VISIBLE else INVISIBLE
            if (changed) invalidate()
        }

        private fun statusBarHeight(): Float {
            val insets = rootWindowInsets ?: return 24f * density
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top.toFloat()
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop.toFloat()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(location)
            canvas.save()
            // Draw in screen coordinates (= capture coordinates).
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            val screenWidth = if (model.frameWidth > 0) model.frameWidth.toFloat() else width.toFloat()
            val top = statusBarHeight()
            val board = model.board
            val result = model.result
            if (showGuides && board != null && result != null) {
                renderer.drawPlan(canvas, board.grid, result)
                renderer.drawGameOverBanner(canvas, screenWidth, top, result)
            }
            if (showDebug) {
                val latest = model.latest
                val boardTop = latest?.board?.outer?.top?.toFloat() ?: (model.frameHeight * 0.25f)
                val panelTop = top + 56f * density
                // Ends well above the board: the frame signature band starts 1 % of the height above it.
                panel.set(8f * density, panelTop, screenWidth - 8f * density, maxOf(panelTop + 40f * density, boardTop - model.frameHeight * 0.025f))
                val s = model.stats
                // Without a board the signature covers this panel, so only show text that does not
                // change from one analysis to the next (otherwise each redraw triggers a new analysis).
                val lines = if (latest?.board == null) {
                    listOf(s.lastStatus)
                } else {
                    listOf(
                        s.lastStatus,
                        String.format(Locale.US, "analysis %.1f ms  solve %.1f ms  frames %d", s.lastAnalysisMs, s.lastSolveMs, s.analysedFrames),
                        "plan: " + (result?.let { r -> "${r.status} score=${"%.1f".format(Locale.US, r.score)}" + (r.monteCarloValue?.let { " mc=${"%.2f".format(Locale.US, it)}" } ?: "") } ?: "-"),
                    )
                }
                renderer.drawDebug(canvas, latest, lines, panel)
            }
            canvas.restore()
        }
    }
}
