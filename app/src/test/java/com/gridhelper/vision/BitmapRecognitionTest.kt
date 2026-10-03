package com.gridhelper.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import com.gridhelper.overlay.GuideRenderer
import com.gridhelper.solver.Bitboard
import com.gridhelper.solver.Shape
import com.gridhelper.solver.Solver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Vision on real Android bitmaps (Robolectric native graphics): decodes the screenshot
 * fixtures of the :vision module with BitmapFactory and checks bitboard + tray shapes.
 * Also verifies that the guide overlay, composited on top of the screenshot like the real
 * overlay window, does not change the recognition of the next frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BitmapRecognitionTest {

    private data class Expected(val board: Long, val tray: List<Shape?>)

    private fun fixtureNames(): List<String> {
        val listing = javaClass.classLoader!!.getResource("fixtures")
        assertNotNull("fixtures not on the test classpath", listing)
        return java.io.File(listing!!.toURI()).listFiles { f -> f.name.endsWith(".expected.txt") }!!
            .map { it.name.removeSuffix(".expected.txt") }
            .sorted()
    }

    private fun expected(name: String): Expected {
        val text = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.expected.txt")!!.bufferedReader().readText()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        val b = lines.indexOf("board")
        val t = lines.indexOf("tray")
        val board = Bitboard.parse(*lines.subList(b + 1, b + 9).toTypedArray())
        val tray = lines.subList(t + 1, t + 4).map { if (it == "-") null else Shape.fromKey(it) }
        return Expected(board, tray)
    }

    private fun decode(name: String): Bitmap {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return javaClass.classLoader!!.getResourceAsStream("fixtures/$name.png")!!.use { BitmapFactory.decodeStream(it, null, opts)!! }
    }

    @Test
    fun recognisesFixturesFromBitmaps() {
        val names = fixtureNames()
        assertTrue(names.isNotEmpty())
        for (name in names) {
            val exp = expected(name)
            val analysis = FrameAnalyzer().analyze(decode(name).toPixelSource())
            assertEquals("$name status (${analysis.failure})", AnalysisStatus.OK, analysis.status)
            assertEquals("$name board", Bitboard.render(exp.board), Bitboard.render(analysis.bitboard!!))
            assertEquals("$name tray", exp.tray, analysis.shapes)
        }
    }

    @Test
    fun overlayDoesNotDisturbRecognition() {
        for (name in fixtureNames()) {
            val bitmap = decode(name)
            val first = FrameAnalyzer().analyze(bitmap.toPixelSource())
            val board = first.board ?: continue
            val result = Solver().solve(board.bitboard, first.shapes!!)
            // Draw the guides + debug layer into a transparent layer, then composite it with the
            // window opacity cap (0.8) like the system compositor does for the overlay window.
            val layer = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val renderer = GuideRenderer(bitmap.width / 411f)
            val canvas = Canvas(layer)
            renderer.drawPlan(canvas, board.grid, result)
            renderer.drawGameOverBanner(canvas, bitmap.width.toFloat(), bitmap.height * 0.035f, result)
            renderer.drawDebug(canvas, first, listOf("debug"), android.graphics.RectF(0f, 200f, bitmap.width.toFloat(), 300f))
            val composite = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(composite).drawBitmap(layer, 0f, 0f, Paint().apply { alpha = (0.8f * 255).toInt() })
            // The overlay must actually be visible on the board (otherwise the test proves nothing).
            val g = board.grid
            var changed = 0
            for (y in g.top.toInt() until g.bottom.toInt() step 3) for (x in g.left.toInt() until g.right.toInt() step 3) {
                if (composite.getPixel(x, y) != bitmap.getPixel(x, y)) changed++
            }
            if (result.moves.isNotEmpty()) assertTrue("$name: overlay drew nothing on the board", changed > 500)
            val second = FrameAnalyzer().analyze(composite.toPixelSource())
            assertEquals("$name status with overlay (${second.failure})", AnalysisStatus.OK, second.status)
            assertEquals("$name board with overlay", Bitboard.render(board.bitboard), Bitboard.render(second.bitboard!!))
            assertEquals("$name tray with overlay", first.shapes, second.shapes)
        }
    }
}
