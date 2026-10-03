package com.gridhelper.solver

enum class SolveStatus {
    /** All remaining blocks can be placed; [SolveResult.moves] holds the full plan. */
    OK,

    /** No order places every remaining block. [SolveResult.moves] holds at most one best move. */
    GAME_OVER,

    /** Nothing to place (empty tray). */
    NO_PIECES,
}

/**
 * One recommended placement.
 *
 * @property slot tray slot (0..2) of the block.
 * @property row top row of the shape's bounding box.
 * @property col left column of the shape's bounding box.
 * @property cellsMask cells covered by the block on the board.
 * @property clearedRows bit r set if row r is cleared by this move.
 * @property clearedCols bit c set if column c is cleared by this move.
 * @property boardBefore board before the move.
 * @property boardAfter board after the move and its line clears.
 */
data class Move(
    val slot: Int,
    val shape: Shape,
    val row: Int,
    val col: Int,
    val cellsMask: Long,
    val clearedRows: Int,
    val clearedCols: Int,
    val boardBefore: Long,
    val boardAfter: Long,
) {
    val linesCleared: Int get() = Integer.bitCount(clearedRows) + Integer.bitCount(clearedCols)
}

data class SolveResult(
    val status: SolveStatus,
    val moves: List<Move>,
    /** Final score used for ranking (static + Monte Carlo term if enabled). */
    val score: Double,
    /** Static part of the score (clear rewards + leaf evaluation). */
    val staticScore: Double,
    /** Monte Carlo value in 0..1 (expected fraction of the next set that fits), null if not used. */
    val monteCarloValue: Double?,
    val elapsedNanos: Long,
    /** Number of leaves evaluated by the exhaustive search. */
    val leaves: Long,
) {
    val finalBoard: Long? get() = moves.lastOrNull()?.boardAfter
    val elapsedMillis: Double get() = elapsedNanos / 1_000_000.0
}
