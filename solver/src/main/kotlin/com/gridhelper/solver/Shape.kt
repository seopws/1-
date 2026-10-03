package com.gridhelper.solver

/**
 * A tray block. Blocks cannot be rotated, so every orientation is its own [Shape].
 * Cells are normalised so the top-most row and left-most column are 0.
 *
 * [originMask] is the shape drawn at board origin (0,0) in 8-wide bitboard coordinates,
 * so a placement at (row, col) is simply `originMask shl (row * 8 + col)`.
 */
class Shape private constructor(
    val rows: Int,
    val cols: Int,
    val originMask: Long,
) {
    val cellCount: Int = java.lang.Long.bitCount(originMask)

    /** Stable textual key, rows joined by '/', e.g. `"##/#."`. Used for persistence. */
    val key: String = buildString {
        for (r in 0 until rows) {
            if (r > 0) append('/')
            for (c in 0 until cols) append(if (cell(r, c)) '#' else '.')
        }
    }

    /** Bit offsets (dr * 8 + dc) of every cell, used by [fitsAnywhere]. */
    @JvmField val offsets: IntArray = IntArray(cellCount).also { out ->
        var i = 0
        for (r in 0 until rows) for (c in 0 until cols) if (cell(r, c)) out[i++] = r * 8 + c
    }

    /** One bit per legal origin (row <= 8 - rows, col <= 8 - cols). */
    val validOriginMask: Long

    /** Placement masks for every legal origin, row-major. */
    @JvmField val placements: LongArray

    /** Origin (row * 8 + col) for each entry of [placements]. */
    @JvmField val placementOrigins: IntArray

    init {
        val n = (Bitboard.SIZE - rows + 1) * (Bitboard.SIZE - cols + 1)
        val masks = LongArray(n)
        val origins = IntArray(n)
        var valid = 0L
        var i = 0
        for (r in 0..Bitboard.SIZE - rows) {
            for (c in 0..Bitboard.SIZE - cols) {
                val o = r * 8 + c
                masks[i] = originMask shl o
                origins[i] = o
                valid = valid or (1L shl o)
                i++
            }
        }
        placements = masks
        placementOrigins = origins
        validOriginMask = valid
    }

    fun cell(row: Int, col: Int): Boolean =
        row in 0 until rows && col in 0 until cols && (originMask ushr (row * 8 + col)) and 1L != 0L

    /** Mask of this shape placed with its top-left bounding-box corner at (row, col). */
    fun maskAt(row: Int, col: Int): Long {
        require(row in 0..Bitboard.SIZE - rows && col in 0..Bitboard.SIZE - cols) {
            "shape $key does not fit at ($row,$col)"
        }
        return originMask shl (row * 8 + col)
    }

    /**
     * True if the shape can be placed somewhere on a board whose empty cells are [emptyMask].
     * Bit-parallel: AND the empty mask shifted by every cell offset, restricted to legal origins.
     */
    fun fitsAnywhere(emptyMask: Long): Boolean {
        var m = validOriginMask
        for (o in offsets) {
            m = m and (emptyMask ushr o)
            if (m == 0L) return false
        }
        return true
    }

    /** Number of legal placements on [board]. */
    fun countPlacements(board: Long): Int {
        var n = 0
        for (m in placements) if (m and board == 0L) n++
        return n
    }

    /** Cells as (row, col) pairs, row-major. */
    fun cells(): List<Pair<Int, Int>> = offsets.map { it / 8 to it % 8 }

    override fun equals(other: Any?): Boolean =
        other is Shape && other.rows == rows && other.cols == cols && other.originMask == originMask

    override fun hashCode(): Int = (originMask xor (originMask ushr 32)).toInt() * 31 + rows * 7 + cols

    override fun toString(): String = "Shape($key)"

    companion object {
        const val MAX_DIM = 5

        /** Builds a shape from text rows, e.g. `Shape.of("##", "#.")`. See [Bitboard.parse] for chars. */
        fun of(vararg rows: String): Shape {
            val cells = ArrayList<Pair<Int, Int>>()
            rows.forEachIndexed { r, line ->
                line.forEachIndexed { c, ch -> if (ch == '#' || ch == 'X' || ch == 'x' || ch == '1') cells += r to c }
            }
            return fromCells(cells)
        }

        /** Parses a [key] string such as `"##/#."`. */
        fun fromKey(key: String): Shape = of(*key.split('/').toTypedArray())

        /** Builds a shape from a boolean matrix [rows][cols]. */
        fun fromMatrix(matrix: Array<BooleanArray>): Shape {
            val cells = ArrayList<Pair<Int, Int>>()
            for (r in matrix.indices) for (c in matrix[r].indices) if (matrix[r][c]) cells += r to c
            return fromCells(cells)
        }

        fun fromCells(cells: Collection<Pair<Int, Int>>): Shape {
            require(cells.isNotEmpty()) { "shape must have at least one cell" }
            val minR = cells.minOf { it.first }
            val minC = cells.minOf { it.second }
            val maxR = cells.maxOf { it.first }
            val maxC = cells.maxOf { it.second }
            val rows = maxR - minR + 1
            val cols = maxC - minC + 1
            require(rows <= Bitboard.SIZE && cols <= Bitboard.SIZE) { "shape too large: ${rows}x$cols" }
            var mask = 0L
            for ((r, c) in cells) mask = mask or (1L shl ((r - minR) * 8 + (c - minC)))
            return Shape(rows, cols, mask)
        }
    }
}
