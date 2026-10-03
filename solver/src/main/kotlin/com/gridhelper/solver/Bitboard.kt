package com.gridhelper.solver

/**
 * 8x8 board packed into a [Long]. Bit index = row * 8 + col, row 0 is the top row,
 * col 0 is the left column. A set bit means the cell is filled.
 */
object Bitboard {
    const val SIZE = 8
    const val EMPTY: Long = 0L
    const val FULL: Long = -1L

    /** Every cell except column 0 / column 7, used to stop horizontal shifts wrapping rows. */
    const val NOT_COL0: Long = -0x0101010101010102L // ~0x0101010101010101
    const val NOT_COL7: Long = 0x7F7F7F7F7F7F7F7FL
    /** Rows 0..6 (everything except the bottom row). */
    const val NOT_ROW7: Long = 0x00FFFFFFFFFFFFFFL

    private const val LOW_BIT_PER_BYTE: Long = 0x0101010101010101L

    @JvmField val ROW_MASKS = LongArray(SIZE) { 0xFFL shl (8 * it) }
    @JvmField val COL_MASKS = LongArray(SIZE) { LOW_BIT_PER_BYTE shl it }

    fun bit(row: Int, col: Int): Long = 1L shl (row * 8 + col)

    fun isSet(board: Long, row: Int, col: Int): Boolean = board and bit(row, col) != 0L

    fun count(board: Long): Int = java.lang.Long.bitCount(board)

    /**
     * Bit mask of every cell that belongs to a completed row or column. Rows and columns
     * completed by the same placement are cleared together, so the union is returned.
     */
    fun clearMask(board: Long): Long {
        var t = board and (board ushr 1) and 0x7F7F7F7F7F7F7F7FL
        t = t and (t ushr 2) and 0x3F3F3F3F3F3F3F3FL
        t = t and (t ushr 4) and 0x0F0F0F0F0F0F0F0FL
        val rows = t and LOW_BIT_PER_BYTE // bit 8*r set when row r is full
        var c = board and (board ushr 32)
        c = c and (c ushr 16)
        c = c and (c ushr 8)
        val cols = c and 0xFFL // bit c set when column c is full
        return (rows * 0xFFL) or (cols * LOW_BIT_PER_BYTE)
    }

    /** Bits 0..7 = completed rows. */
    fun fullRows(board: Long): Int {
        var r = 0
        for (i in 0 until SIZE) if (board and ROW_MASKS[i] == ROW_MASKS[i]) r = r or (1 shl i)
        return r
    }

    /** Bits 0..7 = completed columns. */
    fun fullCols(board: Long): Int {
        var c = board and (board ushr 32)
        c = c and (c ushr 16)
        c = c and (c ushr 8)
        return (c and 0xFFL).toInt()
    }

    /**
     * Parses 8 strings of 8 characters. `#`, `X`, `x`, `1`, `O`, `o` mean filled;
     * anything else (`.`, `0`, space) means empty.
     */
    fun parse(vararg rows: String): Long {
        require(rows.size == SIZE) { "expected 8 rows, got ${rows.size}" }
        var b = 0L
        for (r in 0 until SIZE) {
            require(rows[r].length == SIZE) { "row $r must have 8 cells: '${rows[r]}'" }
            for (c in 0 until SIZE) if (rows[r][c] in FILLED_CHARS) b = b or bit(r, c)
        }
        return b
    }

    fun render(board: Long, filled: Char = '#', empty: Char = '.'): String = buildString {
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) append(if (isSet(board, r, c)) filled else empty)
            if (r < SIZE - 1) append('\n')
        }
    }

    private val FILLED_CHARS = setOf('#', 'X', 'x', '1', 'O', 'o')
}
