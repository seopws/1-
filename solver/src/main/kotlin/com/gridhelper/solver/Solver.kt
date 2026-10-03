package com.gridhelper.solver

data class SolverConfig(
    val weights: EvalWeights = EvalWeights.DEFAULT,
    val library: ShapeLibrary = ShapeLibrary.default(),
    /** Enables the 1-ply Monte Carlo look-ahead over the next set of three blocks. */
    val monteCarlo: Boolean = false,
    val monteCarloSamples: Int = 200,
    /** How many of the best static plans (distinct final boards) are re-ranked by Monte Carlo. */
    val monteCarloCandidates: Int = 8,
    /** Wall-clock budget for the Monte Carlo phase; sampling stops early when exceeded. */
    val monteCarloBudgetMillis: Long = 120,
    /** Fixed seed so that identical inputs give identical (non-flickering) recommendations. */
    val seed: Long = 0x5EED_1234L,
)

/**
 * Exhaustive solver: every order of the remaining blocks x every legal placement.
 *
 * Duplicate work is pruned without losing any outcome:
 *  - identical blocks are always taken in slot order;
 *  - two consecutive placements that both clear nothing commute (same final board, same reward),
 *    so only the order with increasing block index is explored.
 *
 * Pure Kotlin, no Android dependencies. Instances are immutable and thread-safe; each call to
 * [solve] allocates its own search state.
 */
class Solver(val config: SolverConfig = SolverConfig()) {

    val evaluator = Evaluator(config.weights, config.library)

    private val monteCarlo: MonteCarlo? =
        if (config.monteCarlo && config.monteCarloSamples > 0) {
            MonteCarlo(config.library, config.monteCarloSamples, config.seed)
        } else {
            null
        }

    /**
     * @param board current board (filled cells set).
     * @param tray the three tray slots; `null` = slot already used.
     */
    fun solve(board: Long, tray: List<Shape?>): SolveResult {
        val start = System.nanoTime()
        val slots = tray.indices.filter { tray[it] != null }
        if (slots.isEmpty()) {
            return SolveResult(SolveStatus.NO_PIECES, emptyList(), 0.0, 0.0, null, System.nanoTime() - start, 0)
        }
        val pieces = Array(slots.size) { tray[slots[it]]!! }
        val topK = if (monteCarlo != null) config.monteCarloCandidates.coerceAtLeast(1) else 0
        val search = Search(evaluator, pieces, topK)
        search.dfs(0, board, 0, 0.0, -1, -1)

        if (search.bestScore == Double.NEGATIVE_INFINITY) {
            return gameOver(board, pieces, slots, start, search.leaves)
        }

        var chosenPieces = search.bestPiece
        var chosenPlacements = search.bestPlacement
        var finalScore = search.bestScore
        var staticScore = search.bestScore
        var mcValue: Double? = null

        if (monteCarlo != null && search.candCount > 0) {
            val deadline = start + config.monteCarloBudgetMillis * 1_000_000L
            val boards = search.candBoard.copyOf(search.candCount)
            val values = monteCarlo.evaluate(boards, deadline)
            if (values != null) {
                var best = -1
                var bestTotal = Double.NEGATIVE_INFINITY
                for (c in 0 until search.candCount) {
                    val total = search.candScore[c] + config.weights.monteCarlo * values[c]
                    if (total > bestTotal) {
                        bestTotal = total
                        best = c
                    }
                }
                chosenPieces = search.candPiece[best]
                chosenPlacements = search.candPlacement[best]
                finalScore = bestTotal
                staticScore = search.candScore[best]
                mcValue = values[best]
            }
        }

        val moves = replay(board, pieces, slots, chosenPieces, chosenPlacements, pieces.size)
        return SolveResult(
            status = SolveStatus.OK,
            moves = moves,
            score = finalScore,
            staticScore = staticScore,
            monteCarloValue = mcValue,
            elapsedNanos = System.nanoTime() - start,
            leaves = search.leaves,
        )
    }

    /** No complete plan exists: suggest the single highest-scoring move (if any). */
    private fun gameOver(board: Long, pieces: Array<Shape>, slots: List<Int>, start: Long, leaves: Long): SolveResult {
        var bestScore = Double.NEGATIVE_INFINITY
        var bestPiece = -1
        var bestPlacement = -1
        for (i in pieces.indices) {
            val placements = pieces[i].placements
            for (p in placements.indices) {
                val m = placements[p]
                if (m and board != 0L) continue
                val nb = board or m
                val cm = Bitboard.clearMask(nb)
                val reward = if (cm == 0L) 0.0 else evaluator.stepReward(lineCount(nb))
                val s = reward + evaluator.evaluate(nb and cm.inv())
                if (s > bestScore) {
                    bestScore = s
                    bestPiece = i
                    bestPlacement = p
                }
            }
        }
        val moves = if (bestPiece < 0) {
            emptyList()
        } else {
            replay(board, pieces, slots, intArrayOf(bestPiece), intArrayOf(bestPlacement), 1)
        }
        val score = if (bestPiece < 0) 0.0 else bestScore
        return SolveResult(SolveStatus.GAME_OVER, moves, score, score, null, System.nanoTime() - start, leaves)
    }

    private fun replay(
        board: Long,
        pieces: Array<Shape>,
        slots: List<Int>,
        pieceIdx: IntArray,
        placementIdx: IntArray,
        count: Int,
    ): List<Move> {
        val moves = ArrayList<Move>(count)
        var b = board
        for (k in 0 until count) {
            val shape = pieces[pieceIdx[k]]
            val p = placementIdx[k]
            val mask = shape.placements[p]
            val origin = shape.placementOrigins[p]
            val nb = b or mask
            val rows = Bitboard.fullRows(nb)
            val cols = Bitboard.fullCols(nb)
            val after = nb and Bitboard.clearMask(nb).inv()
            moves += Move(
                slot = slots[pieceIdx[k]],
                shape = shape,
                row = origin / 8,
                col = origin % 8,
                cellsMask = mask,
                clearedRows = rows,
                clearedCols = cols,
                boardBefore = b,
                boardAfter = after,
            )
            b = after
        }
        return moves
    }

    private class Search(
        private val evaluator: Evaluator,
        private val pieces: Array<Shape>,
        private val topK: Int,
    ) {
        private val n = pieces.size

        /** Largest j < i with an identical shape, or -1. */
        private val prevSame = IntArray(n) { i -> (i - 1 downTo 0).firstOrNull { pieces[it] == pieces[i] } ?: -1 }

        private val pathPiece = IntArray(n)
        private val pathPlacement = IntArray(n)

        var leaves = 0L
        var bestScore = Double.NEGATIVE_INFINITY
        val bestPiece = IntArray(n)
        val bestPlacement = IntArray(n)

        val candScore = DoubleArray(topK)
        val candBoard = LongArray(topK)
        val candPiece = Array(topK) { IntArray(n) }
        val candPlacement = Array(topK) { IntArray(n) }
        var candCount = 0
        private var candMin = 0

        /**
         * @param lastNonClear block index of the previous placement if it cleared nothing, else -1.
         * @param lastPlacement placement index of that previous placement.
         */
        fun dfs(depth: Int, board: Long, used: Int, reward: Double, lastNonClear: Int, lastPlacement: Int) {
            if (depth == n) {
                leaf(board, reward)
                return
            }
            for (i in 0 until n) {
                if (used and (1 shl i) != 0) continue
                val ps = prevSame[i]
                if (ps >= 0 && used and (1 shl ps) == 0) continue
                val sameAsLast = ps >= 0 && ps == lastNonClear
                val placements = pieces[i].placements
                val nextUsed = used or (1 shl i)
                pathPiece[depth] = i
                for (p in placements.indices) {
                    val m = placements[p]
                    if (m and board != 0L) continue
                    val nb = board or m
                    val cm = Bitboard.clearMask(nb)
                    pathPlacement[depth] = p
                    if (cm == 0L) {
                        if (lastNonClear > i) continue
                        if (sameAsLast && p < lastPlacement) continue
                        dfs(depth + 1, nb, nextUsed, reward, i, p)
                    } else {
                        dfs(depth + 1, nb and cm.inv(), nextUsed, reward + evaluator.stepReward(lineCount(nb)), -1, -1)
                    }
                }
            }
        }

        private fun leaf(board: Long, reward: Double) {
            leaves++
            val s = reward + evaluator.evaluate(board)
            if (s > bestScore) {
                bestScore = s
                pathPiece.copyInto(bestPiece)
                pathPlacement.copyInto(bestPlacement)
            }
            if (topK > 0) offer(s, board)
        }

        private fun offer(score: Double, board: Long) {
            if (candCount == topK && score <= candScore[candMin]) return
            for (c in 0 until candCount) {
                if (candBoard[c] == board) {
                    if (score > candScore[c]) store(c, score, board)
                    recomputeMin()
                    return
                }
            }
            if (candCount < topK) {
                store(candCount++, score, board)
            } else {
                store(candMin, score, board)
            }
            recomputeMin()
        }

        private fun store(c: Int, score: Double, board: Long) {
            candScore[c] = score
            candBoard[c] = board
            pathPiece.copyInto(candPiece[c])
            pathPlacement.copyInto(candPlacement[c])
        }

        private fun recomputeMin() {
            var m = 0
            for (c in 1 until candCount) if (candScore[c] < candScore[m]) m = c
            candMin = m
        }
    }

    companion object {
        /** Number of completed rows + columns on [board] (before clearing). */
        fun lineCount(board: Long): Int =
            Integer.bitCount(Bitboard.fullRows(board)) + Integer.bitCount(Bitboard.fullCols(board))
    }
}
