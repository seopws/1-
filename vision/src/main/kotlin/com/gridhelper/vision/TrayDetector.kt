package com.gridhelper.vision

import com.gridhelper.solver.Shape
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class TraySlot(
    val index: Int,
    /** Search region of the slot. */
    val region: RectI,
    /** Recognised block, or null when the slot is empty (block already placed). */
    val shape: Shape?,
    /** Pixel bounding box of the block. */
    val bounds: RectI?,
    /** True if some cell had a coverage near 50 % (unreliable). */
    val uncertain: Boolean,
)

data class TrayDetection(
    val slots: List<TraySlot>,
    /** Estimated size of one tray cell in pixels. */
    val cellSize: Float,
    /** Search area below the board. */
    val area: RectI,
) {
    val shapes: List<Shape?> get() = slots.map { it.shape }
    val uncertain: Boolean get() = slots.any { it.uncertain }
}

/**
 * Recognises the three blocks below the board.
 *  - Area: from just below the board to ~85 % of the screen height, split into three slots
 *    across the board width.
 *  - Background colour is estimated per row from the screen edge strips (handles gradients).
 *  - Pixels far from the background form a mask; small connected components (sparkles, noise)
 *    are discarded, the remaining ones of a slot form the block (also handles diagonal blocks).
 *  - The tray cell size is fitted jointly over all blocks so that every bounding box is an
 *    integer number of cells, with a prior of ~0.45 x board cell.
 *  - Each cell of the block's grid is filled if more than half of its centre is block pixels.
 */
class TrayDetector(private val params: Params = Params()) {

    data class Params(
        val bottomFraction: Float = 0.85f,
        val cellPrior: Float = 0.45f,
        val minCellRatio: Float = 0.28f,
        val maxCellRatio: Float = 0.70f,
        val colorThreshold: Float = 36f,
    )

    fun detect(src: PixelSource, board: BoardDetection): TrayDetection {
        val g = board.grid
        val boardCell = g.cellSize
        val outer = board.outer
        // Blocks may sit right under the frame; start just below it.
        val top = (max(g.bottom, outer.bottom.toFloat()) + 2f).roundToInt()
        val bottom = max(src.height * params.bottomFraction, top + boardCell * 3.2f)
            .coerceAtMost(src.height * 0.97f).roundToInt()
        // Slightly wider than the board: a 5-wide block can overhang the outer slots.
        val left = (outer.left - boardCell * 0.3f).roundToInt().coerceAtLeast(0)
        val right = (outer.right + boardCell * 0.3f).roundToInt().coerceAtMost(src.width)
        val area = RectI(left, top, right, bottom)
        if (area.height < boardCell || area.width < boardCell * 3) {
            return TrayDetection(List(3) { TraySlot(it, area, null, null, false) }, boardCell * params.cellPrior, area)
        }

        val bg = BackgroundModel(src, top, bottom)
        val priorCell = boardCell * params.cellPrior
        val step = max(1, (priorCell / 10f).roundToInt())
        // Slots are the thirds of the board width.
        val regions = List(3) { i ->
            RectI(outer.left + outer.width * i / 3, top, outer.left + outer.width * (i + 1) / 3, bottom)
        }

        // Pass 1: block bounding box per slot.
        val boxes = findBlockBounds(src, bg, area, outer, step, priorCell)

        // Pass 2: joint cell size estimate.
        val cell = estimateCellSize(src, bg, boxes.filterNotNull(), boardCell)

        // Pass 3: rasterise each block.
        val slots = regions.mapIndexed { i, region ->
            val box = boxes[i]
            if (box == null) {
                TraySlot(i, region, null, null, false)
            } else {
                rasterise(src, bg, i, region, box, cell)
            }
        }
        return TrayDetection(slots, cell, area)
    }

    /** Per-row background colour from narrow strips at both screen edges. */
    private inner class BackgroundModel(src: PixelSource, private val top: Int, bottom: Int) {
        private val colors: IntArray

        init {
            val strip = max(2, (src.width * 0.025f).roundToInt())
            val buf = IntArray(strip * 2)
            colors = IntArray(bottom - top) { i ->
                val y = top + i
                var n = 0
                var x = 0
                while (x < strip) {
                    buf[n++] = src.rgb(x, y)
                    buf[n++] = src.rgb(src.width - 1 - x, y)
                    x += max(1, strip / 4)
                }
                ColorMath.median(buf, n)
            }
        }

        fun at(y: Int): Int = colors[(y - top).coerceIn(0, colors.size - 1)]
    }

    private fun isBlock(src: PixelSource, bg: BackgroundModel, x: Int, y: Int): Boolean =
        ColorMath.distance(src.rgb(x, y), bg.at(y)) > params.colorThreshold

    /**
     * Connected components of the block mask over the whole tray area; components that are too
     * small are noise. Each remaining component is assigned to the slot containing its centre,
     * so wide blocks that cross a slot third and diagonal blocks (several components) both work.
     */
    private fun findBlockBounds(
        src: PixelSource, bg: BackgroundModel, area: RectI, outer: RectI, step: Int, priorCell: Float,
    ): Array<RectI?> {
        val result = arrayOfNulls<RectI>(3)
        val gw = area.width / step
        val gh = area.height / step
        if (gw <= 0 || gh <= 0) return result
        val mask = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            val y = area.top + gy * step + step / 2
            for (gx in 0 until gw) mask[gy * gw + gx] = isBlock(src, bg, area.left + gx * step + step / 2, y)
        }
        val minArea = max(2f, 0.15f * (priorCell / step) * (priorCell / step)).toInt()
        val labels = IntArray(gw * gh)
        val queue = IntArray(gw * gh)
        val minX = IntArray(3) { Int.MAX_VALUE }
        val minY = IntArray(3) { Int.MAX_VALUE }
        val maxX = IntArray(3) { -1 }
        val maxY = IntArray(3) { -1 }
        var label = 0
        for (start in mask.indices) {
            if (!mask[start] || labels[start] != 0) continue
            label++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var cMinX = gw
            var cMinY = gh
            var cMaxX = -1
            var cMaxY = -1
            var count = 0
            while (head < tail) {
                val p = queue[head++]
                val px = p % gw
                val py = p / gw
                count++
                cMinX = min(cMinX, px); cMaxX = max(cMaxX, px)
                cMinY = min(cMinY, py); cMaxY = max(cMaxY, py)
                for (dy in -1..1) {
                    val ny = py + dy
                    if (ny < 0 || ny >= gh) continue
                    for (dx in -1..1) {
                        val nx = px + dx
                        if (nx < 0 || nx >= gw) continue
                        val q = ny * gw + nx
                        if (mask[q] && labels[q] == 0) {
                            labels[q] = label
                            queue[tail++] = q
                        }
                    }
                }
            }
            if (count < minArea) continue
            // Components hugging the left/right edge of the area are decoration, not blocks.
            if (cMinX == 0 || cMaxX == gw - 1) continue
            val centerX = area.left + (cMinX + cMaxX + 1) * step / 2f
            val slot = ((centerX - outer.left) * 3 / outer.width).toInt().coerceIn(0, 2)
            minX[slot] = min(minX[slot], cMinX); maxX[slot] = max(maxX[slot], cMaxX)
            minY[slot] = min(minY[slot], cMinY); maxY[slot] = max(maxY[slot], cMaxY)
        }
        for (slot in 0 until 3) {
            if (maxX[slot] < 0) continue
            val coarse = RectI(
                area.left + minX[slot] * step,
                area.top + minY[slot] * step,
                area.left + (maxX[slot] + 1) * step,
                area.top + (maxY[slot] + 1) * step,
            )
            result[slot] = refine(src, bg, coarse, step, area)
        }
        return result
    }

    /** Moves each edge of [coarse] to the exact first/last pixel column/row with block pixels. */
    private fun refine(src: PixelSource, bg: BackgroundModel, coarse: RectI, step: Int, region: RectI): RectI {
        val probe = max(1, step / 2)
        fun colHas(x: Int): Boolean {
            var y = coarse.top
            var hits = 0
            while (y < coarse.bottom) {
                if (isBlock(src, bg, x, y) && ++hits >= 2) return true
                y += probe
            }
            return false
        }
        fun rowHas(y: Int): Boolean {
            var x = coarse.left
            var hits = 0
            while (x < coarse.right) {
                if (isBlock(src, bg, x, y) && ++hits >= 2) return true
                x += probe
            }
            return false
        }
        var l = coarse.left
        while (l > region.left && colHas(l - 1)) l--
        while (l < coarse.right - 1 && !colHas(l)) l++
        var r = coarse.right
        while (r < region.right && colHas(r)) r++
        while (r > l + 1 && !colHas(r - 1)) r--
        var t = coarse.top
        while (t > region.top && rowHas(t - 1)) t--
        while (t < coarse.bottom - 1 && !rowHas(t)) t++
        var b = coarse.bottom
        while (b < region.bottom && rowHas(b)) b++
        while (b > t + 1 && !rowHas(b - 1)) b--
        return RectI(l, t, r, b)
    }

    /**
     * Joint cell size: every block's width and height should be a whole number (1..5) of cells,
     * with a prior of ~0.45 x board cell. A single block is ambiguous (3 cells of 70 px look like
     * 4 cells of 52 px), so the best few candidates are rasterised and the one whose cells are
     * most clearly filled or empty (block bevels align with true cell borders) wins.
     */
    private fun estimateCellSize(src: PixelSource, bg: BackgroundModel, boxes: List<RectI>, boardCell: Float): Float {
        val prior = boardCell * params.cellPrior
        if (boxes.isEmpty()) return prior
        val cStart = boardCell * params.minCellRatio
        val cEnd = boardCell * params.maxCellRatio
        val n = ((cEnd - cStart) / 0.25f).toInt() + 1
        val costs = FloatArray(n) { i ->
            val c = cStart + i * 0.25f
            var cost = 0f
            for (b in boxes) cost += integerError(b.width / c) + integerError(b.height / c)
            cost / boxes.size + 0.35f * abs(c - prior) / prior
        }
        // Local minima, best first, at least 10 % apart.
        val minima = (0 until n).filter { i ->
            (i == 0 || costs[i] <= costs[i - 1]) && (i == n - 1 || costs[i] <= costs[i + 1])
        }.sortedBy { costs[it] }
        val candidates = ArrayList<Float>()
        for (i in minima) {
            val c = refineCell(cStart + i * 0.25f, boxes)
            if (candidates.none { abs(it - c) / c < 0.10f }) candidates += c
            if (candidates.size == 3) break
        }
        if (candidates.size == 1) return candidates[0]
        var best = candidates[0]
        var bestScore = Float.MAX_VALUE
        for (c in candidates) {
            var ambiguity = 0f
            var fit = 0f
            for (b in boxes) {
                ambiguity += coverage(src, bg, b, c).let { cov -> cov.sumOf { min(it, 1f - it).toDouble() }.toFloat() / cov.size }
                fit += integerError(b.width / c) + integerError(b.height / c)
            }
            val score = 3f * ambiguity / boxes.size + fit / boxes.size + 0.35f * abs(c - prior) / prior
            if (score < bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    /** Average of width/k and height/k over all blocks for cell size [c]. */
    private fun refineCell(c: Float, boxes: List<RectI>): Float {
        var sum = 0f
        var n = 0
        for (b in boxes) {
            val kw = (b.width / c).roundToInt().coerceIn(1, Shape.MAX_DIM)
            val kh = (b.height / c).roundToInt().coerceIn(1, Shape.MAX_DIM)
            sum += b.width.toFloat() / kw + b.height.toFloat() / kh
            n += 2
        }
        return if (n > 0) sum / n else c
    }

    private fun integerError(x: Float): Float {
        val k = x.roundToInt()
        if (k < 1) return 1f
        if (k > Shape.MAX_DIM) return 1f + (k - Shape.MAX_DIM)
        return abs(x - k)
    }

    /** Coverage (fraction of block pixels in the central half) of every cell, row-major. */
    private fun coverage(src: PixelSource, bg: BackgroundModel, box: RectI, cell: Float): FloatArray {
        val cols = (box.width / cell).roundToInt().coerceIn(1, Shape.MAX_DIM)
        val rows = (box.height / cell).roundToInt().coerceIn(1, Shape.MAX_DIM)
        val cw = box.width.toFloat() / cols
        val ch = box.height.toFloat() / rows
        val st = max(1, (min(cw, ch) / 12f).toInt())
        return FloatArray(rows * cols) { i ->
            val r = i / cols
            val c = i % cols
            val cx = box.left + (c + 0.5f) * cw
            val cy = box.top + (r + 0.5f) * ch
            val hx = cw * 0.25f
            val hy = ch * 0.25f
            var hits = 0
            var total = 0
            var y = (cy - hy).toInt()
            while (y <= (cy + hy).toInt()) {
                var x = (cx - hx).toInt()
                while (x <= (cx + hx).toInt()) {
                    if (isBlock(src, bg, x, y)) hits++
                    total++
                    x += st
                }
                y += st
            }
            hits.toFloat() / total
        }
    }

    private fun rasterise(src: PixelSource, bg: BackgroundModel, index: Int, region: RectI, box: RectI, cell: Float): TraySlot {
        val cols = (box.width / cell).roundToInt().coerceIn(1, Shape.MAX_DIM)
        val rows = (box.height / cell).roundToInt().coerceIn(1, Shape.MAX_DIM)
        val cov = coverage(src, bg, box, cell)
        val matrix = Array(rows) { r -> BooleanArray(cols) { c -> cov[r * cols + c] >= 0.5f } }
        val uncertain = cov.any { it in 0.3f..0.7f }
        if (matrix.none { row -> row.any { it } }) return TraySlot(index, region, null, null, true)
        return TraySlot(index, region, Shape.fromMatrix(matrix), box, uncertain)
    }
}
