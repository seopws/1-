package com.gridhelper.solver

import kotlin.random.Random

/**
 * Deliberately naive re-implementations (plain loops, flood fill, no pruning) used to
 * cross-check the bit-parallel production code.
 */
object Reference {

    fun filled(board: Long, r: Int, c: Int) = Bitboard.isSet(board, r, c)

    /** Returns (board after clearing, lines cleared). */
    fun clear(board: Long): Pair<Long, Int> {
        val fullRows = (0 until 8).filter { r -> (0 until 8).all { c -> filled(board, r, c) } }
        val fullCols = (0 until 8).filter { c -> (0 until 8).all { r -> filled(board, r, c) } }
        var b = board
        for (r in fullRows) for (c in 0 until 8) b = b and Bitboard.bit(r, c).inv()
        for (c in fullCols) for (r in 0 until 8) b = b and Bitboard.bit(r, c).inv()
        return b to (fullRows.size + fullCols.size)
    }

    fun holes(board: Long): Int {
        val seen = Array(8) { BooleanArray(8) }
        var holes = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            if (filled(board, r, c) || seen[r][c]) continue
            var size = 0
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.add(r to c)
            seen[r][c] = true
            while (stack.isNotEmpty()) {
                val (cr, cc) = stack.removeLast()
                size++
                for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val nr = cr + dr
                    val nc = cc + dc
                    if (nr !in 0..7 || nc !in 0..7) continue
                    if (filled(board, nr, nc) || seen[nr][nc]) continue
                    seen[nr][nc] = true
                    stack.add(nr to nc)
                }
            }
            if (size <= 2) holes++
        }
        return holes
    }

    fun roughness(board: Long): Int {
        var n = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            if (c < 7 && filled(board, r, c) != filled(board, r, c + 1)) n++
            if (r < 7 && filled(board, r, c) != filled(board, r + 1, c)) n++
        }
        return n
    }

    fun fits(shape: Shape, board: Long): Boolean {
        for (r in 0..8 - shape.rows) for (c in 0..8 - shape.cols) {
            if (shape.cells().all { (dr, dc) -> !filled(board, r + dr, c + dc) }) return true
        }
        return false
    }

    fun place(shape: Shape, board: Long, r: Int, c: Int): Long? {
        var b = board
        for ((dr, dc) in shape.cells()) {
            if (filled(b, r + dr, c + dc)) return null
            b = b or Bitboard.bit(r + dr, c + dc)
        }
        return b
    }

    /** Best total score over every permutation and placement, or null if no complete plan. */
    fun bestScore(board: Long, pieces: List<Shape>, evaluator: Evaluator): Double? {
        var best: Double? = null
        fun rec(b: Long, remaining: List<Shape>, reward: Double) {
            if (remaining.isEmpty()) {
                val s = reward + evaluator.evaluate(b)
                if (best == null || s > best!!) best = s
                return
            }
            for (i in remaining.indices) {
                val shape = remaining[i]
                val rest = remaining.filterIndexed { j, _ -> j != i }
                for (r in 0..8 - shape.rows) for (c in 0..8 - shape.cols) {
                    val placed = place(shape, b, r, c) ?: continue
                    val (after, lines) = clear(placed)
                    rec(after, rest, reward + evaluator.stepReward(lines))
                }
            }
        }
        rec(board, pieces, 0.0)
        return best
    }

    /** Random board with roughly [density] filled cells and no completed line. */
    fun randomBoard(rnd: Random, density: Double): Long {
        var b = 0L
        for (i in 0 until 64) if (rnd.nextDouble() < density) b = b or (1L shl i)
        return clear(b).first
    }

    fun randomPieces(rnd: Random, n: Int, library: ShapeLibrary = ShapeLibrary.default()): List<Shape> =
        List(n) { library.shapes[rnd.nextInt(library.size)] }
}
