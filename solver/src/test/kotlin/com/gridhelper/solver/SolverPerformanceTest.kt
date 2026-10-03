package com.gridhelper.solver

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Rough timing benchmark. Targets on a phone: ~20 ms without and ~150 ms with Monte Carlo.
 * The assertions are deliberately loose (desktop JVM, shared CI machines); the printed numbers
 * are what matters when tuning.
 */
class SolverPerformanceTest {

    private fun measure(label: String, solver: Solver, cases: List<Pair<Long, List<Shape>>>): Pair<Double, Double> {
        // Warm-up so the JIT has compiled the hot loops.
        repeat(3) { for ((b, t) in cases.take(30)) solver.solve(b, t) }
        val times = cases.map { (b, t) -> solver.solve(b, t).elapsedMillis }.sorted()
        val avg = times.average()
        val p95 = times[(times.size * 0.95).toInt().coerceAtMost(times.size - 1)]
        println("%-28s avg %6.2f ms  p95 %6.2f ms  max %6.2f ms".format(label, avg, p95, times.last()))
        return avg to p95
    }

    private fun cases(seed: Int, count: Int, density: () -> Double): List<Pair<Long, List<Shape>>> {
        val rnd = Random(seed)
        return List(count) { Reference.randomBoard(rnd, density()) to Reference.randomPieces(rnd, 3) }
    }

    @Test
    fun exhaustiveSearchIsFast() {
        val solver = Solver()
        val rnd = Random(5)
        val (midAvg, _) = measure("mid-game (30-50% filled)", solver, cases(1, 200) { rnd.nextDouble(0.3, 0.5) })
        measure("open board (10-25% filled)", solver, cases(2, 100) { rnd.nextDouble(0.1, 0.25) })
        val (emptyAvg, _) = measure("empty board", solver, cases(3, 50) { 0.0 })
        assertTrue("mid-game average $midAvg ms", midAvg < 60.0)
        assertTrue("empty-board average $emptyAvg ms", emptyAvg < 250.0)
    }

    @Test
    fun monteCarloStaysWithinBudget() {
        val solver = Solver(SolverConfig(monteCarlo = true))
        val rnd = Random(6)
        val (avg, p95) = measure("mid-game + Monte Carlo", solver, cases(4, 80) { rnd.nextDouble(0.3, 0.5) })
        assertTrue("Monte Carlo average $avg ms", avg < 200.0)
        assertTrue("Monte Carlo p95 $p95 ms", p95 < 400.0)
    }

    /**
     * Self-play: realistic boards come from actually playing. Deals random sets, plays the
     * recommended plan, and records solve times. Also a sanity check that the solver survives.
     */
    @Test
    fun selfPlayTimings() {
        for (mc in listOf(false, true)) {
            val solver = Solver(SolverConfig(monteCarlo = mc))
            val rnd = Random(77)
            val times = ArrayList<Double>()
            var turns = 0
            var lines = 0
            repeat(if (mc) 5 else 10) {
                var board = 0L
                var alive = true
                var turn = 0
                while (alive && turn < 60) {
                    val tray = Reference.randomPieces(rnd, 3)
                    val r = solver.solve(board, tray)
                    times += r.elapsedMillis
                    if (r.status != SolveStatus.OK) {
                        alive = false
                    } else {
                        lines += r.moves.sumOf { it.linesCleared }
                        board = r.finalBoard!!
                        turn++
                    }
                }
                turns += turn
            }
            times.sort()
            println(
                "self-play mc=%-5s turns %4d lines %4d  avg %6.2f ms  p95 %6.2f ms  max %6.2f ms".format(
                    mc, turns, lines, times.average(), times[(times.size * 0.95).toInt()], times.last(),
                ),
            )
            assertTrue(turns > 0)
        }
    }
}
