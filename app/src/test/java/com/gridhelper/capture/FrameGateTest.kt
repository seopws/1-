package com.gridhelper.capture

import com.gridhelper.vision.FrameSignature
import com.gridhelper.vision.IntArrayPixelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGateTest {

    private fun frame(shade: Int): FrameSignature {
        val w = 64
        val h = 128
        val px = IntArray(w * h) { i -> if ((i / w) in 40..80) (shade shl 16) or (shade shl 8) or shade else 0x3050C0 }
        return FrameSignature.of(IntArrayPixelSource(w, h, px))
    }

    @Test
    fun throttlesToInterval() {
        val gate = FrameGate(333)
        assertTrue(gate.shouldSample(1_000))
        assertFalse(gate.shouldSample(1_100))
        assertFalse(gate.shouldSample(1_332))
        assertTrue(gate.shouldSample(1_333))
    }

    @Test
    fun requiresTwoIdenticalFramesAndSkipsUnchanged() {
        val gate = FrameGate()
        assertEquals(FrameGate.Decision.WAIT_FOR_STABLE, gate.offer(frame(10)))
        assertEquals(FrameGate.Decision.ANALYZE, gate.offer(frame(10)))
        assertEquals(FrameGate.Decision.UNCHANGED, gate.offer(frame(10)))
        // Animation: every frame differs -> never analysed.
        assertEquals(FrameGate.Decision.WAIT_FOR_STABLE, gate.offer(frame(60)))
        assertEquals(FrameGate.Decision.WAIT_FOR_STABLE, gate.offer(frame(120)))
        // Settled.
        assertEquals(FrameGate.Decision.ANALYZE, gate.offer(frame(120)))
        assertEquals(FrameGate.Decision.UNCHANGED, gate.offer(frame(120)))
    }

    @Test
    fun resetForgetsHistory() {
        val gate = FrameGate()
        gate.offer(frame(10))
        gate.offer(frame(10))
        gate.reset()
        assertEquals(FrameGate.Decision.WAIT_FOR_STABLE, gate.offer(frame(10)))
        assertEquals(FrameGate.Decision.ANALYZE, gate.offer(frame(10)))
    }
}
