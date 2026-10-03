package com.gridhelper.capture

import com.gridhelper.vision.FrameSignature

/**
 * Decides which captured frames get analysed. Pure Kotlin (unit tested).
 *
 *  - At most one frame per [intervalMillis] is looked at (3 fps by default).
 *  - A frame whose 32x64 signature equals the last analysed one is skipped (nothing changed).
 *  - A changed frame is analysed only once the next sampled frame has the same signature,
 *    i.e. two consecutive identical frames: block move / line-clear animations are over.
 */
class FrameGate(intervalMillis: Long = ACTIVE_INTERVAL_MS) {

    /** Sampling interval; raised to [IDLE_INTERVAL_MS] while no game board is on screen. */
    @Volatile
    var intervalMillis: Long = intervalMillis

    enum class Decision { UNCHANGED, WAIT_FOR_STABLE, ANALYZE }

    private var lastSampleAt = Long.MIN_VALUE / 2
    private var previous: FrameSignature? = null
    private var lastAnalysed: FrameSignature? = null

    /** Cheap check before computing a signature. */
    @Synchronized
    fun shouldSample(nowMillis: Long): Boolean {
        if (nowMillis - lastSampleAt < intervalMillis) return false
        lastSampleAt = nowMillis
        return true
    }

    @Synchronized
    fun offer(signature: FrameSignature): Decision {
        val decision = when {
            signature.isSameAs(lastAnalysed) -> Decision.UNCHANGED
            signature.isSameAs(previous) -> Decision.ANALYZE
            else -> Decision.WAIT_FOR_STABLE
        }
        previous = signature
        if (decision == Decision.ANALYZE) lastAnalysed = signature
        return decision
    }

    companion object {
        const val ACTIVE_INTERVAL_MS = 333L
        const val IDLE_INTERVAL_MS = 1000L
    }

    /** Forget history so the next stable frame is analysed again (resume, settings change). */
    @Synchronized
    fun reset() {
        previous = null
        lastAnalysed = null
        lastSampleAt = Long.MIN_VALUE / 2
    }
}
