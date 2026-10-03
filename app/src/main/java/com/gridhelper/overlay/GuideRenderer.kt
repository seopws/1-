package com.gridhelper.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import com.gridhelper.solver.Bitboard
import com.gridhelper.solver.Shape
import com.gridhelper.solver.SolveResult
import com.gridhelper.solver.SolveStatus
import com.gridhelper.vision.FrameAnalysis
import com.gridhelper.vision.GridGeometry
import com.gridhelper.vision.TrayDetection
import kotlin.math.min

/**
 * Draws recommendations and debug information with plain android.graphics, in capture (= screen)
 * pixel coordinates. Shared by the live overlay and the static screenshot test screen.
 *
 * Everything drawn over the board avoids the centre of the cells (which the recogniser samples)
 * and nothing is drawn over the tray, so the overlay never disturbs recognition of the next frame.
 */
class GuideRenderer(private val density: Float) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6C62828.toInt() }
    private val bannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val debugLinePaint = Paint().apply {
        color = 0xFFFFEB3B.toInt()
        strokeWidth = 1.5f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
    }
    private val miniPaint = Paint().apply { color = Color.WHITE }
    private val trayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val rect = RectF()

    /** Ghost blocks, order badges and line-clear highlights for every step of the plan. */
    fun drawPlan(canvas: Canvas, grid: GridGeometry, result: SolveResult) {
        val cell = grid.cellSize
        ringPaint.strokeWidth = cell * 0.16f
        outlinePaint.strokeWidth = cell * 0.05f
        linePaint.strokeWidth = cell * 0.06f
        linePaint.pathEffect = DashPathEffect(floatArrayOf(cell * 0.22f, cell * 0.12f), 0f)
        result.moves.forEachIndexed { step, move ->
            val color = STEP_COLORS[step % STEP_COLORS.size]
            // Line clears this step will cause (drawn on the cell borders).
            linePaint.color = color
            for (r in 0 until 8) if (move.clearedRows and (1 shl r) != 0) {
                rect.set(grid.left, grid.cellTop(r), grid.right, grid.cellTop(r) + grid.cellH)
                canvas.drawRect(rect, linePaint)
            }
            for (c in 0 until 8) if (move.clearedCols and (1 shl c) != 0) {
                rect.set(grid.cellLeft(c), grid.top, grid.cellLeft(c) + grid.cellW, grid.bottom)
                canvas.drawRect(rect, linePaint)
            }
            // Ghost block: a thick ring inside each cell, centre left transparent.
            ringPaint.color = (color and 0x00FFFFFF) or (0xE0 shl 24)
            outlinePaint.color = color
            val inset = cell * 0.07f + ringPaint.strokeWidth / 2f
            var first = -1
            for (bit in 0 until 64) {
                if (move.cellsMask and (1L shl bit) == 0L) continue
                if (first < 0) first = bit
                val r = bit / 8
                val c = bit % 8
                rect.set(
                    grid.cellLeft(c) + inset, grid.cellTop(r) + inset,
                    grid.cellLeft(c) + grid.cellW - inset, grid.cellTop(r) + grid.cellH - inset,
                )
                canvas.drawRect(rect, ringPaint)
                drawPieceOutline(canvas, grid, move.cellsMask, r, c)
            }
            if (first >= 0) drawBadge(canvas, grid, first / 8, first % 8, step + 1, color)
        }
    }

    /** Outer border of the piece: edges of piece cells that face a non-piece cell. */
    private fun drawPieceOutline(canvas: Canvas, grid: GridGeometry, mask: Long, r: Int, c: Int) {
        val l = grid.cellLeft(c)
        val t = grid.cellTop(r)
        val rr = l + grid.cellW
        val b = t + grid.cellH
        fun has(rr2: Int, cc2: Int) = rr2 in 0..7 && cc2 in 0..7 && mask and Bitboard.bit(rr2, cc2) != 0L
        if (!has(r - 1, c)) canvas.drawLine(l, t, rr, t, outlinePaint)
        if (!has(r + 1, c)) canvas.drawLine(l, b, rr, b, outlinePaint)
        if (!has(r, c - 1)) canvas.drawLine(l, t, l, b, outlinePaint)
        if (!has(r, c + 1)) canvas.drawLine(rr, t, rr, b, outlinePaint)
    }

    /** Circled step number in the top-left corner of the piece's first cell (outside the sampled centre). */
    private fun drawBadge(canvas: Canvas, grid: GridGeometry, r: Int, c: Int, number: Int, color: Int) {
        val cell = grid.cellSize
        val radius = cell * 0.17f
        val cx = grid.cellLeft(c) + cell * 0.2f
        val cy = grid.cellTop(r) + cell * 0.2f
        badgePaint.color = color
        canvas.drawCircle(cx, cy, radius, badgePaint)
        badgeRimPaint.strokeWidth = cell * 0.025f
        canvas.drawCircle(cx, cy, radius, badgeRimPaint)
        badgeTextPaint.textSize = radius * 1.35f
        val fm = badgeTextPaint.fontMetrics
        canvas.drawText(number.toString(), cx, cy - (fm.ascent + fm.descent) / 2f, badgeTextPaint)
    }

    /** Warning banner shown when not all remaining blocks can be placed. */
    fun drawGameOverBanner(canvas: Canvas, screenWidth: Float, top: Float, result: SolveResult) {
        if (result.status != SolveStatus.GAME_OVER) return
        val text = if (result.moves.isNotEmpty()) {
            "⚠ 남은 블록을 모두 놓을 수 없어요 · 최선의 1수만 표시"
        } else {
            "⚠ 놓을 수 있는 블록이 없어요 (게임 오버)"
        }
        val margin = 12f * density
        val height = 40f * density
        rect.set(margin, top + 8f * density, screenWidth - margin, top + 8f * density + height)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, bannerPaint)
        var size = 15f * density
        bannerTextPaint.textSize = size
        val maxWidth = rect.width() - 2 * margin
        while (bannerTextPaint.measureText(text) > maxWidth && size > 9f * density) {
            size -= 0.5f * density
            bannerTextPaint.textSize = size
        }
        val fm = bannerTextPaint.fontMetrics
        val x = rect.centerX() - bannerTextPaint.measureText(text) / 2f
        canvas.drawText(text, x, rect.centerY() - (fm.ascent + fm.descent) / 2f, bannerTextPaint)
    }

    /**
     * Debug view: recognised grid lines and per-cell decisions on the board, and a text panel with
     * status and the recognised tray shapes in [panel] (kept away from the board and tray).
     */
    fun drawDebug(canvas: Canvas, analysis: FrameAnalysis?, lines: List<String>, panel: RectF) {
        val board = analysis?.board
        if (board != null) {
            val g = board.grid
            for (i in 0..8) {
                canvas.drawLine(g.cellLeft(i), g.top, g.cellLeft(i), g.bottom, debugLinePaint)
                canvas.drawLine(g.left, g.cellTop(i), g.right, g.cellTop(i), debugLinePaint)
            }
            val r = g.cellSize * 0.05f
            for (bit in 0 until 64) {
                val row = bit / 8
                val col = bit % 8
                dotPaint.color = when {
                    board.ambiguous and (1L shl bit) != 0L -> 0xFFD500F9.toInt()
                    board.bitboard and (1L shl bit) != 0L -> 0xFFFF1744.toInt()
                    else -> 0xFF00E676.toInt()
                }
                canvas.drawCircle(g.cellLeft(col) + g.cellSize * 0.12f, g.cellTop(row) + g.cellSize * 0.12f, r, dotPaint)
            }
        }
        // Text panel.
        val lineH = debugTextPaint.textSize * 1.3f
        var y = panel.top + lineH
        for (line in lines) {
            if (y > panel.bottom) break
            canvas.drawText(line, panel.left, y, debugTextPaint)
            y += lineH
        }
        // Mini renderings of the recognised tray shapes, right-aligned in the panel.
        val shapes = analysis?.tray?.shapes ?: return
        val mini = min(6f * density, (panel.height() - 4f * density) / 5f)
        var x = panel.right
        for (shape in shapes.reversed()) {
            x -= (shape?.cols ?: 1) * mini + 8f * density
            if (shape != null) drawMiniShape(canvas, shape, x, panel.top + 2f * density, mini)
        }
    }

    /**
     * Tray slot regions, block bounds and recognised keys. Only for static screenshots: drawing on
     * the live tray would leak into the next frame's block mask.
     */
    fun drawTrayDebug(canvas: Canvas, tray: TrayDetection) {
        for (slot in tray.slots) {
            trayPaint.color = 0x99FFFFFF.toInt()
            canvas.drawRect(
                slot.region.left.toFloat(), slot.region.top.toFloat(),
                slot.region.right.toFloat(), slot.region.bottom.toFloat(), trayPaint,
            )
            val b = slot.bounds ?: continue
            trayPaint.color = if (slot.uncertain) 0xFFD500F9.toInt() else 0xFF00E5FF.toInt()
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), trayPaint)
            canvas.drawText(slot.shape?.key ?: "?", b.left.toFloat(), b.bottom + debugTextPaint.textSize * 1.2f, debugTextPaint)
        }
    }

    private fun drawMiniShape(canvas: Canvas, shape: Shape, left: Float, top: Float, size: Float) {
        for ((r, c) in shape.cells()) {
            canvas.drawRect(left + c * size, top + r * size, left + (c + 1) * size - 1f, top + (r + 1) * size - 1f, miniPaint)
        }
    }

    companion object {
        /** Step colours: ① cyan, ② magenta, ③ amber. */
        val STEP_COLORS = intArrayOf(0xFF00E5FF.toInt(), 0xFFFF4FD8.toInt(), 0xFFFFC400.toInt())
    }
}
