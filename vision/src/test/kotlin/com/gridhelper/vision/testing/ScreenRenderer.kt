package com.gridhelper.vision.testing

import com.gridhelper.solver.Bitboard
import com.gridhelper.solver.Shape
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import kotlin.random.Random

/** Colour scheme of a synthetic game screen. */
data class Theme(
    val name: String,
    val bgTop: Color,
    val bgBottom: Color,
    val frame: Color,
    val emptyCell: Color,
    val gridLine: Color,
    val blockColors: List<Color>,
    val dotLights: Color? = null,
) {
    companion object {
        val CLASSIC = Theme(
            "classic",
            bgTop = Color(70, 112, 216), bgBottom = Color(46, 78, 178),
            frame = Color(28, 38, 88), emptyCell = Color(34, 46, 102), gridLine = Color(22, 30, 70),
            blockColors = listOf(
                Color(232, 72, 72), Color(246, 152, 42), Color(250, 208, 52), Color(82, 200, 92),
                Color(60, 200, 232), Color(72, 122, 242), Color(162, 92, 222),
            ),
        )
        val GRAY_YELLOW = Theme(
            "grayyellow",
            bgTop = Color(126, 126, 130), bgBottom = Color(98, 98, 102),
            frame = Color(50, 50, 54), emptyCell = Color(62, 62, 66), gridLine = Color(42, 42, 46),
            blockColors = listOf(Color(248, 204, 40)),
        )
        val NIGHT_LIGHTS = Theme(
            "nightlights",
            bgTop = Color(52, 38, 112), bgBottom = Color(30, 22, 72),
            frame = Color(14, 10, 34), emptyCell = Color(22, 18, 52), gridLine = Color(10, 8, 26),
            blockColors = listOf(
                Color(255, 92, 140), Color(255, 180, 60), Color(120, 230, 120),
                Color(80, 210, 255), Color(190, 120, 255),
            ),
            dotLights = Color(255, 232, 150),
        )
        val MINT = Theme(
            "mint",
            bgTop = Color(156, 220, 204), bgBottom = Color(118, 188, 172),
            frame = Color(38, 78, 78), emptyCell = Color(50, 94, 94), gridLine = Color(30, 64, 64),
            blockColors = listOf(
                Color(240, 96, 80), Color(255, 196, 70), Color(90, 150, 240), Color(250, 250, 250),
                Color(186, 110, 230),
            ),
        )
        val ALL = listOf(CLASSIC, GRAY_YELLOW, NIGHT_LIGHTS, MINT)
    }
}

data class Scene(
    val width: Int,
    val height: Int,
    val theme: Theme,
    val board: Long,
    val tray: List<Shape?>,
    val boardWidthFraction: Float = 0.92f,
    val boardCenterY: Float = 0.47f,
    val trayScale: Float = 0.45f,
    val effects: Boolean = true,
    /** Tray slots drawn greyed out ("does not fit" style). */
    val greyedSlots: Set<Int> = emptySet(),
    val seed: Int = 1,
)

/** Draws a block-puzzle-like portrait screenshot. Only used to produce test fixtures. */
object ScreenRenderer {

    fun render(scene: Scene): BufferedImage {
        val w = scene.width
        val h = scene.height
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val rnd = Random(scene.seed)
        val t = scene.theme

        // Background gradient.
        g.paint = GradientPaint(0f, 0f, t.bgTop, 0f, h.toFloat(), t.bgBottom)
        g.fillRect(0, 0, w, h)

        drawStatusBar(g, w, h)
        drawHeader(g, w, h, rnd)

        val outer = w * scene.boardWidthFraction
        val bx = (w - outer) / 2f
        val by = h * scene.boardCenterY - outer / 2f
        val pad = outer * 0.025f
        val grid = outer - 2 * pad
        val cell = grid / 8f

        if (scene.effects) drawEffects(g, w, h, by, rnd)

        // Frame.
        g.color = t.frame
        g.fill(RoundRectangle2D.Float(bx, by, outer, outer, outer * 0.035f, outer * 0.035f))
        // Grid lines + cells.
        val gx = bx + pad
        val gy = by + pad
        g.color = t.gridLine
        g.fill(java.awt.geom.Rectangle2D.Float(gx, gy, grid, grid))
        val lw = (cell * 0.035f).coerceAtLeast(1f)
        for (r in 0 until 8) for (c in 0 until 8) {
            val x = gx + c * cell
            val y = gy + r * cell
            if (Bitboard.isSet(scene.board, r, c)) {
                drawBlock(g, x, y, cell, t.blockColors[(r * 3 + c * 5 + scene.seed) % t.blockColors.size])
            } else {
                g.color = t.emptyCell
                g.fill(java.awt.geom.Rectangle2D.Float(x + lw, y + lw, cell - 2 * lw, cell - 2 * lw))
            }
        }
        t.dotLights?.let { drawDotLights(g, bx, by, outer, pad, cell, it) }

        // Tray.
        val boardBottom = by + outer
        val trayCell = cell * scene.trayScale
        // The tray zone always starts below the board (room for a 5-tall block).
        val trayCenterY = maxOf(boardBottom + (h * 0.85f - boardBottom) * 0.45f, boardBottom + cell * 0.3f + trayCell * 2.5f)
        scene.tray.forEachIndexed { i, shape ->
            if (shape == null) return@forEachIndexed
            val cx = bx + (i + 0.5f) * outer / 3f
            val left = cx - shape.cols * trayCell / 2f
            val top = trayCenterY - shape.rows * trayCell / 2f
            val baseColor = t.blockColors[(i * 2 + scene.seed) % t.blockColors.size]
            // "Does not fit" blocks are drawn dimmed/desaturated, still distinct from the background.
            val color = if (i in scene.greyedSlots) blend(baseColor, Color(96, 96, 104), 0.6f) else baseColor
            for ((r, c) in shape.cells()) drawBlock(g, left + c * trayCell, top + r * trayCell, trayCell, color)
        }

        drawNavBar(g, w, h)
        g.dispose()
        return img
    }

    private fun blend(c: Color, with: Color, f: Float) = Color(
        (c.red + (with.red - c.red) * f).roundToInt(),
        (c.green + (with.green - c.green) * f).roundToInt(),
        (c.blue + (with.blue - c.blue) * f).roundToInt(),
    )

    /** Bevelled block: light top, dark bottom, shaded sides, flat centre face. */
    fun drawBlock(g: Graphics2D, x: Float, y: Float, s: Float, base: Color) {
        val b = s * 0.14f
        fun poly(vararg pts: Float): Polygon {
            val p = Polygon()
            var i = 0
            while (i < pts.size) {
                p.addPoint(pts[i].roundToInt(), pts[i + 1].roundToInt())
                i += 2
            }
            return p
        }
        g.color = blend(base, Color.WHITE, 0.45f)
        g.fillPolygon(poly(x, y, x + s, y, x + s - b, y + b, x + b, y + b))
        g.color = blend(base, Color.BLACK, 0.35f)
        g.fillPolygon(poly(x, y + s, x + s, y + s, x + s - b, y + s - b, x + b, y + s - b))
        g.color = blend(base, Color.WHITE, 0.2f)
        g.fillPolygon(poly(x, y, x + b, y + b, x + b, y + s - b, x, y + s))
        g.color = blend(base, Color.BLACK, 0.2f)
        g.fillPolygon(poly(x + s, y, x + s - b, y + b, x + s - b, y + s - b, x + s, y + s))
        g.color = base
        g.fill(java.awt.geom.Rectangle2D.Float(x + b, y + b, s - 2 * b, s - 2 * b))
    }

    private fun drawStatusBar(g: Graphics2D, w: Int, h: Int) {
        val sb = (h * 0.035f).roundToInt()
        g.color = Color(0, 0, 0, 60)
        g.fillRect(0, 0, w, sb)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (sb * 0.5f).roundToInt())
        g.drawString("12:30", (w * 0.05f).roundToInt(), (sb * 0.7f).roundToInt())
        g.fillRect((w * 0.86f).roundToInt(), (sb * 0.3f).roundToInt(), (w * 0.06f).roundToInt(), (sb * 0.4f).roundToInt())
    }

    private fun drawHeader(g: Graphics2D, w: Int, h: Int, rnd: Random) {
        // Crown + best score (top-left), gear (top-right), big score (centre).
        val y = h * 0.07f
        g.color = Color(255, 200, 40)
        val cw = w * 0.07f
        val x0 = w * 0.06f
        g.fillPolygon(
            intArrayOf(x0.toInt(), (x0 + cw * 0.25f).toInt(), (x0 + cw * 0.5f).toInt(), (x0 + cw * 0.75f).toInt(), (x0 + cw).toInt(), (x0 + cw).toInt(), x0.toInt()),
            intArrayOf(y.toInt(), (y + cw * 0.3f).toInt(), (y - cw * 0.1f).toInt(), (y + cw * 0.3f).toInt(), y.toInt(), (y + cw * 0.7f).toInt(), (y + cw * 0.7f).toInt()),
            7,
        )
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (w * 0.05f).roundToInt())
        g.drawString("${2000 + rnd.nextInt(8000)}", (x0 + cw * 1.3f).roundToInt(), (y + cw * 0.6f).roundToInt())
        g.color = Color(255, 255, 255, 200)
        g.fill(Ellipse2D.Float(w * 0.86f, y - cw * 0.1f, cw, cw))
        g.color = Color.WHITE
        val f = Font(Font.SANS_SERIF, Font.BOLD, (w * 0.12f).roundToInt())
        g.font = f
        val text = "${rnd.nextInt(100, 9999)}"
        val tw = g.fontMetrics.stringWidth(text)
        g.drawString(text, (w - tw) / 2, (h * 0.19f).roundToInt())
    }

    /** Particles and a combo word above the board (must be ignored by the detector). */
    private fun drawEffects(g: Graphics2D, w: Int, h: Int, boardTop: Float, rnd: Random) {
        val colors = listOf(Color(255, 240, 120), Color(255, 120, 200), Color(120, 255, 220), Color.WHITE)
        repeat(40) {
            g.color = colors[rnd.nextInt(colors.size)]
            val r = w * rnd.nextFloat() * 0.012f + 2f
            val x = w * rnd.nextFloat()
            val y = h * 0.10f + (boardTop - h * 0.10f - r * 2 - h * 0.01f) * rnd.nextFloat()
            g.fill(Ellipse2D.Float(x, y, r, r))
        }
        val f = Font(Font.SANS_SERIF, Font.BOLD or Font.ITALIC, (w * 0.08f).roundToInt())
        g.font = f
        val text = "Great!"
        val tw = g.fontMetrics.stringWidth(text)
        val ty = (boardTop - h * 0.02f).roundToInt()
        g.color = Color(120, 40, 0)
        g.stroke = BasicStroke(w * 0.006f)
        g.drawString(text, (w - tw) / 2 + 3, ty + 3)
        g.color = Color(255, 160, 40)
        g.drawString(text, (w - tw) / 2, ty)
    }

    private fun drawDotLights(g: Graphics2D, bx: Float, by: Float, outer: Float, pad: Float, cell: Float, color: Color) {
        val r = pad * 0.32f
        val inset = pad * 0.5f
        val spacing = cell * 0.5f
        g.color = color
        var d = spacing / 2
        while (d < outer - inset * 2) {
            for ((x, y) in listOf(
                bx + inset + d to by + inset,
                bx + inset + d to by + outer - inset,
                bx + inset to by + inset + d,
                bx + outer - inset to by + inset + d,
            )) {
                g.fill(Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r))
            }
            d += spacing
        }
    }

    private fun drawNavBar(g: Graphics2D, w: Int, h: Int) {
        g.color = Color(255, 255, 255, 150)
        val bw = w * 0.3f
        g.fillRoundRect(((w - bw) / 2).roundToInt(), (h * 0.985f).roundToInt(), bw.roundToInt(), (h * 0.005f).roundToInt().coerceAtLeast(3), 6, 6)
    }
}
