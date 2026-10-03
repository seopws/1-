package com.gridhelper.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 8x8 cell grid in frame pixel coordinates. */
data class GridGeometry(val left: Float, val top: Float, val cellW: Float, val cellH: Float) {
    val right: Float get() = left + 8 * cellW
    val bottom: Float get() = top + 8 * cellH
    val cellSize: Float get() = (cellW + cellH) / 2f
    fun cellLeft(col: Int): Float = left + col * cellW
    fun cellTop(row: Int): Float = top + row * cellH
    fun cellCenterX(col: Int): Float = left + (col + 0.5f) * cellW
    fun cellCenterY(row: Int): Float = top + (row + 0.5f) * cellH
    fun cellRect(row: Int, col: Int): RectF2 =
        RectF2(cellLeft(col), cellTop(row), cellLeft(col) + cellW, cellTop(row) + cellH)
}

/** Mean colour of the central 30 % of a cell. */
data class CellSample(val rgb: Int, val value: Float, val saturation: Float)

data class BoardDetection(
    /** Bounding box of the dark board area (including its frame). */
    val outer: RectI,
    val grid: GridGeometry,
    /** 64 samples, row-major. */
    val cells: List<CellSample>,
    /** Filled cells (bit = row * 8 + col). */
    val bitboard: Long,
    /** Cells whose empty/filled decision is uncertain (e.g. drag preview, animation). */
    val ambiguous: Long,
    /** Cluster centroids as (value, saturation). */
    val emptyCentroid: Pair<Float, Float>,
    val filledCentroid: Pair<Float, Float>?,
    /** Grid alignment strength (peak / mean edge response); higher is better. */
    val gridScore: Float,
)

data class BoardSearch(
    val detection: BoardDetection?,
    /** Why detection failed (null on success). */
    val failure: String?,
    /** Best dark candidate region, also reported on failure for debugging. */
    val candidate: RectI?,
)

/**
 * Finds the 8x8 board without any hard-coded colours:
 *  1. Background luma = median of the left/right screen edge strips.
 *  2. Pixels clearly darker than the background form a mask; the largest roughly square
 *     component whose centre lies in the middle 30-70 % of the screen height is the board.
 *     Bright frame decorations (dot lights) are bridged by a morphological closing and never
 *     shrink the dark frame ring; score effects are outside the band or not square.
 *  3. The 9 vertical / horizontal grid lines are located by fitting a periodic comb to edge
 *     profiles inside the region.
 *  4. Each cell's central 30 % is averaged; the 64 (V, S) samples are split by 2-means and the
 *     darker cluster is "empty".
 */
class BoardDetector(private val params: Params = Params()) {

    data class Params(
        val searchTop: Float = 0.12f,
        val searchBottom: Float = 0.88f,
        val centerMin: Float = 0.30f,
        val centerMax: Float = 0.70f,
        val minWidthFraction: Float = 0.45f,
        val darkRatio: Float = 0.82f,
        val sampleFraction: Float = 0.30f,
    )

    fun detect(src: PixelSource): BoardSearch {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return BoardSearch(null, "empty frame", null)
        if (w > h) return BoardSearch(null, "landscape frame", null)

        val step = max(1, w / 240)
        val y0 = (h * params.searchTop).toInt()
        val y1 = (h * params.searchBottom).toInt()
        val gw = w / step
        val gh = (y1 - y0) / step
        if (gw < 16 || gh < 16) return BoardSearch(null, "frame too small", null)
        val lum = IntArray(gw * gh)
        for (gy in 0 until gh) {
            val y = y0 + gy * step + step / 2
            for (gx in 0 until gw) lum[gy * gw + gx] = ColorMath.luma(src.rgb(gx * step + step / 2, y))
        }

        val backgrounds = backgroundEstimates(src, lum, gw, gh)
        var best: RectI? = null
        var anyCandidate: RectI? = null
        for (bg in backgrounds) {
            if (bg < 20) continue // nothing can be clearly darker than an almost black background
            val threshold = (bg * params.darkRatio).roundToInt()
            val found = findBoardComponent(lum, gw, gh, threshold, step, y0, w, h)
            if (found.second != null && anyCandidate == null) anyCandidate = found.second
            if (found.first != null) {
                best = found.first
                break
            }
        }
        val outer = best ?: return BoardSearch(null, "no square dark region", anyCandidate)

        val grid = fitGrid(src, outer) ?: return BoardSearch(null, "grid lines not found", outer)
        val (geometry, score) = grid
        if (geometry.left < 0 || geometry.top < 0 || geometry.right > w || geometry.bottom > h) {
            return BoardSearch(null, "grid outside frame", outer)
        }

        val cells = sampleCells(src, geometry)
        val classification = classify(cells) ?: return BoardSearch(null, "cells not separable", outer)
        return BoardSearch(
            BoardDetection(
                outer = outer,
                grid = geometry,
                cells = cells,
                bitboard = classification.filled,
                ambiguous = classification.ambiguous,
                emptyCentroid = classification.emptyCentroid,
                filledCentroid = classification.filledCentroid,
                gridScore = score,
            ),
            null,
            outer,
        )
    }

    /** Candidate background luma values, most trusted first. */
    private fun backgroundEstimates(src: PixelSource, lum: IntArray, gw: Int, gh: Int): IntArray {
        val edge = max(1, (gw * 0.02f).roundToInt())
        val values = IntArray(gh * edge * 2)
        var n = 0
        for (gy in 0 until gh) {
            for (gx in 0 until edge) {
                values[n++] = lum[gy * gw + gx]
                values[n++] = lum[gy * gw + gw - 1 - gx]
            }
        }
        val edgeMedian = ColorMath.medianInt(values, n)
        // Fallback: a full-width strip just above the search band (board never reaches there).
        val stripY = (src.height * (params.searchTop - 0.02f)).toInt().coerceIn(0, src.height - 1)
        val strip = IntArray(src.width / 4 + 1)
        var m = 0
        var x = 0
        while (x < src.width) {
            strip[m++] = ColorMath.luma(src.rgb(x, stripY))
            x += 4
        }
        val stripMedian = ColorMath.medianInt(strip, m)
        return if (abs(stripMedian - edgeMedian) < 8) intArrayOf(edgeMedian) else intArrayOf(edgeMedian, stripMedian)
    }

    /** Returns (accepted board bbox, best candidate bbox for diagnostics). */
    private fun findBoardComponent(
        lum: IntArray, gw: Int, gh: Int, threshold: Int, step: Int, y0: Int, w: Int, h: Int,
    ): Pair<RectI?, RectI?> {
        val mask = BooleanArray(gw * gh) { lum[it] < threshold }
        val closed = Morphology.close(mask, gw, gh)
        val labels = IntArray(gw * gh)
        val queue = IntArray(gw * gh)
        var label = 0
        var accepted: RectI? = null
        var acceptedArea = 0
        var candidate: RectI? = null
        var candidateArea = 0
        for (start in closed.indices) {
            if (!closed[start] || labels[start] != 0) continue
            label++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var minX = gw
            var minY = gh
            var maxX = -1
            var maxY = -1
            var count = 0
            while (head < tail) {
                val p = queue[head++]
                val px = p % gw
                val py = p / gw
                count++
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                if (px > 0) { val q = p - 1; if (closed[q] && labels[q] == 0) { labels[q] = label; queue[tail++] = q } }
                if (px < gw - 1) { val q = p + 1; if (closed[q] && labels[q] == 0) { labels[q] = label; queue[tail++] = q } }
                if (py > 0) { val q = p - gw; if (closed[q] && labels[q] == 0) { labels[q] = label; queue[tail++] = q } }
                if (py < gh - 1) { val q = p + gw; if (closed[q] && labels[q] == 0) { labels[q] = label; queue[tail++] = q } }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val rect = RectI(minX * step, y0 + minY * step, (maxX + 1) * step, y0 + (maxY + 1) * step)
            val area = bw * bh
            if (bw * step < w * 0.25f) continue
            if (area > candidateArea) {
                candidate = rect
                candidateArea = area
            }
            val aspect = bw.toFloat() / bh
            val fill = count.toFloat() / area
            val centerY = rect.centerY / h
            val ok = bw * step >= w * params.minWidthFraction &&
                aspect in 0.85f..1.18f &&
                fill >= 0.08f &&
                centerY in params.centerMin..params.centerMax &&
                !touchesSides(minX, maxX, gw)
            if (ok && area > acceptedArea) {
                accepted = rect
                acceptedArea = area
            }
        }
        return accepted to candidate
    }

    /** The board never touches both screen edges; a full-width dark band is a background. */
    private fun touchesSides(minX: Int, maxX: Int, gw: Int) = minX == 0 && maxX == gw - 1

    /**
     * Locates the 9 grid lines along each axis. Returns geometry and alignment score.
     */
    private fun fitGrid(src: PixelSource, outer: RectI): Pair<GridGeometry, Float>? {
        val size = (outer.width + outer.height) / 2f
        val margin = (size * 0.04f).roundToInt() + 2
        val inset = (size * 0.07f).roundToInt()
        val left = (outer.left - margin).coerceAtLeast(1)
        val right = (outer.right + margin).coerceAtMost(src.width - 2)
        val top = (outer.top - margin).coerceAtLeast(1)
        val bottom = (outer.bottom + margin).coerceAtMost(src.height - 2)
        val sampleStep = max(2, (size / 200f).roundToInt())

        // Vertical lines: horizontal gradient, accumulated over rows inside the frame.
        val colProfile = FloatArray(right - left)
        val rowBuf = IntArray(right - left + 2)
        var y = outer.top + inset
        while (y < outer.bottom - inset) {
            for (i in rowBuf.indices) rowBuf[i] = ColorMath.luma(src.rgb(left - 1 + i, y))
            for (i in colProfile.indices) colProfile[i] += abs(rowBuf[i + 2] - rowBuf[i]).toFloat()
            y += sampleStep
        }
        // Horizontal lines: vertical gradient, accumulated over columns inside the frame.
        val rowProfile = FloatArray(bottom - top)
        val colBuf = IntArray(bottom - top + 2)
        var x = outer.left + inset
        while (x < outer.right - inset) {
            for (i in colBuf.indices) colBuf[i] = ColorMath.luma(src.rgb(x, top - 1 + i))
            for (i in rowProfile.indices) rowProfile[i] += abs(colBuf[i + 2] - colBuf[i]).toFloat()
            x += sampleStep
        }

        val fx = fitAxis(colProfile, left, outer.left, outer.right) ?: return null
        val fy = fitAxis(rowProfile, top, outer.top, outer.bottom) ?: return null
        // Cells are square: if the axes disagree a lot one of the fits locked onto noise.
        val ratio = fx.cell / fy.cell
        val (gx, gy) = if (ratio in 0.93f..1.07f) {
            fx to fy
        } else if (fx.score >= fy.score) {
            fx to (fitAxis(rowProfile, top, outer.top, outer.bottom, fx.cell) ?: return null)
        } else {
            (fitAxis(colProfile, left, outer.left, outer.right, fy.cell) ?: return null) to fy
        }
        val score = min(gx.score, gy.score)
        if (score < 1.15f) return null
        return GridGeometry(gx.start, gy.start, gx.cell, gy.cell) to score
    }

    private class AxisFit(val start: Float, val cell: Float, val score: Float)

    /**
     * Fits 9 equally spaced lines `start + k * cell` (k = 0..8) to [profile] (index 0 = pixel
     * [base]). The grid must lie inside [lo, hi] (the dark region) give or take a few percent.
     */
    private fun fitAxis(profile: FloatArray, base: Int, lo: Int, hi: Int, fixedCell: Float? = null): AxisFit? {
        val span = (hi - lo).toFloat()
        if (span < 40f || profile.isEmpty()) return null
        val tol = max(1, (span / 8f * 0.03f).roundToInt())
        val dil = FloatArray(profile.size)
        for (i in profile.indices) {
            var m = 0f
            for (j in max(0, i - tol)..min(profile.size - 1, i + tol)) if (profile[j] > m) m = profile[j]
            dil[i] = m
        }
        val mean = (profile.sum() / profile.size).coerceAtLeast(1e-3f)
        val center = (lo + hi) / 2f
        val cMin = fixedCell?.times(0.985f) ?: (span * 0.78f / 8f)
        val cMax = fixedCell?.times(1.015f) ?: (span * 1.04f / 8f)
        var best: AxisFit? = null
        var bestValue = Float.NEGATIVE_INFINITY
        var c = cMin
        while (c <= cMax) {
            val gridLen = 8 * c
            val sMin = lo - span * 0.04f
            val sMax = hi + span * 0.04f - gridLen
            var s = sMin
            while (s <= sMax) {
                var sum = 0f
                for (k in 0..8) {
                    val idx = (s + k * c - base).roundToInt()
                    if (idx in dil.indices) sum += dil[idx]
                }
                val strength = sum / (9f * mean)
                val offCenter = abs(s + gridLen / 2f - center) / span
                val value = strength - 2.0f * offCenter
                if (value > bestValue) {
                    bestValue = value
                    best = AxisFit(s, c, strength)
                }
                s += 0.5f
            }
            c += 0.2f
        }
        return best
    }

    private fun sampleCells(src: PixelSource, g: GridGeometry): List<CellSample> {
        val half = g.cellSize * params.sampleFraction / 2f
        val st = max(1, (half * 2 / 8f).toInt())
        val out = ArrayList<CellSample>(64)
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val cx = g.cellCenterX(c)
                val cy = g.cellCenterY(r)
                var sr = 0L
                var sg = 0L
                var sb = 0L
                var n = 0
                var yy = (cy - half).toInt()
                val yEnd = (cy + half).toInt()
                while (yy <= yEnd) {
                    var xx = (cx - half).toInt()
                    val xEnd = (cx + half).toInt()
                    while (xx <= xEnd) {
                        val p = src.rgb(xx, yy)
                        sr += ColorMath.r(p)
                        sg += ColorMath.g(p)
                        sb += ColorMath.b(p)
                        n++
                        xx += st
                    }
                    yy += st
                }
                val mean = ColorMath.rgb((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
                out += CellSample(mean, ColorMath.value(mean), ColorMath.saturation(mean))
            }
        }
        return out
    }

    private class Classification(
        val filled: Long,
        val ambiguous: Long,
        val emptyCentroid: Pair<Float, Float>,
        val filledCentroid: Pair<Float, Float>?,
    )

    /** 2-means over (value, saturation); the darker / less saturated cluster is empty. */
    private fun classify(cells: List<CellSample>): Classification? {
        val v = FloatArray(64) { cells[it].value }
        val s = FloatArray(64) { cells[it].saturation }
        var lo = 0
        var hi = 0
        for (i in 1 until 64) {
            if (v[i] < v[lo]) lo = i
            if (v[i] > v[hi]) hi = i
        }
        var c0v = v[lo]
        var c0s = s[lo]
        var c1v = v[hi]
        var c1s = s[hi]
        val assign = BooleanArray(64) // true = cluster 1
        for (iteration in 0 until 12) {
            var changed = false
            for (i in 0 until 64) {
                val d0 = dist(v[i], s[i], c0v, c0s)
                val d1 = dist(v[i], s[i], c1v, c1s)
                val a = d1 < d0
                if (a != assign[i]) {
                    assign[i] = a
                    changed = true
                }
            }
            var n0 = 0
            var n1 = 0
            var sv0 = 0f
            var ss0 = 0f
            var sv1 = 0f
            var ss1 = 0f
            for (i in 0 until 64) {
                if (assign[i]) { n1++; sv1 += v[i]; ss1 += s[i] } else { n0++; sv0 += v[i]; ss0 += s[i] }
            }
            if (n0 > 0) { c0v = sv0 / n0; c0s = ss0 / n0 }
            if (n1 > 0) { c1v = sv1 / n1; c1s = ss1 / n1 }
            if (!changed && iteration > 0) break
        }
        val separated = abs(c1v - c0v) >= 0.12f || abs(c1s - c0s) >= 0.25f
        if (!separated) {
            // One cluster: an empty board (start of a game). A uniformly bright board is not a board.
            val meanV = v.average().toFloat()
            val meanS = s.average().toFloat()
            return if (meanV <= 0.6f) Classification(0L, 0L, meanV to meanS, null) else null
        }
        // Cluster with lower "darkness score" is empty.
        val emptyIsZero = (c0v + 0.25f * c0s) <= (c1v + 0.25f * c1s)
        val ev = if (emptyIsZero) c0v else c1v
        val es = if (emptyIsZero) c0s else c1s
        val fv = if (emptyIsZero) c1v else c0v
        val fs = if (emptyIsZero) c1s else c0s
        val centroidDist = dist(ev, es, fv, fs)
        var filled = 0L
        var ambiguous = 0L
        for (i in 0 until 64) {
            val de = dist(v[i], s[i], ev, es)
            val df = dist(v[i], s[i], fv, fs)
            if (df < de) filled = filled or (1L shl i)
            if (abs(de - df) < 0.25f * centroidDist) ambiguous = ambiguous or (1L shl i)
        }
        return Classification(filled, ambiguous, ev to es, fv to fs)
    }

    private fun dist(v1: Float, s1: Float, v2: Float, s2: Float): Float {
        val dv = v1 - v2
        val ds = s1 - s2
        return kotlin.math.sqrt(dv * dv + 0.35f * ds * ds)
    }
}

internal object Morphology {
    /** 3x3 dilation followed by 3x3 erosion (bridges 1-2 px gaps such as dot lights or grid lines). */
    fun close(mask: BooleanArray, w: Int, h: Int): BooleanArray = erode(dilate(mask, w, h), w, h)

    fun dilate(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var v = false
            loop@ for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    if (mask[yy * w + xx]) { v = true; break@loop }
                }
            }
            out[y * w + x] = v
        }
        return out
    }

    fun erode(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var v = true
            loop@ for (dy in -1..1) {
                val yy = (y + dy).coerceIn(0, h - 1)
                for (dx in -1..1) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    if (!mask[yy * w + xx]) { v = false; break@loop }
                }
            }
            out[y * w + x] = v
        }
        return out
    }
}
