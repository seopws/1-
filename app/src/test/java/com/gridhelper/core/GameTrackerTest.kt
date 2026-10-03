package com.gridhelper.core

import com.gridhelper.solver.Shape
import com.gridhelper.vision.AnalysisStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTrackerTest {

    private val a = Shape.of("##")
    private val b = Shape.of("#", "#")
    private val c = Shape.of("###")

    @Test
    fun noBoardHidesAndUnreliableHolds() {
        val t = GameTracker()
        assertEquals(GameTracker.Event.Hidden, t.onFrame(AnalysisStatus.NO_BOARD, null, null))
        assertEquals(GameTracker.Event.Hold, t.onFrame(AnalysisStatus.UNRELIABLE, 0L, listOf(a, b, c)))
        assertNull(t.current)
    }

    @Test
    fun freshSetIsReportedOnce() {
        val t = GameTracker()
        val e1 = t.onFrame(AnalysisStatus.OK, 0L, listOf(a, b, c))
        assertTrue(e1 is GameTracker.Event.Changed)
        assertEquals(listOf(a, b, c), (e1 as GameTracker.Event.Changed).newSet)
        assertEquals(GameTracker.Event.Unchanged, t.onFrame(AnalysisStatus.OK, 0L, listOf(a, b, c)))
        // Leaving the game and coming back does not count the same set again.
        assertEquals(GameTracker.Event.Hidden, t.onFrame(AnalysisStatus.NO_BOARD, null, null))
        assertEquals(GameTracker.Event.Unchanged, t.onFrame(AnalysisStatus.OK, 0L, listOf(a, b, c)))
    }

    @Test
    fun placingABlockChangesBoardAndTray() {
        val t = GameTracker()
        t.onFrame(AnalysisStatus.OK, 0L, listOf(a, b, c))
        val e = t.onFrame(AnalysisStatus.OK, 0b11L, listOf(null, b, c))
        assertTrue(e is GameTracker.Event.Changed)
        assertNull((e as GameTracker.Event.Changed).newSet)
        assertEquals(2, t.current!!.pieces)
    }

    @Test
    fun draggingABlockIsIgnored() {
        val t = GameTracker()
        t.onFrame(AnalysisStatus.OK, 0L, listOf(a, b, c))
        // Block lifted from the tray, board unchanged: user is dragging.
        assertEquals(GameTracker.Event.Hold, t.onFrame(AnalysisStatus.OK, 0L, listOf(a, null, c)))
        assertEquals(3, t.current!!.pieces)
    }

    @Test
    fun newSetAfterLastBlock() {
        val t = GameTracker()
        t.onFrame(AnalysisStatus.OK, 0L, listOf(a, b, c))
        t.onFrame(AnalysisStatus.OK, 1L, listOf(null, b, c))
        t.onFrame(AnalysisStatus.OK, 3L, listOf(null, null, c))
        val e = t.onFrame(AnalysisStatus.OK, 7L, listOf(c, a, a))
        assertEquals(listOf(c, a, a), (e as GameTracker.Event.Changed).newSet)
    }
}
