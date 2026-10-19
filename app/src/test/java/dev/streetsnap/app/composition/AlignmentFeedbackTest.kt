package dev.streetsnap.app.composition

import dev.streetsnap.camera.NormalizedBoundingBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentFeedbackTest {

    private fun frame(inset: Float = 0f) = NormalizedBoundingBox(
        inset, inset, 1f - inset, 1f - inset)

    private fun frame(left: Float, top: Float, right: Float, bottom: Float) =
        NormalizedBoundingBox(left, top, right, bottom)

    @Test
    fun fullFrameActionableStyleIsFramed() {
        assertTrue(AlignmentFeedback.isFramed(frame(), CompositionStyle.THIRDS))
        assertTrue(AlignmentFeedback.isFramed(frame(), CompositionStyle.CENTERED))
    }

    @Test
    fun insetStaysInsideTolerance() {
        // 3% on every side is still "the full frame"; the 0.92 crop band (4%) is not.
        assertTrue(AlignmentFeedback.isFramed(frame(0.03f), CompositionStyle.THIRDS))
        assertFalse(AlignmentFeedback.isFramed(frame(0.04f), CompositionStyle.THIRDS))
    }

    @Test
    fun singleSideInsetBoundsTheDecision() {
        assertTrue(AlignmentFeedback.isFramed(frame(0.03f, 0f, 1f, 1f), CompositionStyle.TRIANGLE))
        assertFalse(AlignmentFeedback.isFramed(frame(0f, 0.04f, 1f, 1f), CompositionStyle.TRIANGLE))
    }

    @Test
    fun problemStylesNeverCountAsFramed() {
        for (style in listOf(CompositionStyle.EDGE_CUT, CompositionStyle.TOO_TIGHT,
            CompositionStyle.HORIZON_SPLIT, CompositionStyle.CONSERVATIVE)) {
            assertFalse(style.name, AlignmentFeedback.isFramed(frame(), style))
        }
    }

    @Test
    fun alreadyGoodFramingOnSwitchOnEarnsNoPraise() {
        val tracker = AlignmentTracker()
        // The full frame carries no move sentence (actionable=false); even if a caller
        // marks it actionable, no prior need was observed, so nothing fires.
        repeat(10) { assertNull(tracker.observe(framed = true, actionable = false)) }
        repeat(10) { assertNull(tracker.observe(framed = true, actionable = true)) }
    }

    @Test
    fun firesOnceAfterEightConsecutiveFramedFrames() {
        val tracker = AlignmentTracker()
        assertNull(tracker.observe(framed = false, actionable = true))
        repeat(7) { index ->
            assertNull("frame ${index + 1}", tracker.observe(framed = true, actionable = false))
        }
        assertTrue(tracker.observe(framed = true, actionable = false) == AlignmentEvent.ALIGNED)
        // Staying framed never re-fires.
        repeat(12) { assertNull(tracker.observe(framed = true, actionable = false)) }
    }

    @Test
    fun oneFlickeringFrameDoesNotConfirm() {
        val tracker = AlignmentTracker()
        tracker.observe(framed = false, actionable = true)
        repeat(5) { tracker.observe(framed = true, actionable = false) }
        assertNull(tracker.observe(framed = false, actionable = true))
        repeat(AlignmentFeedback.CONFIRM_FRAMES - 1) {
            assertNull(tracker.observe(framed = true, actionable = false))
        }
        // Streak restarted from zero after the miss: the eighth in-frame frame confirms.
        assertTrue(tracker.observe(framed = true, actionable = false) == AlignmentEvent.ALIGNED)
    }

    @Test
    fun briefDriftWhileLatchedDoesNotRearm() {
        val tracker = AlignmentTracker()
        tracker.observe(framed = false, actionable = true)
        repeat(AlignmentFeedback.CONFIRM_FRAMES) { tracker.observe(framed = true, actionable = false) }
        // One or two bad frames are ignored while latched...
        repeat(AlignmentFeedback.REARM_FRAMES - 1) {
            assertNull(tracker.observe(framed = false, actionable = true))
        }
        // ...and coming back does not fire again.
        repeat(AlignmentFeedback.CONFIRM_FRAMES) {
            assertNull(tracker.observe(framed = true, actionable = false))
        }
    }

    @Test
    fun sustainedDriftRearmsOneMoreConfirmation() {
        val tracker = AlignmentTracker()
        tracker.observe(framed = false, actionable = true)
        repeat(AlignmentFeedback.CONFIRM_FRAMES) { tracker.observe(framed = true, actionable = false) }
        repeat(AlignmentFeedback.REARM_FRAMES) { tracker.observe(framed = false, actionable = false) }
        // No fresh move sentence required after an achieved alignment drifted away.
        repeat(AlignmentFeedback.CONFIRM_FRAMES - 1) {
            assertNull(tracker.observe(framed = true, actionable = false))
        }
        assertTrue(tracker.observe(framed = true, actionable = false) == AlignmentEvent.ALIGNED)
    }

    @Test
    fun nonActionableNeedDoesNotArm() {
        val tracker = AlignmentTracker()
        // Problem styles stay un-framed but also carry no move sentence.
        repeat(5) { tracker.observe(framed = false, actionable = false) }
        repeat(AlignmentFeedback.CONFIRM_FRAMES) {
            assertNull(tracker.observe(framed = true, actionable = false))
        }
        // An actionable need arms it immediately afterwards.
        tracker.observe(framed = false, actionable = true)
        repeat(AlignmentFeedback.CONFIRM_FRAMES - 1) {
            assertNull(tracker.observe(framed = true, actionable = false))
        }
        assertTrue(tracker.observe(framed = true, actionable = false) == AlignmentEvent.ALIGNED)
    }

    @Test
    fun resetStartsTheStoryOver() {
        val tracker = AlignmentTracker(confirmFrames = 2, rearmFrames = 2)
        tracker.observe(framed = false, actionable = true)
        tracker.observe(framed = true, actionable = false)
        assertTrue(tracker.observe(framed = true, actionable = false) == AlignmentEvent.ALIGNED)
        tracker.reset()
        repeat(4) { assertNull(tracker.observe(framed = true, actionable = false)) }
    }
}
