package dev.streetsnap.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSceneTrackerTest {
    @Test
    fun aSlowInferenceCanStillPublishWhenRecentSceneSamplesMatch() {
        val tracker = FrameSceneTracker()
        tracker.observe(pattern(), 0)
        val captured = tracker.capture()
        tracker.observe(pattern(brightness = 8), 20_000_000_000)
        assertTrue(tracker.isCurrent(captured, 20_500_000_000))
        assertFalse(tracker.isCurrent(captured, 22_000_000_001))
    }

    @Test
    fun panningWithTheSameColourHistogramClearsTheOverlayOnce() {
        val tracker = FrameSceneTracker()
        tracker.observe(pattern(), 0)
        val captured = tracker.capture()
        assertTrue(tracker.observe(pattern(flipped = true), 500_000_000))
        assertFalse(tracker.isCurrent(captured, 500_000_000))
        assertFalse(tracker.observe(pattern(flipped = true), 1_000_000_000))
    }

    @Test
    fun temporaryMovementDoesNotPermanentlyInvalidateTheInference() {
        val tracker = FrameSceneTracker()
        tracker.observe(pattern(), 0)
        val captured = tracker.capture()
        tracker.observe(pattern(flipped = true), 500_000_000)
        assertFalse(tracker.isCurrent(captured, 500_000_000))
        tracker.observe(pattern(), 1_000_000_000)
        assertTrue(tracker.isCurrent(captured, 1_000_000_000))
    }

    @Test
    fun gradualMovementIsComparedWithTheCapturedSceneNotOnlyThePreviousSample() {
        val tracker = FrameSceneTracker()
        tracker.observe(pattern(), 0)
        val captured = tracker.capture()
        assertFalse(tracker.observe(pattern(brightness = 10), 500_000_000))
        assertTrue(tracker.observe(pattern(brightness = 25), 1_000_000_000))
        assertFalse(tracker.isCurrent(captured, 1_000_000_000))
    }

    @Test
    fun sessionResetAndDifferentViewportShapesRejectOldCoordinates() {
        val tracker = FrameSceneTracker()
        tracker.observe(pattern(), 0)
        val captured = tracker.capture()
        assertTrue(tracker.observe(SceneSignature(18, 24, IntArray(18 * 24)), 500_000_000))
        assertFalse(tracker.isCurrent(captured, 500_000_000))
        tracker.reset()
        assertFalse(tracker.isCurrent(captured, 500_000_000))
    }

    private fun pattern(brightness: Int = 0, flipped: Boolean = false): SceneSignature =
        SceneSignature(24, 18, IntArray(24 * 18) { index ->
            val value = (if ((index % 24 < 12) xor flipped) 40 else 180) + brightness
            (255 shl 24) or (value shl 16) or (value shl 8) or value
        })
}
