package com.gridhelper.core

import android.content.Context
import android.content.SharedPreferences
import com.gridhelper.solver.EvalWeights
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /**
     * Window opacity of the guide overlay. Android 12+ only lets touches pass through an
     * untrusted overlay whose opacity is at most 0.8, so the value is capped there.
     */
    val overlayOpacity: Float = 0.7f,
    val monteCarlo: Boolean = false,
    val weights: EvalWeights = EvalWeights.DEFAULT,
    val debugMode: Boolean = false,
    /** Guides on/off, toggled by tapping the floating bubble. */
    val guidesVisible: Boolean = true,
) {
    companion object {
        const val MIN_OPACITY = 0.2f
        const val MAX_OPACITY = 0.8f
    }
}

/** SharedPreferences-backed settings exposed as a [StateFlow]. */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val flow: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    @Synchronized
    fun edit(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value).let {
            it.copy(overlayOpacity = it.overlayOpacity.coerceIn(AppSettings.MIN_OPACITY, AppSettings.MAX_OPACITY))
        }
        write(next)
        state.value = next
    }

    private fun read(): AppSettings {
        val d = EvalWeights.DEFAULT
        fun f(key: String, def: Double) = prefs.getFloat(key, def.toFloat()).toDouble()
        return AppSettings(
            overlayOpacity = prefs.getFloat(KEY_OPACITY, 0.7f),
            monteCarlo = prefs.getBoolean(KEY_MC, false),
            debugMode = prefs.getBoolean(KEY_DEBUG, false),
            guidesVisible = prefs.getBoolean(KEY_GUIDES, true),
            weights = EvalWeights(
                clearLine = f("w_clear", d.clearLine),
                multiClear = f("w_multi", d.multiClear),
                emptyCell = f("w_empty", d.emptyCell),
                hole = f("w_hole", d.hole),
                fit3x3 = f("w_fit3x3", d.fit3x3),
                fit1x5 = f("w_fit1x5", d.fit1x5),
                fit5x1 = f("w_fit5x1", d.fit5x1),
                libraryFit = f("w_library", d.libraryFit),
                roughness = f("w_rough", d.roughness),
                monteCarlo = f("w_mc", d.monteCarlo),
            ),
        )
    }

    private fun write(s: AppSettings) {
        val w = s.weights
        prefs.edit()
            .putFloat(KEY_OPACITY, s.overlayOpacity)
            .putBoolean(KEY_MC, s.monteCarlo)
            .putBoolean(KEY_DEBUG, s.debugMode)
            .putBoolean(KEY_GUIDES, s.guidesVisible)
            .putFloat("w_clear", w.clearLine.toFloat())
            .putFloat("w_multi", w.multiClear.toFloat())
            .putFloat("w_empty", w.emptyCell.toFloat())
            .putFloat("w_hole", w.hole.toFloat())
            .putFloat("w_fit3x3", w.fit3x3.toFloat())
            .putFloat("w_fit1x5", w.fit1x5.toFloat())
            .putFloat("w_fit5x1", w.fit5x1.toFloat())
            .putFloat("w_library", w.libraryFit.toFloat())
            .putFloat("w_rough", w.roughness.toFloat())
            .putFloat("w_mc", w.monteCarlo.toFloat())
            .apply()
    }

    private companion object {
        const val KEY_OPACITY = "overlay_opacity"
        const val KEY_MC = "monte_carlo"
        const val KEY_DEBUG = "debug_mode"
        const val KEY_GUIDES = "guides_visible"
    }
}
