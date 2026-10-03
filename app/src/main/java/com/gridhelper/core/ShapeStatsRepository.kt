package com.gridhelper.core

import android.content.Context
import com.gridhelper.solver.Shape
import com.gridhelper.solver.ShapeLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local frequency table of recognised tray blocks (shape key -> count). Feeds the evaluator's
 * "library fit" term and the Monte Carlo sampler so they match what the game really deals.
 * Stored in a private SharedPreferences file; nothing leaves the device.
 */
class ShapeStatsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("shape_stats", Context.MODE_PRIVATE)
    private val counts = HashMap<String, Int>()
    private val versionState = MutableStateFlow(0)

    /** Incremented whenever the library changes noticeably (every [REBUILD_EVERY] sets). */
    val version: StateFlow<Int> = versionState.asStateFlow()

    init {
        for ((k, v) in prefs.all) if (v is Int) counts[k] = v
    }

    @Synchronized
    fun record(shapes: List<Shape>) {
        if (shapes.isEmpty()) return
        val e = prefs.edit()
        for (s in shapes) {
            val n = (counts[s.key] ?: 0) + 1
            counts[s.key] = n
            e.putInt(s.key, n)
        }
        val sets = prefs.getInt(KEY_SETS, 0) + 1
        e.putInt(KEY_SETS, sets)
        e.apply()
        if (sets % REBUILD_EVERY == 0) versionState.value = versionState.value + 1
    }

    @Synchronized
    fun snapshot(): Map<String, Int> = HashMap(counts).apply { remove(KEY_SETS) }

    fun totalSets(): Int = prefs.getInt(KEY_SETS, 0)

    fun library(): ShapeLibrary = ShapeLibrary.fromCounts(snapshot())

    @Synchronized
    fun clear() {
        counts.clear()
        prefs.edit().clear().apply()
        versionState.value = versionState.value + 1
    }

    private companion object {
        /** Not a valid shape key, so it never collides with one. */
        const val KEY_SETS = "__sets__"
        const val REBUILD_EVERY = 10
    }
}
