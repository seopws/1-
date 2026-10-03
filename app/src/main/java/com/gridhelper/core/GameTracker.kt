package com.gridhelper.core

import com.gridhelper.solver.Shape
import com.gridhelper.vision.AnalysisStatus

/**
 * Turns per-frame recognition results into game-state changes. Pure Kotlin (unit tested).
 *
 * - The board always changes when a block is placed, so "same board, fewer tray blocks" means
 *   the user is dragging a block: keep the current recommendation instead of re-solving.
 * - A fresh set of three blocks is reported once (for the local shape statistics).
 */
class GameTracker {

    data class State(val board: Long, val tray: List<Shape?>) {
        val pieces: Int get() = tray.count { it != null }
    }

    sealed interface Event {
        /** No board on screen (not the game, menu, popup...). */
        data object Hidden : Event

        /** Frame not trustworthy (animation, drag preview, ambiguous cells): keep what is shown. */
        data object Hold : Event

        /** Same state as before: just make sure the guides are visible. */
        data object Unchanged : Event

        /** New state to solve. [newSet] is set when a fresh set of blocks was dealt. */
        data class Changed(val state: State, val newSet: List<Shape>?) : Event
    }

    @Volatile
    var current: State? = null
        private set
    private var lastRecordedSet: List<Shape?>? = null

    @Synchronized
    fun onFrame(status: AnalysisStatus, board: Long?, tray: List<Shape?>?): Event {
        if (status == AnalysisStatus.NO_BOARD || board == null) return Event.Hidden
        if (status == AnalysisStatus.UNRELIABLE || tray == null) return Event.Hold
        val next = State(board, tray)
        val prev = current
        if (next == prev) return Event.Unchanged
        if (prev != null && next.board == prev.board && next.pieces < prev.pieces) return Event.Hold
        val fresh = next.pieces == 3 && (prev == null || prev.pieces < 3 || prev.tray != next.tray) &&
            next.tray != lastRecordedSet
        current = next
        return if (fresh) {
            lastRecordedSet = next.tray
            Event.Changed(next, next.tray.filterNotNull())
        } else {
            Event.Changed(next, null)
        }
    }

    @Synchronized
    fun reset() {
        current = null
    }
}
