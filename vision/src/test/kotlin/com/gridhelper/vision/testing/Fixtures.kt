package com.gridhelper.vision.testing

import com.gridhelper.solver.Bitboard
import com.gridhelper.solver.Shape
import com.gridhelper.solver.ShapeLibrary
import com.gridhelper.solver.SolveStatus
import com.gridhelper.solver.Solver
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/** Expected recognition result of a screenshot fixture. */
data class Expectation(val board: Long, val tray: List<Shape?>) {

    fun serialize(): String = buildString {
        appendLine("// GridHelper fixture expectation: '#' filled, '.' empty; tray: shape key or '-' = empty slot")
        appendLine("board")
        appendLine(Bitboard.render(board))
        appendLine("tray")
        tray.forEach { appendLine(it?.key ?: "-") }
    }

    companion object {
        fun parse(text: String): Expectation {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
            val b = lines.indexOf("board")
            val t = lines.indexOf("tray")
            require(b >= 0 && t > b) { "malformed expectation" }
            val board = Bitboard.parse(*lines.subList(b + 1, b + 9).toTypedArray())
            val tray = lines.subList(t + 1, t + 4).map { if (it == "-") null else Shape.fromKey(it) }
            return Expectation(board, tray)
        }
    }
}

object Fixtures {

    /** Plays [turns] turns of random sets with the solver: realistic, game-like boards. */
    fun selfPlayBoard(seed: Int, turns: Int): Long {
        val rnd = Random(seed)
        val lib = ShapeLibrary.default()
        val solver = Solver()
        var board = 0L
        repeat(turns) {
            val tray = List(3) { lib.shapes[rnd.nextInt(lib.size)] }
            val r = solver.solve(board, tray)
            if (r.status != SolveStatus.OK) return board
            board = r.finalBoard!!
        }
        return board
    }

    /** Random dense board without completed lines. */
    fun denseBoard(seed: Int, density: Double): Long {
        val rnd = Random(seed)
        var b = 0L
        for (i in 0 until 64) if (rnd.nextDouble() < density) b = b or (1L shl i)
        return b and Bitboard.clearMask(b).inv()
    }

    private fun s(key: String) = Shape.fromKey(key)

    /** The committed screenshot fixtures (regenerate with [FixtureGenerator]). */
    val scenes: Map<String, Scene> by lazy {
        linkedMapOf(
            "classic_1080x2400_mid" to Scene(
                1080, 2400, Theme.CLASSIC, denseBoard(1, 0.45),
                listOf(s("#./#./##"), s("###/###/###"), s("#####")),
            ),
            "classic_1080x2340_sparse" to Scene(
                1080, 2340, Theme.CLASSIC, selfPlayBoard(2, 9),
                listOf(s("#"), s("##/##"), null), seed = 2,
            ),
            "classic_1080x2400_empty" to Scene(
                1080, 2400, Theme.CLASSIC, 0L,
                listOf(s("##/#."), s("#/#/#/#/#"), s(".#./###")), seed = 3,
            ),
            "classic_1080x1920_16x9" to Scene(
                1080, 1920, Theme.CLASSIC, denseBoard(4, 0.35),
                listOf(s(".#./###"), null, null), boardCenterY = 0.48f, trayScale = 0.5f, seed = 4,
            ),
            "classic_1080x2400_greyed" to Scene(
                1080, 2400, Theme.CLASSIC, denseBoard(5, 0.6),
                listOf(s("###/#../#.."), s("#/#"), s("####")), greyedSlots = setOf(0), seed = 5,
            ),
            "grayyellow_1080x2400" to Scene(
                1080, 2400, Theme.GRAY_YELLOW, denseBoard(6, 0.5),
                listOf(s("###/.#."), s("#/#/#/#"), s("##")), seed = 6,
            ),
            "grayyellow_720x1520" to Scene(
                720, 1520, Theme.GRAY_YELLOW, selfPlayBoard(7, 14),
                listOf(s("##/.#"), s("###/###"), s(".##/##.")), trayScale = 0.42f, seed = 7,
            ),
            "nightlights_1440x3120" to Scene(
                1440, 3120, Theme.NIGHT_LIGHTS, denseBoard(8, 0.4),
                listOf(s("#./.#"), s("##/##/##"), s("###/#../#..")), seed = 8,
            ),
            "nightlights_1080x2400_full" to Scene(
                1080, 2400, Theme.NIGHT_LIGHTS, denseBoard(9, 0.78),
                listOf(s("#"), s("#/#/#"), null), seed = 9,
            ),
            "mint_720x1600" to Scene(
                720, 1600, Theme.MINT, denseBoard(10, 0.55),
                listOf(s("#####"), s("#/#"), s("##./.##")), seed = 10,
            ),
        )
    }

    fun expectation(scene: Scene) = Expectation(scene.board, scene.tray)

    /** Loads an image (PNG/JPEG) as an ARGB pixel source. */
    fun load(stream: InputStream): com.gridhelper.vision.IntArrayPixelSource {
        val img = ImageIO.read(stream) ?: error("unreadable image")
        return toPixelSource(img)
    }

    fun toPixelSource(img: java.awt.image.BufferedImage): com.gridhelper.vision.IntArrayPixelSource {
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return com.gridhelper.vision.IntArrayPixelSource(img.width, img.height, px)
    }
}

/**
 * Writes the fixture PNGs and their `.expected.txt` files.
 * Run from the repository root: `./gradlew :vision:generateFixtures`.
 */
object FixtureGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "vision/src/test/resources/fixtures")
        dir.mkdirs()
        for ((name, scene) in Fixtures.scenes) {
            ImageIO.write(ScreenRenderer.render(scene), "png", File(dir, "$name.png"))
            File(dir, "$name.expected.txt").writeText(Fixtures.expectation(scene).serialize())
            println("wrote $name")
        }
    }
}
