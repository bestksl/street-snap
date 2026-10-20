package dev.streetsnap.app.composition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T76: the gate covers only the short AF/AE hunt after an explicit focus request. */
class FocusDisturbanceGateTest {

    private var nowMs = 10_000L
    private val gate = FocusDisturbanceGate(graceMs = 1500L, now = { nowMs })

    @Test
    fun quietBeforeAnyEngagement() {
        assertFalse(gate.isDisturbing())
    }

    @Test
    fun disturbingRightAtEngagementAndInsideGrace() {
        gate.engage()
        assertTrue(gate.isDisturbing())
        nowMs += 1499L
        assertTrue(gate.isDisturbing())
    }

    @Test
    fun settlesAfterGraceWindow() {
        gate.engage()
        nowMs += 1500L
        assertFalse(gate.isDisturbing())
        nowMs += 5_000L
        assertFalse(gate.isDisturbing())
    }

    @Test
    fun reEngageExtendsTheWindow() {
        gate.engage()
        nowMs += 1_000L
        gate.engage()
        nowMs += 1_000L
        // 2,000 ms after the first tap but only 1,000 ms after the second.
        assertTrue(gate.isDisturbing())
        nowMs += 501L
        assertFalse(gate.isDisturbing())
    }

    @Test
    fun resetDropsTheWindowImmediately() {
        gate.engage()
        gate.reset()
        assertFalse(gate.isDisturbing())
    }

    @Test
    fun staleTimestampsFromThePastDoNotReadAsDisturbing() {
        nowMs = 5_000L
        gate.engage()
        nowMs = 4_000L
        // Clock moved backwards (session rebind): negative delta must not suppress clears.
        assertFalse(gate.isDisturbing())
    }
}
