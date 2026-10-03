package com.gridhelper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draggable floating button. Tap = guides on/off, long press = pause/resume.
 * After a drag it moves itself out of the board / tray area so it never covers what is being
 * recognised. Position is remembered.
 */
class BubbleController(
    private val context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    /** Screen rectangles the bubble must not rest on (board, tray). */
    private val avoid: () -> List<Rect>,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val size = (52 * density).roundToInt()
    private val margin = (8 * density).roundToInt()
    private val prefs = context.getSharedPreferences("bubble", Context.MODE_PRIVATE)
    private val view = BubbleView(context)
    private var attached = false

    private val params = WindowManager.LayoutParams(
        size,
        size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt(KEY_X, screenWidth - size - margin).coerceIn(0, screenWidth - size)
        y = prefs.getInt(KEY_Y, (screenHeight * 0.12f).roundToInt()).coerceIn(0, screenHeight - size)
        title = "GridHelper bubble"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    init {
        view.setOnTouchListener(TouchHandler())
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
            // already gone
        }
        attached = false
    }

    fun setState(guidesOn: Boolean, paused: Boolean) {
        view.setState(guidesOn, paused)
    }

    /** Moves the bubble out of the recognition area (call when the board position is known). */
    fun avoidRecognitionArea() {
        if (!attached) return
        val r = Rect(params.x, params.y, params.x + size, params.y + size)
        val blockers = avoid()
        val hit = blockers.firstOrNull { Rect.intersects(it, r) } ?: return
        val union = Rect(hit)
        blockers.forEach { union.union(it) }
        val above = union.top - size - margin
        params.y = if (above >= 0) above else (union.bottom + margin).coerceAtMost(screenHeight - size)
        windowManager.updateViewLayout(view, params)
        savePosition()
    }

    private fun savePosition() {
        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
    }

    private inner class TouchHandler : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        v.removeCallbacks(longPress)
                    }
                    if (dragging && !longPressed) {
                        params.x = (startX + dx).roundToInt().coerceIn(0, screenWidth - size)
                        params.y = (startY + dy).roundToInt().coerceIn(0, screenHeight - size)
                        windowManager.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (dragging) {
                        savePosition()
                        avoidRecognitionArea()
                    } else if (!longPressed) {
                        v.performClick()
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
            }
            return true
        }
    }

    @SuppressLint("ViewConstructor")
    private class BubbleView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
        }
        private var guidesOn = true
        private var paused = false

        init {
            contentDescription = "GridHelper"
        }

        fun setState(guidesOn: Boolean, paused: Boolean) {
            if (guidesOn == this.guidesOn && paused == this.paused) return
            this.guidesOn = guidesOn
            this.paused = paused
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val r = w / 2f
            fill.color = when {
                paused -> 0xD9F57C00.toInt()
                guidesOn -> 0xD92E7D32.toInt()
                else -> 0xD9616161.toInt()
            }
            canvas.drawCircle(r, r, r, fill)
            val s = w * 0.11f
            if (paused) {
                // "II"
                canvas.drawRect(r - 2.2f * s, r - 2.5f * s, r - 0.8f * s, r + 2.5f * s, ink)
                canvas.drawRect(r + 0.8f * s, r - 2.5f * s, r + 2.2f * s, r + 2.5f * s, ink)
            } else {
                // 2x2 grid: filled when guides are shown, outlined when hidden.
                stroke.strokeWidth = w * 0.04f
                val p = if (guidesOn) ink else stroke
                for (i in 0..1) for (j in 0..1) {
                    val left = r - 2.2f * s + i * 2.4f * s
                    val top = r - 2.2f * s + j * 2.4f * s
                    canvas.drawRect(left, top, left + 2f * s, top + 2f * s, p)
                }
            }
        }
    }

    private companion object {
        const val KEY_X = "x"
        const val KEY_Y = "y"
    }
}
