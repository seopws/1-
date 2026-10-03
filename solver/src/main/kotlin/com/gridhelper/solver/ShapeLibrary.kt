package com.gridhelper.solver

/**
 * The set of blocks the game can deal, with relative frequencies. Used by the evaluator
 * ("how many kinds of block still fit") and by the Monte Carlo look-ahead (sampling the
 * next set of three blocks).
 */
class ShapeLibrary(shapes: List<Shape>, weights: List<Double>) {
    val shapes: List<Shape>
    /** Normalised so the sum is 1. Same order as [shapes]. */
    val weights: DoubleArray

    init {
        require(shapes.size == weights.size) { "shapes/weights size mismatch" }
        require(shapes.isNotEmpty()) { "library must not be empty" }
        // Sort by weight (desc) so frequent shapes are checked first.
        val order = shapes.indices.sortedByDescending { weights[it] }
        this.shapes = order.map { shapes[it] }
        val total = weights.sum().takeIf { it > 0.0 } ?: 1.0
        this.weights = DoubleArray(order.size) { weights[order[it]] / total }
    }

    private val cumulative: DoubleArray = DoubleArray(this.weights.size).also {
        var acc = 0.0
        for (i in it.indices) {
            acc += this.weights[i]
            it[i] = acc
        }
    }

    val size: Int get() = shapes.size

    /** Draws a shape index according to the weights. [u] must be uniform in [0, 1). */
    fun sampleIndex(u: Double): Int {
        val target = u * cumulative[cumulative.size - 1]
        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] > target) hi = mid else lo = mid + 1
        }
        return lo
    }

    companion object {
        /** Common blocks of 8x8 block puzzles. Every orientation is a separate entry. */
        val DEFAULT_SHAPES: List<Shape> = listOf(
            // 1x1, lines
            "#",
            "##", "#/#",
            "###", "#/#/#",
            "####", "#/#/#/#",
            "#####", "#/#/#/#/#",
            // squares / rectangles
            "##/##",
            "###/###/###",
            "###/###", "##/##/##",
            // small corners (3 cells)
            "##/#.", "##/.#", "#./##", ".#/##",
            // L / J tetrominoes
            "#./#./##", ".#/.#/##", "##/#./#.", "##/.#/.#",
            "###/#..", "###/..#", "#../###", "..#/###",
            // T tetrominoes
            "###/.#.", ".#./###", "#./##/#.", ".#/##/.#",
            // S / Z tetrominoes
            ".##/##.", "##./.##", "#./##/.#", ".#/##/#.",
            // big corners (5 cells)
            "###/#../#..", "###/..#/..#", "#../#../###", "..#/..#/###",
        ).map { Shape.fromKey(it) }

        fun default(): ShapeLibrary = ShapeLibrary(DEFAULT_SHAPES, List(DEFAULT_SHAPES.size) { 1.0 })

        /**
         * Builds a library from observed counts (shape key -> times seen). Every default shape keeps
         * a [prior] pseudo-count so unseen-but-possible shapes are never impossible; observed shapes
         * that are not in the default list are added with their count.
         */
        fun fromCounts(counts: Map<String, Int>, prior: Double = 1.0): ShapeLibrary {
            val weightByShape = LinkedHashMap<Shape, Double>()
            for (s in DEFAULT_SHAPES) weightByShape[s] = prior
            for ((key, n) in counts) {
                if (n <= 0) continue
                val shape = runCatching { Shape.fromKey(key) }.getOrNull() ?: continue
                if (shape.rows > Shape.MAX_DIM || shape.cols > Shape.MAX_DIM) continue
                weightByShape[shape] = (weightByShape[shape] ?: 0.0) + n
            }
            return ShapeLibrary(weightByShape.keys.toList(), weightByShape.values.toList())
        }
    }
}
