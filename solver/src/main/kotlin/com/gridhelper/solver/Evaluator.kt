package com.gridhelper.solver

/**
 * Weights of the evaluation function. All values are non-negative; penalties are subtracted.
 * Exposed in the settings screen as sliders.
 */
data class EvalWeights(
    /** Reward per cleared line. */
    val clearLine: Double = 10.0,
    /** Extra reward per additional line when 2+ lines clear at once. */
    val multiClear: Double = 12.0,
    /** Reward per empty cell on the resulting board. */
    val emptyCell: Double = 0.6,
    /** Penalty per isolated hole (empty 4-connected region of 1-2 cells). */
    val hole: Double = 7.0,
    /** Bonus if a 3x3 block still fits. */
    val fit3x3: Double = 5.0,
    /** Bonus if a horizontal 1x5 still fits. */
    val fit1x5: Double = 3.0,
    /** Bonus if a vertical 5x1 still fits. */
    val fit5x1: Double = 3.0,
    /** Bonus scaled by the (frequency weighted) fraction of library blocks that still fit. */
    val libraryFit: Double = 25.0,
    /** Penalty per filled/empty boundary edge. */
    val roughness: Double = 0.5,
    /** Weight of the Monte Carlo survival estimate (0..1) when look-ahead is enabled. */
    val monteCarlo: Double = 40.0,
) {
    companion object {
        val DEFAULT = EvalWeights()
    }
}

/** Raw feature values of a board, mainly for tests and the debug overlay. */
data class EvalFeatures(
    val emptyCells: Int,
    val holes: Int,
    val fits3x3: Boolean,
    val fits1x5: Boolean,
    val fits5x1: Boolean,
    val libraryFitRatio: Double,
    val roughness: Int,
)

/**
 * Static board evaluation. Every feature is computed with bit-parallel operations so a
 * leaf costs a few hundred machine operations.
 */
class Evaluator(val weights: EvalWeights, library: ShapeLibrary) {

    private val libShapes: Array<Shape> = library.shapes.take(MAX_LIBRARY_SHAPES).toTypedArray()
    private val libWeights: DoubleArray = run {
        val w = library.weights.copyOf(libShapes.size)
        val total = w.sum().takeIf { it > 0 } ?: 1.0
        DoubleArray(w.size) { w[it] / total }
    }

    /** Reward for a single placement that cleared [lines] lines. */
    fun stepReward(lines: Int): Double {
        if (lines == 0) return 0.0
        var r = weights.clearLine * lines
        if (lines >= 2) r += weights.multiClear * (lines - 1)
        return r
    }

    fun evaluate(board: Long): Double {
        val empty = board.inv()
        var score = weights.emptyCell * java.lang.Long.bitCount(empty)
        score -= weights.hole * countHoles(empty)
        score -= weights.roughness * roughness(board)
        if (SQUARE3.fitsAnywhere(empty)) score += weights.fit3x3
        if (LINE5H.fitsAnywhere(empty)) score += weights.fit1x5
        if (LINE5V.fitsAnywhere(empty)) score += weights.fit5x1
        if (weights.libraryFit != 0.0) score += weights.libraryFit * libraryFitRatio(empty)
        return score
    }

    fun features(board: Long): EvalFeatures {
        val empty = board.inv()
        return EvalFeatures(
            emptyCells = java.lang.Long.bitCount(empty),
            holes = countHoles(empty),
            fits3x3 = SQUARE3.fitsAnywhere(empty),
            fits1x5 = LINE5H.fitsAnywhere(empty),
            fits5x1 = LINE5V.fitsAnywhere(empty),
            libraryFitRatio = libraryFitRatio(empty),
            roughness = roughness(board),
        )
    }

    fun libraryFitRatio(empty: Long): Double {
        var sum = 0.0
        for (i in libShapes.indices) if (libShapes[i].fitsAnywhere(empty)) sum += libWeights[i]
        return sum
    }

    companion object {
        /** Upper bound on library shapes checked per leaf (most frequent first). */
        const val MAX_LIBRARY_SHAPES = 48

        val SQUARE3: Shape = Shape.of("###", "###", "###")
        val LINE5H: Shape = Shape.of("#####")
        val LINE5V: Shape = Shape.of("#", "#", "#", "#", "#")

        /**
         * Number of empty 4-connected regions of size 1 or 2 (walls count as filled).
         * [empty] is the empty-cell mask.
         */
        fun countHoles(empty: Long): Int {
            val up = empty shl 8 // bit i <- cell above
            val down = empty ushr 8 // bit i <- cell below
            val left = (empty shl 1) and Bitboard.NOT_COL0 // bit i <- cell to the left
            val right = (empty ushr 1) and Bitboard.NOT_COL7 // bit i <- cell to the right
            val any = up or down or left or right
            val single = empty and any.inv()
            val parity = up xor down xor left xor right
            val atLeastTwo = (up and down) or (up and left) or (up and right) or
                (down and left) or (down and right) or (left and right)
            val exactlyOne = empty and parity and atLeastTwo.inv()
            val horizontalPairs = exactlyOne and (exactlyOne ushr 1) and right
            val verticalPairs = exactlyOne and (exactlyOne ushr 8) and down
            return java.lang.Long.bitCount(single) +
                java.lang.Long.bitCount(horizontalPairs) +
                java.lang.Long.bitCount(verticalPairs)
        }

        /** Number of adjacent (horizontal + vertical) cell pairs whose filled state differs. */
        fun roughness(board: Long): Int {
            val h = (board xor (board ushr 1)) and Bitboard.NOT_COL7
            val v = (board xor (board ushr 8)) and Bitboard.NOT_ROW7
            return java.lang.Long.bitCount(h) + java.lang.Long.bitCount(v)
        }
    }
}
