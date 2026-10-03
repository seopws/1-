package com.gridhelper.vision

import com.gridhelper.solver.Bitboard
import com.gridhelper.solver.Shape
import com.gridhelper.solver.ShapeLibrary
import com.gridhelper.vision.testing.Fixtures
import com.gridhelper.vision.testing.Scene
import com.gridhelper.vision.testing.ScreenRenderer
import com.gridhelper.vision.testing.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Renders many random scenes in memory (theme, resolution, board position/size, tray scale,
 * board contents, tray blocks incl. diagonals and empty slots) and measures exact-match accuracy.
 */
class SyntheticSweepTest {

    private val resolutions = listOf(1080 to 2400, 1080 to 2340, 1080 to 1920, 720 to 1600, 1440 to 3200, 1260 to 2800)
    private val extraShapes = listOf("#./.#", ".#/#.", "#../.#./..#").map { Shape.fromKey(it) }

    @Test
    fun randomScenes() {
        val rnd = Random(31337)
        val lib = ShapeLibrary.default().shapes + extraShapes
        val total = 120
        var boardOk = 0
        var trayOk = 0
        var statusOk = 0
        val failures = ArrayList<String>()
        val times = ArrayList<Double>()
        repeat(total) { i ->
            val (w, h) = resolutions[rnd.nextInt(resolutions.size)]
            val theme = Theme.ALL[rnd.nextInt(Theme.ALL.size)]
            val board = when (rnd.nextInt(4)) {
                0 -> 0L
                1 -> Fixtures.selfPlayBoard(i, 3 + rnd.nextInt(20))
                else -> Fixtures.denseBoard(i, rnd.nextDouble(0.2, 0.8))
            }
            val tray = List(3) { if (rnd.nextInt(6) == 0) null else lib[rnd.nextInt(lib.size)] }
            val scene = Scene(
                w, h, theme, board, tray,
                boardWidthFraction = rnd.nextDouble(0.86, 0.95).toFloat(),
                boardCenterY = rnd.nextDouble(0.42, 0.52).toFloat(),
                trayScale = rnd.nextDouble(0.40, 0.55).toFloat(),
                effects = rnd.nextBoolean(),
                greyedSlots = if (rnd.nextInt(5) == 0) setOf(rnd.nextInt(3)) else emptySet(),
                seed = i,
            )
            val image = ScreenRenderer.render(scene)
            val src = Fixtures.toPixelSource(image)
            val a = FrameAnalyzer().analyze(src)
            times += a.elapsedNanos / 1e6
            val bOk = a.bitboard == board
            val tOk = a.shapes == tray
            if (bOk) boardOk++
            if (tOk) trayOk++
            if (a.status == AnalysisStatus.OK) statusOk++
            if (!bOk || !tOk) {
                // Keep failing renders for inspection.
                val out = java.io.File("build/sweep-failures").apply { mkdirs() }
                javax.imageio.ImageIO.write(image, "png", java.io.File(out, "case$i.png"))
                failures += "#$i ${theme.name} ${w}x$h bw=${"%.2f".format(scene.boardWidthFraction)} ts=${"%.2f".format(scene.trayScale)} " +
                    "status=${a.status} ${a.failure ?: ""}" +
                    (if (!bOk) "\n board exp/got:\n${Bitboard.render(board)}\n--\n${a.bitboard?.let { Bitboard.render(it) }}" else "") +
                    (if (!tOk) "\n tray exp ${tray.map { it?.key }} got ${a.shapes?.map { it?.key }} cell=${a.tray?.cellSize} boardCell=${a.board?.grid?.cellSize} " +
                        "bounds=${a.tray?.slots?.map { it.bounds }} area=${a.tray?.area}" else "")
            }
        }
        times.sort()
        println("sweep: board $boardOk/$total, tray $trayOk/$total, status OK $statusOk/$total, " +
            "avg ${"%.1f".format(times.average())} ms, p95 ${"%.1f".format(times[(times.size * 0.95).toInt()])} ms")
        failures.take(10).forEach { println(it) }
        assertEquals("board accuracy", total, boardOk)
        assertTrue("tray accuracy $trayOk/$total", trayOk >= total * 0.98)
    }

    @Test
    fun signatureDetectsChangesAndIgnoresNothing() {
        val scene = Scene(1080, 2400, Theme.CLASSIC, Fixtures.denseBoard(3, 0.4), listOf(Shape.of("#"), null, null))
        val a = FrameSignature.of(Fixtures.toPixelSource(ScreenRenderer.render(scene)))
        val b = FrameSignature.of(Fixtures.toPixelSource(ScreenRenderer.render(scene)))
        assertTrue(a.isSameAs(b))
        assertEquals(a.hash, b.hash)
        // Placing a 1x1 block (tray slot empties, one board cell fills) must be detected.
        val freeCell = (0 until 64).first { scene.board and (1L shl it) == 0L }
        val after = scene.copy(board = scene.board or (1L shl freeCell), tray = listOf(null, null, null))
        val c = FrameSignature.of(Fixtures.toPixelSource(ScreenRenderer.render(after)))
        assertTrue("differences=${a.differences(c)}", !a.isSameAs(c))
    }
}
