package com.gridhelper.core

import com.gridhelper.solver.SolveResult
import com.gridhelper.solver.Solver
import com.gridhelper.solver.SolverConfig
import com.gridhelper.vision.BoardDetection
import com.gridhelper.vision.FrameAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the overlay needs to draw one frame. Coordinates are capture (= screen) pixels. */
data class OverlayModel(
    /** A board is on screen. */
    val boardVisible: Boolean = false,
    /** Geometry of the state the [result] belongs to. */
    val board: BoardDetection? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** Plan for the current state, null while solving or without blocks. */
    val result: SolveResult? = null,
    /** Latest analysis of any status, for the debug overlay. */
    val latest: FrameAnalysis? = null,
    val stats: PipelineStats = PipelineStats(),
)

data class PipelineStats(
    val analysedFrames: Int = 0,
    val lastAnalysisMs: Double = 0.0,
    val lastSolveMs: Double = 0.0,
    val lastStatus: String = "",
)

/**
 * Glue between recognition and the solver: tracks game state, re-solves on every change on
 * [Dispatchers.Default] (latest wins), rebuilds the solver when weights / Monte Carlo / the
 * learned shape library change, and publishes an [OverlayModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisPipeline(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val shapeStats: ShapeStatsRepository,
) {
    private val tracker = GameTracker()
    private val modelState = MutableStateFlow(OverlayModel())
    val model: StateFlow<OverlayModel> = modelState.asStateFlow()

    private data class Request(val state: GameTracker.State, val board: BoardDetection, val solver: Solver)

    private val requests = MutableStateFlow<Request?>(null)

    @Volatile
    private var solver: Solver = buildSolver()

    /** Geometry of the current state (kept to re-solve after a settings change). */
    @Volatile
    private var currentBoard: BoardDetection? = null

    fun start() {
        scope.launch {
            combine(
                settings.flow.map { it.weights to it.monteCarlo }.distinctUntilChanged(),
                shapeStats.version,
            ) { _, _ -> Unit }.collect {
                solver = buildSolver()
                val state = tracker.current
                val board = currentBoard
                if (state != null && board != null) requests.value = Request(state, board, solver)
            }
        }
        scope.launch(Dispatchers.Default) {
            requests.filterNotNull().collectLatest { req ->
                val result = req.solver.solve(req.state.board, req.state.tray)
                modelState.update { m ->
                    if (tracker.current != req.state) {
                        m
                    } else {
                        m.copy(result = result, board = req.board, stats = m.stats.copy(lastSolveMs = result.elapsedMillis))
                    }
                }
            }
        }
    }

    /** Called from the capture thread for every analysed frame. */
    fun submit(analysis: FrameAnalysis) {
        val event = tracker.onFrame(analysis.status, analysis.bitboard, analysis.shapes)
        modelState.update { m ->
            val stats = m.stats.copy(
                analysedFrames = m.stats.analysedFrames + 1,
                lastAnalysisMs = analysis.elapsedNanos / 1e6,
                lastStatus = analysis.status.name + (analysis.failure?.let { " ($it)" } ?: ""),
            )
            val base = m.copy(latest = analysis, stats = stats, frameWidth = analysis.width, frameHeight = analysis.height)
            when (event) {
                GameTracker.Event.Hidden -> base.copy(boardVisible = false)
                GameTracker.Event.Hold -> base
                GameTracker.Event.Unchanged -> base.copy(boardVisible = true)
                is GameTracker.Event.Changed -> base.copy(boardVisible = true, board = analysis.board, result = null)
            }
        }
        if (event is GameTracker.Event.Changed) {
            event.newSet?.let { shapeStats.record(it) }
            val board = analysis.board ?: return
            currentBoard = board
            requests.value = Request(event.state, board, solver)
        }
    }

    /** Hides the guides (screen off, rotation, pause) and forgets the state. */
    fun reset() {
        tracker.reset()
        currentBoard = null
        requests.value = null
        modelState.value = OverlayModel(stats = modelState.value.stats)
    }

    private fun buildSolver(): Solver {
        val s = settings.current
        return Solver(SolverConfig(weights = s.weights, library = shapeStats.library(), monteCarlo = s.monteCarlo))
    }
}
