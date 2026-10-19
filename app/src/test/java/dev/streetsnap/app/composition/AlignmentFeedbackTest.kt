package dev.streetsnap.app.composition

import dev.streetsnap.camera.NormalizedBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentFeedbackTest {

    private fun frame(inset: Float = 0f) = NormalizedBoundingBox(
        inset, inset, 1f - inset, 1f - inset)

    private fun frame(left: Float, top: Float, right: Float, bottom: Float) =
        NormalizedBoundingBox(left, top, right, bottom)

    // ── Legacy exact-full-frame test, still used by the long-press trial zoom ──

    @Test
    fun fullFrameActionableStyleIsFramed() {
        assertTrue(AlignmentFeedback.isFramed(frame(), CompositionStyle.THIRDS))
        assertTrue(AlignmentFeedback.isFramed(frame(), CompositionStyle.CENTERED))
    }

    @Test
    fun insetStaysInsideTolerance() {
        assertTrue(AlignmentFeedback.isFramed(frame(0.03f), CompositionStyle.THIRDS))
        assertFalse(AlignmentFeedback.isFramed(frame(0.04f), CompositionStyle.THIRDS))
    }

    @Test
    fun singleSideInsetBoundsTheDecision() {
        assertTrue(AlignmentFeedback.isFramed(frame(0.03f, 0f, 1f, 1f), CompositionStyle.TRIANGLE))
        assertFalse(AlignmentFeedback.isFramed(frame(0f, 0.04f, 1f, 1f), CompositionStyle.TRIANGLE))
    }

    @Test
    fun problemStylesNeverCountAsExactFullFrame() {
        for (style in listOf(CompositionStyle.EDGE_CUT, CompositionStyle.TOO_TIGHT,
            CompositionStyle.HORIZON_SPLIT, CompositionStyle.CONSERVATIVE)) {
            assertFalse(style.name, AlignmentFeedback.isFramed(frame(), style))
        }
    }

    // ── T40 move-satisfaction alignment band ──

    @Test
    fun fullCenteredFrameIsAligned() {
        assertTrue(AlignmentFeedback.isAligned(frame()))
    }

    @Test
    fun ninetyTwoCentredCropIsAligned() {
        // The structural T37 failure: the 0.92 band leaves 4 % inset and never met the old
        // 3 % full-frame test. A centred 0.92 suggestion is exactly where obedient moves land.
        assertTrue(AlignmentFeedback.isAligned(frame(0.04f)))
    }

    @Test
    fun eightyFourCentredCropIsStillTooTightToConfirm() {
        assertFalse(AlignmentFeedback.isAligned(frame(0.08f)))
    }

    @Test
    fun tightOffCentreFrameIsNotAligned() {
        // A 0.68 crop pushed left (centre 0.39): a near-full, centred frame is not yet reached.
        assertFalse(AlignmentFeedback.isAligned(frame(0.05f, 0.18f, 0.73f, 0.86f)))
    }

    @Test
    fun guidanceMapsStylesToLabels() {
        assertEquals(FrameGuidance.PROBLEM,
            AlignmentFeedback.guidanceFor(frame(), CompositionStyle.EDGE_CUT))
        assertEquals(FrameGuidance.PROBLEM,
            AlignmentFeedback.guidanceFor(frame(), CompositionStyle.TOO_TIGHT))
        assertEquals(FrameGuidance.PROBLEM,
            AlignmentFeedback.guidanceFor(frame(), CompositionStyle.HORIZON_SPLIT))
        assertEquals(FrameGuidance.NEUTRAL,
            AlignmentFeedback.guidanceFor(frame(), CompositionStyle.CONSERVATIVE))
        assertEquals(FrameGuidance.ALIGNED,
            AlignmentFeedback.guidanceFor(frame(0.04f), CompositionStyle.THIRDS))
        assertEquals(FrameGuidance.NEEDS_MOVE,
            AlignmentFeedback.guidanceFor(frame(0.05f, 0.18f, 0.73f, 0.86f), CompositionStyle.THIRDS))
    }

    // ── State machine ──

    @Test
    fun alreadyAlignedOnSwitchOnEarnsNoPraise() {
        val tracker = AlignmentTracker()
        repeat(10) { assertNull(tracker.observe(FrameGuidance.ALIGNED)) }
    }

    @Test
    fun firesOnceAfterConsecutiveAlignedFrames() {
        val tracker = AlignmentTracker()
        assertNull(tracker.observe(FrameGuidance.NEEDS_MOVE))
        repeat(AlignmentTracker.CONFIRM_FRAMES - 1) { index ->
            assertNull("frame ${index + 1}", tracker.observe(FrameGuidance.ALIGNED))
        }
        assertTrue(tracker.observe(FrameGuidance.ALIGNED) == AlignmentEvent.ALIGNED)
        // Staying aligned never re-fires.
        repeat(12) { assertNull(tracker.observe(FrameGuidance.ALIGNED)) }
    }

    @Test
    fun oneFlickeringFrameRestartsTheStreak() {
        val tracker = AlignmentTracker()
        tracker.observe(FrameGuidance.NEEDS_MOVE)
        repeat(3) { tracker.observe(FrameGuidance.ALIGNED) }
        assertNull(tracker.observe(FrameGuidance.NEUTRAL))
        repeat(AlignmentTracker.CONFIRM_FRAMES - 1) {
            assertNull(tracker.observe(FrameGuidance.ALIGNED))
        }
        assertTrue(tracker.observe(FrameGuidance.ALIGNED) == AlignmentEvent.ALIGNED)
    }

    @Test
    fun problemWarningArmsAndReachingAlignedFramingConfirms() {
        // EDGE_CUT tells the user to step sideways; the win is reaching an aligned positive
        // framing, never the warning disappearing into a quiet NEUTRAL fallback.
        val tracker = AlignmentTracker()
        repeat(3) { assertNull(tracker.observe(FrameGuidance.PROBLEM)) }
        // Going quiet does not praise.
        repeat(AlignmentTracker.CONFIRM_FRAMES + 2) {
            assertNull(tracker.observe(FrameGuidance.NEUTRAL))
        }
        // Reaching a real aligned composition does.
        repeat(AlignmentTracker.CONFIRM_FRAMES - 1) {
            assertNull(tracker.observe(FrameGuidance.ALIGNED))
        }
        assertTrue(tracker.observe(FrameGuidance.ALIGNED) == AlignmentEvent.ALIGNED)
    }

    @Test
    fun briefDriftWhileLatchedDoesNotRearm() {
        val tracker = AlignmentTracker()
        tracker.observe(FrameGuidance.NEEDS_MOVE)
        repeat(AlignmentTracker.CONFIRM_FRAMES) { tracker.observe(FrameGuidance.ALIGNED) }
        repeat(AlignmentTracker.REARM_FRAMES - 1) {
            assertNull(tracker.observe(FrameGuidance.NEEDS_MOVE))
        }
        repeat(AlignmentTracker.CONFIRM_FRAMES) {
            assertNull(tracker.observe(FrameGuidance.ALIGNED))
        }
    }

    @Test
    fun sustainedDriftRearmsOneMoreConfirmation() {
        val tracker = AlignmentTracker()
        tracker.observe(FrameGuidance.NEEDS_MOVE)
        repeat(AlignmentTracker.CONFIRM_FRAMES) { tracker.observe(FrameGuidance.ALIGNED) }
        repeat(AlignmentTracker.REARM_FRAMES) { tracker.observe(FrameGuidance.NEUTRAL) }
        // Drifting away is itself the new need; no fresh move frame is demanded for the rearm.
        repeat(AlignmentTracker.CONFIRM_FRAMES - 1) {
            assertNull(tracker.observe(FrameGuidance.ALIGNED))
        }
        assertTrue(tracker.observe(FrameGuidance.ALIGNED) == AlignmentEvent.ALIGNED)
    }

    @Test
    fun neutralFramesDoNotArmOrAdvance() {
        val tracker = AlignmentTracker()
        repeat(5) { tracker.observe(FrameGuidance.NEUTRAL) }
        repeat(AlignmentTracker.CONFIRM_FRAMES) {
            assertNull(tracker.observe(FrameGuidance.ALIGNED))
        }
        tracker.observe(FrameGuidance.NEEDS_MOVE)
        repeat(AlignmentTracker.CONFIRM_FRAMES - 1) {
            assertNull(tracker.observe(FrameGuidance.ALIGNED))
        }
        assertTrue(tracker.observe(FrameGuidance.ALIGNED) == AlignmentEvent.ALIGNED)
    }

    @Test
    fun resetStartsTheStoryOver() {
        val tracker = AlignmentTracker(confirmFrames = 2, rearmFrames = 2)
        tracker.observe(FrameGuidance.NEEDS_MOVE)
        tracker.observe(FrameGuidance.ALIGNED)
        assertTrue(tracker.observe(FrameGuidance.ALIGNED) == AlignmentEvent.ALIGNED)
        tracker.reset()
        repeat(4) { assertNull(tracker.observe(FrameGuidance.ALIGNED)) }
    }
}
