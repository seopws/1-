package com.gridhelper.solver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShapeAndEvaluatorTest {

    @Test
    fun shapeIsNormalised() {
        val s = Shape.fromCells(listOf(3 to 4, 3 to 5, 4 to 4))
        assertEquals(2, s.rows)
        assertEquals(2, s.cols)
        assertEquals("##/#.", s.key)
        assertEquals(s, Shape.fromKey("##/#."))
        assertNotEquals(s, Shape.fromKey("##/.#"))
        assertEquals(3, s.cellCount)
    }

    @Test
    fun placementCounts() {
        assertEquals(64, Shape.of("#").placements.size)
        assertEquals(36, Shape.of("###", "###", "###").placements.size)
        assertEquals(32, Shape.of("#####").placements.size)
        assertEquals(32, Shape.of("#", "#", "#", "#", "#").placements.size)
        // Shape placed in the bottom-right corner.
        val sq = Shape.of("##", "##")
        val corner = sq.maskAt(6, 6)
        assertEquals(Bitboard.bit(6, 6) or Bitboard.bit(6, 7) or Bitboard.bit(7, 6) or Bitboard.bit(7, 7), corner)
    }

    @Test
    fun defaultLibraryHasUniqueShapes() {
        val keys = ShapeLibrary.DEFAULT_SHAPES.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(ShapeLibrary.DEFAULT_SHAPES.all { it.rows <= Shape.MAX_DIM && it.cols <= Shape.MAX_DIM })
    }

    @Test
    fun libraryFromCountsAddsUnknownShapes() {
        val lib = ShapeLibrary.fromCounts(mapOf("#./.#" to 5, "##" to 10))
        assertEquals(ShapeLibrary.DEFAULT_SHAPES.size + 1, lib.size)
        assertEquals(Shape.fromKey("##"), lib.shapes.first()) // highest weight first
        assertEquals(1.0, lib.weights.sum(), 1e-9)
    }

    @Test
    fun fitsAnywhereMatchesReference() {
        val rnd = Random(7)
        val shapes = ShapeLibrary.DEFAULT_SHAPES + Shape.fromKey("#./.#") + Shape.fromKey("..#/.#./#..")
        repeat(3000) {
            val b = Reference.randomBoard(rnd, rnd.nextDouble(0.2, 0.9))
            for (s in shapes) assertEquals("${s.key}\n${Bitboard.render(b)}", Reference.fits(s, b), s.fitsAnywhere(b.inv()))
        }
    }

    @Test
    fun holesAndRoughnessMatchReference() {
        val rnd = Random(11)
        repeat(20_000) {
            val b = Reference.randomBoard(rnd, rnd.nextDouble(0.1, 0.95))
            assertEquals(Bitboard.render(b), Reference.holes(b), Evaluator.countHoles(b.inv()))
            assertEquals(Reference.roughness(b), Evaluator.roughness(b))
        }
    }

    @Test
    fun holeExamples() {
        val b = Bitboard.parse(
            ".#######", // (0,0) single hole against the walls
            "########",
            "##..####", // horizontal 2-cell hole
            "########",
            "####.###", // vertical 2-cell hole
            "####.###",
            "#...####", // 3-cell region: not a hole
            "########",
        )
        assertEquals(3, Evaluator.countHoles(b.inv()))
        assertEquals(0, Evaluator.countHoles(0L.inv()))
    }

    @Test
    fun evaluatorPrefersOpenBoards() {
        val ev = Evaluator(EvalWeights.DEFAULT, ShapeLibrary.default())
        val open = Bitboard.parse(
            "........", "........", "........", "........",
            "........", "........", "##......", "###.....",
        )
        val holey = Bitboard.parse(
            ".#.#.#.#", "#.#.#.#.", "........", "........",
            "........", "........", "........", "........",
        )
        val fOpen = ev.features(open)
        assertTrue(fOpen.fits3x3 && fOpen.fits1x5 && fOpen.fits5x1)
        assertEquals(1.0, fOpen.libraryFitRatio, 1e-9)
        assertTrue(ev.evaluate(open) > ev.evaluate(holey))
        assertFalse(ev.features(Bitboard.parse(
            "#.#.#.#.", ".#.#.#.#", "#.#.#.#.", ".#.#.#.#",
            "#.#.#.#.", ".#.#.#.#", "#.#.#.#.", ".#.#.#.#",
        )).fits3x3)
    }

    @Test
    fun stepRewardHasMultiLineBonus() {
        val w = EvalWeights(clearLine = 10.0, multiClear = 12.0)
        val ev = Evaluator(w, ShapeLibrary.default())
        assertEquals(0.0, ev.stepReward(0), 0.0)
        assertEquals(10.0, ev.stepReward(1), 0.0)
        assertEquals(20.0 + 12.0, ev.stepReward(2), 0.0)
        assertEquals(30.0 + 24.0, ev.stepReward(3), 0.0)
    }
}
