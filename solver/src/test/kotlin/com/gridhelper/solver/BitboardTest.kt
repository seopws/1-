package com.gridhelper.solver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BitboardTest {

    @Test
    fun parseAndRenderRoundTrip() {
        val rows = arrayOf(
            "#.......",
            ".#......",
            "..#.....",
            "...#....",
            "....#...",
            ".....#..",
            "......#.",
            ".......#",
        )
        val b = Bitboard.parse(*rows)
        assertEquals(rows.joinToString("\n"), Bitboard.render(b))
        assertEquals(8, Bitboard.count(b))
        assertTrue(Bitboard.isSet(b, 3, 3))
        assertFalse(Bitboard.isSet(b, 3, 4))
    }

    @Test
    fun singleRowClear() {
        val b = Bitboard.parse(
            "........",
            "........",
            "########",
            "#.......",
            "........",
            "........",
            "........",
            "........",
        )
        assertEquals(Bitboard.ROW_MASKS[2], Bitboard.clearMask(b))
        assertEquals(0b100, Bitboard.fullRows(b))
        assertEquals(0, Bitboard.fullCols(b))
        assertEquals(Bitboard.bit(3, 0), b and Bitboard.clearMask(b).inv())
    }

    @Test
    fun singleColumnClear() {
        var b = 0L
        for (r in 0 until 8) b = b or Bitboard.bit(r, 5)
        b = b or Bitboard.bit(0, 0)
        assertEquals(Bitboard.COL_MASKS[5], Bitboard.clearMask(b))
        assertEquals(1 shl 5, Bitboard.fullCols(b))
    }

    @Test
    fun rowAndColumnClearTogether() {
        // Row 7 and column 0 complete at the same time; the shared corner is cleared once.
        val b = Bitboard.parse(
            "#.......",
            "#.......",
            "#.......",
            "#.......",
            "#.......",
            "#.......",
            "#......#",
            "########",
        )
        val cm = Bitboard.clearMask(b)
        assertEquals(Bitboard.ROW_MASKS[7] or Bitboard.COL_MASKS[0], cm)
        assertEquals(Bitboard.bit(6, 7), b and cm.inv())
        assertEquals(2, Solver.lineCount(b))
    }

    @Test
    fun fullBoardClearsSixteenLines() {
        assertEquals(Bitboard.FULL, Bitboard.clearMask(Bitboard.FULL))
        assertEquals(16, Solver.lineCount(Bitboard.FULL))
        assertEquals(0L, Bitboard.clearMask(0L))
    }

    @Test
    fun clearMaskMatchesReferenceOnRandomBoards() {
        val rnd = Random(42)
        repeat(20_000) {
            var b = 0L
            val density = rnd.nextDouble(0.5, 1.0)
            for (i in 0 until 64) if (rnd.nextDouble() < density) b = b or (1L shl i)
            val (expectedBoard, expectedLines) = Reference.clear(b)
            assertEquals(Bitboard.render(b), expectedBoard, b and Bitboard.clearMask(b).inv())
            assertEquals(expectedLines, Solver.lineCount(b))
        }
    }
}
