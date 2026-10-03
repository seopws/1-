package com.gridhelper.vision

import com.gridhelper.solver.Shape

enum class AnalysisStatus {
    /** Board and tray recognised with confidence. */
    OK,

    /** No board on screen: probably not the game. The overlay hides itself. */
    NO_BOARD,

    /** Board found but some cells / tray blocks are ambiguous (animation, drag preview...). */
    UNRELIABLE,
}

data class FrameAnalysis(
    val width: Int,
    val height: Int,
    val status: AnalysisStatus,
    val board: BoardDetection?,
    val tray: TrayDetection?,
    val failure: String?,
    /** Best dark region even when detection failed (debug drawing). */
    val candidate: RectI?,
    val elapsedNanos: Long,
) {
    val bitboard: Long? get() = board?.bitboard
    val shapes: List<Shape?>? get() = tray?.shapes
}

/** Board + tray recognition for one frame. Stateless and thread-confined per instance. */
class FrameAnalyzer(
    private val boardDetector: BoardDetector = BoardDetector(),
    private val trayDetector: TrayDetector = TrayDetector(),
) {
    fun analyze(src: PixelSource): FrameAnalysis {
        val start = System.nanoTime()
        val search = boardDetector.detect(src)
        val board = search.detection
            ?: return FrameAnalysis(
                src.width, src.height, AnalysisStatus.NO_BOARD, null, null,
                search.failure, search.candidate, System.nanoTime() - start,
            )
        val tray = trayDetector.detect(src, board)
        val problems = buildList {
            if (board.ambiguous != 0L) add("${java.lang.Long.bitCount(board.ambiguous)} ambiguous cells")
            if (tray.uncertain) add("uncertain tray block")
        }
        val status = if (problems.isEmpty()) AnalysisStatus.OK else AnalysisStatus.UNRELIABLE
        return FrameAnalysis(
            src.width, src.height, status, board, tray,
            problems.takeIf { it.isNotEmpty() }?.joinToString(), search.candidate, System.nanoTime() - start,
        )
    }
}

/**
 * 32 x 64 down-sampled luma signature of a frame, used to skip frames that did not change and
 * to require two identical consecutive frames before analysing (animations in progress produce
 * different signatures). The top and bottom bands are excluded: status bar, banner/debug text of
 * our own overlay and the navigation bar must not keep the signature changing.
 */
class FrameSignature private constructor(private val values: ByteArray) {

    /** 64-bit FNV-1a hash of the quantised signature. */
    val hash: Long by lazy {
        var h = -0x340d631b7bdddcdbL
        for (v in values) {
            h = h xor ((v.toInt() and 0xFF) shr 2).toLong()
            h *= 0x100000001b3L
        }
        h
    }

    /** Number of cells whose luma differs by more than [tolerance]. */
    fun differences(other: FrameSignature, tolerance: Int = 6): Int {
        var n = 0
        for (i in values.indices) {
            if (kotlin.math.abs((values[i].toInt() and 0xFF) - (other.values[i].toInt() and 0xFF)) > tolerance) n++
        }
        return n
    }

    fun isSameAs(other: FrameSignature?, maxDifferentCells: Int = 3): Boolean =
        other != null && differences(other) <= maxDifferentCells

    companion object {
        const val COLS = 32
        const val ROWS = 64
        const val TOP = 0.10f
        const val BOTTOM = 0.92f

        fun of(src: PixelSource): FrameSignature {
            val out = ByteArray(COLS * ROWS)
            val y0 = src.height * TOP
            val bandH = src.height * (BOTTOM - TOP)
            val cw = src.width.toFloat() / COLS
            val ch = bandH / ROWS
            for (r in 0 until ROWS) {
                for (c in 0 until COLS) {
                    // 2x2 samples inside the cell
                    var sum = 0
                    for (sy in 0..1) for (sx in 0..1) {
                        val x = (c * cw + cw * (0.25f + 0.5f * sx)).toInt().coerceIn(0, src.width - 1)
                        val y = (y0 + r * ch + ch * (0.25f + 0.5f * sy)).toInt().coerceIn(0, src.height - 1)
                        sum += ColorMath.luma(src.rgb(x, y))
                    }
                    out[r * COLS + c] = (sum / 4).toByte()
                }
            }
            return FrameSignature(out)
        }
    }
}
