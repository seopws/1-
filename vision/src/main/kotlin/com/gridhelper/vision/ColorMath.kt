package com.gridhelper.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal object ColorMath {
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    /** Rec.601 luma in 0..255. */
    fun luma(c: Int): Int = (r(c) * 299 + g(c) * 587 + b(c) * 114) / 1000

    /** HSV value in 0..1. */
    fun value(c: Int): Float = max(r(c), max(g(c), b(c))) / 255f

    /** HSV saturation in 0..1. */
    fun saturation(c: Int): Float {
        val mx = max(r(c), max(g(c), b(c)))
        if (mx == 0) return 0f
        val mn = min(r(c), min(g(c), b(c)))
        return (mx - mn).toFloat() / mx
    }

    /** Euclidean RGB distance in 0..~441. */
    fun distance(a: Int, b: Int): Float {
        val dr = r(a) - r(b)
        val dg = g(a) - g(b)
        val db = ColorMath.b(a) - ColorMath.b(b)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    fun rgb(r: Int, g: Int, b: Int): Int = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Per-channel median of [colors] (first [n] entries). Sorts copies; n is small. */
    fun median(colors: IntArray, n: Int = colors.size): Int {
        if (n == 0) return 0
        val rs = IntArray(n) { r(colors[it]) }.also { it.sort() }
        val gs = IntArray(n) { g(colors[it]) }.also { it.sort() }
        val bs = IntArray(n) { b(colors[it]) }.also { it.sort() }
        return rgb(rs[n / 2], gs[n / 2], bs[n / 2])
    }

    fun medianInt(values: IntArray, n: Int = values.size): Int {
        if (n == 0) return 0
        val copy = values.copyOf(n)
        copy.sort()
        return copy[n / 2]
    }
}
