package com.gridhelper.solver

import kotlin.random.Random

/**
 * 1-ply look-ahead: samples [samples] sets of three blocks from the library and measures, for
 * each candidate board, the average fraction of the set that can still be placed (1.0 = the
 * whole set fits in some order). The same sample sets are used for every candidate (common
 * random numbers) so the comparison between candidates is fair even with few samples.
 */
internal class MonteCarlo(library: ShapeLibrary, samples: Int, seed: Long) {

    private val sets: Array<Array<Shape>>

    init {
        val rnd = Random(seed)
        sets = Array(samples) {
            Array(SET_SIZE) { library.shapes[library.sampleIndex(rnd.nextDouble())] }
                // identical shapes adjacent -> the identical-block pruning works best
                .sortedBy { it.key }.toTypedArray()
        }
    }

    private val checker = Checker()

    /**
     * @return value per candidate board in 0..1, or null if not a single sample finished before
     * [deadlineNanos] (System.nanoTime based).
     */
    fun evaluate(boards: LongArray, deadlineNanos: Long): DoubleArray? = synchronized(checker) {
        val totals = DoubleArray(boards.size)
        var done = 0
        for (set in sets) {
            for (c in boards.indices) {
                totals[c] += checker.maxPlaceable(boards[c], set).toDouble() / set.size
            }
            done++
            if (System.nanoTime() > deadlineNanos) break
        }
        if (done == 0) return null
        for (c in totals.indices) totals[c] /= done
        totals
    }

    /** Depth-first "can everything be placed" search with early exit and a node budget. */
    private class Checker {
        private var pieces: Array<Shape> = emptyArray()
        private var budget = 0
        private var prevSame = IntArray(SET_SIZE)

        fun maxPlaceable(board: Long, set: Array<Shape>): Int {
            pieces = set
            budget = NODE_BUDGET
            for (i in set.indices) {
                prevSame[i] = if (i > 0 && set[i - 1] == set[i]) i - 1 else -1
            }
            return dfs(board, 0, 0, -1)
        }

        private fun dfs(board: Long, used: Int, depth: Int, lastNonClear: Int): Int {
            val n = pieces.size
            if (depth == n) return n
            var best = depth
            for (i in 0 until n) {
                if (used and (1 shl i) != 0) continue
                val ps = prevSame[i]
                if (ps >= 0 && used and (1 shl ps) == 0) continue
                val placements = pieces[i].placements
                for (p in placements.indices) {
                    val m = placements[p]
                    if (m and board != 0L) continue
                    val nb = board or m
                    val cm = Bitboard.clearMask(nb)
                    val r = if (cm == 0L) {
                        if (lastNonClear > i) continue
                        dfs(nb, used or (1 shl i), depth + 1, i)
                    } else {
                        dfs(nb and cm.inv(), used or (1 shl i), depth + 1, -1)
                    }
                    if (r == n) return n
                    if (r > best) best = r
                    if (--budget <= 0) return best
                }
            }
            return best
        }
    }

    companion object {
        const val SET_SIZE = 3
        const val NODE_BUDGET = 4000
    }
}
