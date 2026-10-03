package com.gridhelper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.net.Uri
import com.gridhelper.GridHelperApp
import com.gridhelper.overlay.GuideRenderer
import com.gridhelper.solver.Bitboard
import com.gridhelper.solver.SolveResult
import com.gridhelper.solver.Solver
import com.gridhelper.solver.SolverConfig
import com.gridhelper.vision.FrameAnalysis
import com.gridhelper.vision.FrameAnalyzer
import com.gridhelper.vision.toPixelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class StaticReport(val annotated: Bitmap, val summary: String)

/** Recognition + recommendation on a screenshot picked from the gallery (no overlay, no capture). */
object StaticAnalysis {

    suspend fun run(context: Context, uri: Uri): StaticReport = withContext(Dispatchers.Default) {
        val app = GridHelperApp.from(context)
        val bitmap = decode(context, uri)
        val analysis = FrameAnalyzer().analyze(bitmap.toPixelSource())
        val settings = app.settings.current
        val board = analysis.board
        val shapes = analysis.shapes
        val result = if (board != null && shapes != null) {
            Solver(SolverConfig(weights = settings.weights, library = app.shapeStats.library(), monteCarlo = settings.monteCarlo))
                .solve(board.bitboard, shapes)
        } else {
            null
        }
        StaticReport(annotate(bitmap, analysis, result), summarize(analysis, result))
    }

    private fun decode(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun annotate(bitmap: Bitmap, analysis: FrameAnalysis, result: SolveResult?): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        // Scale UI elements as if the screenshot were a ~411 dp wide phone screen.
        val density = out.width / 411f
        val renderer = GuideRenderer(density)
        val board = analysis.board
        if (board != null && result != null) {
            renderer.drawPlan(canvas, board.grid, result)
            renderer.drawGameOverBanner(canvas, out.width.toFloat(), out.height * 0.035f, result)
        }
        val top = out.height * 0.035f + 56f * density
        val bottom = board?.outer?.top?.toFloat()?.minus(8f * density) ?: (out.height * 0.25f)
        val panel = RectF(8f * density, top, out.width - 8f * density, maxOf(top + 40f * density, bottom))
        renderer.drawDebug(canvas, analysis, listOf(analysis.status.name + (analysis.failure?.let { " ($it)" } ?: "")), panel)
        analysis.tray?.let { renderer.drawTrayDebug(canvas, it) }
        return out
    }

    private fun summarize(a: FrameAnalysis, result: SolveResult?): String = buildString {
        appendLine("화면 ${a.width}×${a.height}  상태 ${a.status}${a.failure?.let { " ($it)" } ?: ""}")
        appendLine(String.format(Locale.US, "인식 %.1f ms", a.elapsedNanos / 1e6))
        val board = a.board
        if (board == null) {
            appendLine("보드를 찾지 못했습니다 (게임 화면이 아님?)")
            return@buildString
        }
        appendLine("격자: x=${board.grid.left.toInt()} y=${board.grid.top.toInt()} 칸=${String.format(Locale.US, "%.1f", board.grid.cellSize)}px")
        appendLine("보드 (# 채움, ? 불확실):")
        val text = Bitboard.render(board.bitboard).lines()
        val amb = Bitboard.render(board.ambiguous, filled = '?').lines()
        for (r in 0 until 8) appendLine("  ${text[r]}   ${amb[r]}")
        a.tray?.let { t ->
            appendLine(String.format(Locale.US, "트레이 (셀 %.1f px):", t.cellSize))
            t.slots.forEach { s -> appendLine("  슬롯 ${s.index + 1}: ${s.shape?.key ?: "(비어 있음)"}${if (s.uncertain) " ?" else ""}") }
        }
        if (result == null) return@buildString
        appendLine(String.format(Locale.US, "추천: %s  점수 %.1f  (%.1f ms, 리프 %d)", result.status, result.score, result.elapsedMillis, result.leaves))
        result.monteCarloValue?.let { appendLine(String.format(Locale.US, "Monte Carlo 생존율 %.2f", it)) }
        result.moves.forEachIndexed { i, m ->
            val clears = buildList {
                for (r in 0 until 8) if (m.clearedRows and (1 shl r) != 0) add("행${r + 1}")
                for (c in 0 until 8) if (m.clearedCols and (1 shl c) != 0) add("열${c + 1}")
            }
            appendLine("  ${"①②③"[i]} 슬롯 ${m.slot + 1} [${m.shape.key}] → ${m.row + 1}행 ${m.col + 1}열" +
                if (clears.isNotEmpty()) "  클리어: ${clears.joinToString()}" else "")
        }
    }
}
