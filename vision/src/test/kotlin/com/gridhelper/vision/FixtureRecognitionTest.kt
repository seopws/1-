package com.gridhelper.vision

import com.gridhelper.solver.Bitboard
import com.gridhelper.vision.testing.Expectation
import com.gridhelper.vision.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the recogniser on every `fixtures/<name>.png` that has a `<name>.expected.txt` next to it.
 * Drop real screenshots + expectation files into the folder to extend the suite.
 */
class FixtureRecognitionTest {

    private fun fixtureDir(): File {
        val url = javaClass.classLoader.getResource("fixtures") ?: error("fixtures folder missing")
        return File(url.toURI())
    }

    @Test
    fun recognisesAllFixtures() {
        val dir = fixtureDir()
        val images = dir.listFiles { f -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }!!.sortedBy { it.name }
        val failures = ArrayList<String>()
        var checked = 0
        for (img in images) {
            val expFile = File(dir, img.nameWithoutExtension + ".expected.txt")
            if (!expFile.exists()) continue
            val expected = Expectation.parse(expFile.readText())
            val src = img.inputStream().use { Fixtures.load(it) }
            val analysis = FrameAnalyzer().analyze(src)
            checked++
            val board = analysis.board
            val msg = StringBuilder("${img.name}: status=${analysis.status} failure=${analysis.failure} " +
                "time=${"%.1f".format(analysis.elapsedNanos / 1e6)}ms")
            if (board == null) {
                failures += "$msg (no board, candidate=${analysis.candidate})"
                continue
            }
            val boardOk = board.bitboard == expected.board
            val trayOk = analysis.shapes == expected.tray
            msg.append(" grid=${board.grid} score=${"%.2f".format(board.gridScore)}")
            if (!boardOk) {
                msg.append("\n expected board:\n${Bitboard.render(expected.board)}\n got:\n${Bitboard.render(board.bitboard)}")
            }
            if (!trayOk) msg.append("\n expected tray ${expected.tray.map { it?.key }} got ${analysis.shapes?.map { it?.key }} cell=${analysis.tray?.cellSize}")
            if (analysis.status != AnalysisStatus.OK) msg.append("\n unexpected status")
            println(msg)
            if (!boardOk || !trayOk || analysis.status != AnalysisStatus.OK) failures += msg.toString()
        }
        assertTrue("no fixtures found in $dir", checked > 0)
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    @Test
    fun committedFixturesMatchGenerator() {
        // Guards against editing Fixtures.scenes without regenerating the PNGs.
        val dir = fixtureDir()
        for ((name, scene) in Fixtures.scenes) {
            val f = File(dir, "$name.expected.txt")
            assertTrue("missing $f (run :vision:generateFixtures)", f.exists())
            assertEquals(name, Fixtures.expectation(scene), Expectation.parse(f.readText()))
        }
    }

    @Test
    fun portraitOnlyAndNonGameScreens() {
        val landscape = IntArrayPixelSource(400, 200, IntArray(400 * 200) { 0x336699 })
        assertEquals(AnalysisStatus.NO_BOARD, FrameAnalyzer().analyze(landscape).status)
        // A plain screen (e.g. home screen wallpaper) has no board.
        val plain = IntArrayPixelSource(270, 600, IntArray(270 * 600) { i -> if ((i / 270) % 50 < 25) 0x4060D0 else 0x3050C0 })
        assertEquals(AnalysisStatus.NO_BOARD, FrameAnalyzer().analyze(plain).status)
    }
}
