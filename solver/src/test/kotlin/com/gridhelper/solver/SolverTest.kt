package com.gridhelper.solver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SolverTest {

    private val solver = Solver()

    /** Replays [result] move by move and checks legality, clears and the reported score. */
    private fun assertConsistent(board: Long, tray: List<Shape?>, result: SolveResult, ev: Evaluator = solver.evaluator) {
        var b = board
        var reward = 0.0
        val usedSlots = HashSet<Int>()
        for (m in result.moves) {
            assertEquals(b, m.boardBefore)
            assertTrue("slot reused", usedSlots.add(m.slot))
            assertEquals(tray[m.slot], m.shape)
            assertEquals(m.shape.maskAt(m.row, m.col), m.cellsMask)
            assertEquals("overlap", 0L, m.cellsMask and b)
            val (after, lines) = Reference.clear(b or m.cellsMask)
            assertEquals(after, m.boardAfter)
            assertEquals(lines, m.linesCleared)
            reward += ev.stepReward(lines)
            b = after
        }
        if (result.status == SolveStatus.OK) {
            assertEquals(tray.count { it != null }, result.moves.size)
            assertEquals(reward + ev.evaluate(b), result.staticScore, 1e-9)
        }
    }

    @Test
    fun emptyTrayReturnsNoPieces() {
        val r = solver.solve(0L, listOf(null, null, null))
        assertEquals(SolveStatus.NO_PIECES, r.status)
        assertTrue(r.moves.isEmpty())
    }

    @Test
    fun completesAlmostFullRow() {
        val board = Bitboard.parse(
            "......##",
            "........",
            "........",
            "........",
            "........",
            "........",
            "........",
            "######..",
        )
        val domino = Shape.of("##")
        val tray = listOf(domino, null, null)
        val r = solver.solve(board, tray)
        assertEquals(SolveStatus.OK, r.status)
        val m = r.moves.single()
        assertEquals(7 to 6, m.row to m.col)
        assertEquals(1 shl 7, m.clearedRows)
        assertEquals(1, m.linesCleared)
        assertConsistent(board, tray, r)
    }

    @Test
    fun prefersDoubleClear() {
        // A 2x2 square at (3,6) completes rows 3 and 4 at once.
        val board = Bitboard.parse(
            "........",
            "........",
            "........",
            "######..",
            "######..",
            "........",
            "........",
            "........",
        )
        val tray = listOf(Shape.of("#"), Shape.of("##", "##"), Shape.of("#"))
        val r = solver.solve(board, tray)
        assertEquals(SolveStatus.OK, r.status)
        val square = r.moves.single { it.slot == 1 }
        assertEquals(3 to 6, square.row to square.col)
        assertEquals(2, square.linesCleared)
        assertConsistent(board, tray, r)
    }

    @Test
    fun rowAndColumnClearedBySamePlacement() {
        // The 1x1 at (7,0) completes row 7 and column 0 simultaneously.
        val board = Bitboard.parse(
            "#.......",
            "#.......",
            "#.......",
            "#.......",
            "#.......",
            "#.......",
            "#.......",
            ".#######",
        )
        val tray = listOf(Shape.of("#"), null, null)
        val r = solver.solve(board, tray)
        val m = r.moves.single()
        assertEquals(7 to 0, m.row to m.col)
        assertEquals(1 shl 7, m.clearedRows)
        assertEquals(1, m.clearedCols)
        assertEquals(0L, m.boardAfter)
    }

    @Test
    fun orderMattersWhenAClearMakesRoom() {
        // No two empty cells are adjacent, so "####" fits nowhere until the 1x1 completes a row.
        // Row 0 is the only row with a single gap, so the only plan is 1x1 at (0,7) first.
        val board = Bitboard.parse(
            "#######.",
            ".##.##.#",
            "#.##.##.",
            "##.##.##",
            ".##.##.#",
            "#.##.##.",
            "##.##.##",
            ".##.##.#",
        )
        assertEquals(0L, Bitboard.clearMask(board))
        val four = Shape.of("####")
        val one = Shape.of("#")
        assertTrue(!four.fitsAnywhere(board.inv()))
        val tray = listOf(four, one, null)
        val r = solver.solve(board, tray)
        assertEquals(SolveStatus.OK, r.status)
        assertConsistent(board, tray, r)
        assertEquals(listOf(1, 0), r.moves.map { it.slot })
        assertEquals(0 to 7, r.moves[0].row to r.moves[0].col)
        assertEquals(1, r.moves[0].clearedRows)
        assertEquals(0, r.moves[1].row)
        // With a second 1x1 there is another plan (fill row 3's two gaps, then the 4-line there).
        val r2 = solver.solve(board, listOf(four, one, one))
        assertEquals(SolveStatus.OK, r2.status)
        assertConsistent(board, listOf(four, one, one), r2)
        assertEquals(0, r2.moves.indexOfFirst { it.slot == 0 } - 2)
    }

    @Test
    fun gameOverWhenNothingFits() {
        val checker = Bitboard.parse(
            "#.#.#.#.",
            ".#.#.#.#",
            "#.#.#.#.",
            ".#.#.#.#",
            "#.#.#.#.",
            ".#.#.#.#",
            "#.#.#.#.",
            ".#.#.#.#",
        )
        val big = Shape.of("###", "###", "###")
        val r = solver.solve(checker, listOf(big, big, big))
        assertEquals(SolveStatus.GAME_OVER, r.status)
        assertTrue(r.moves.isEmpty())
    }

    @Test
    fun gameOverSuggestsSingleBestMove() {
        val checker = Bitboard.parse(
            "#.#.#.#.",
            ".#.#.#.#",
            "#.#.#.#.",
            ".#.#.#.#",
            "#.#.#.#.",
            ".#.#.#.#",
            "#.#.#.#.",
            ".#.#.#.#",
        )
        val big = Shape.of("###", "###", "###")
        val one = Shape.of("#")
        val tray = listOf(big, one, big)
        val r = solver.solve(checker, tray)
        assertEquals(SolveStatus.GAME_OVER, r.status)
        val m = r.moves.single()
        assertEquals(1, m.slot)
        assertConsistent(checker, tray, r)
    }

    @Test
    fun gameOverDetectedEvenThoughFirstBlockFits() {
        // The 1x1 and 1x2 fit, but the 3x3 never can (no 3x3 empty area and no clear can open one).
        val board = Bitboard.parse(
            "##.##.##",
            "#.##.##.",
            ".##.##.#",
            "##.##.##",
            "#.##.##.",
            ".##.##.#",
            "##.##.#.",
            "#.##.#..",
        )
        assertEquals(0L, Bitboard.clearMask(board))
        val tray = listOf(Shape.of("#"), Shape.of("###", "###", "###"), Shape.of("##"))
        val r = solver.solve(board, tray)
        assertEquals(SolveStatus.GAME_OVER, r.status)
        assertEquals(1, r.moves.size)
        assertTrue(r.moves[0].slot != 1)
    }

    @Test
    fun matchesBruteForceOnRandomPositions() {
        val rnd = Random(2024)
        var checked = 0
        var gameOvers = 0
        repeat(150) { iteration ->
            val density = if (iteration % 10 == 0) 0.2 else rnd.nextDouble(0.35, 0.65)
            val board = Reference.randomBoard(rnd, density)
            val n = 1 + rnd.nextInt(3)
            val pieces = Reference.randomPieces(rnd, n)
            // Occasionally duplicate a piece to exercise the identical-block pruning.
            val tray: List<Shape?> = if (n == 3 && rnd.nextInt(4) == 0) listOf(pieces[0], pieces[0], pieces[2]) else pieces
            val nonNull = tray.filterNotNull()
            val expected = Reference.bestScore(board, nonNull, solver.evaluator)
            val r = solver.solve(board, tray)
            if (expected == null) {
                assertEquals(Bitboard.render(board), SolveStatus.GAME_OVER, r.status)
                gameOvers++
            } else {
                assertEquals(Bitboard.render(board) + "\n$nonNull", SolveStatus.OK, r.status)
                assertEquals(Bitboard.render(board) + "\n$nonNull", expected, r.score, 1e-9)
                assertConsistent(board, tray, r)
            }
            checked++
        }
        assertEquals(150, checked)
        println("brute-force cross-check: $checked positions, $gameOvers game-overs")
    }

    @Test
    fun monteCarloReturnsValidPlanAndIsDeterministic() {
        val mcSolver = Solver(SolverConfig(monteCarlo = true))
        val rnd = Random(99)
        repeat(20) {
            val board = Reference.randomBoard(rnd, 0.4)
            val tray = Reference.randomPieces(rnd, 3)
            val a = mcSolver.solve(board, tray)
            val b = mcSolver.solve(board, tray)
            if (a.status == SolveStatus.OK) {
                assertNotNull(a.monteCarloValue)
                assertTrue(a.monteCarloValue!! in 0.0..1.0)
                assertConsistent(board, tray, a, mcSolver.evaluator)
                assertEquals(a.moves, b.moves)
            } else {
                assertNull(a.monteCarloValue)
            }
        }
    }

    @Test
    fun weightsChangeTheChoice() {
        // With a huge roughness penalty the solver should avoid leaving a jagged surface.
        val board = Bitboard.parse(
            "........", "........", "........", "........",
            "........", "........", "........", "#.......",
        )
        val tray = listOf(Shape.of("#"), null, null)
        val smooth = Solver(SolverConfig(weights = EvalWeights(roughness = 50.0, libraryFit = 0.0)))
        val m = smooth.solve(board, tray).moves.single()
        // Next to the existing block in the corner is the smoothest spot.
        assertTrue((m.row to m.col) in setOf(6 to 0, 7 to 1))
    }
}
