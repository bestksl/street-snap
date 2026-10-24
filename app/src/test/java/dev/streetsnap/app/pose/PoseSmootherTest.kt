package dev.streetsnap.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * T108: geometric property checks for the One-Euro landmark filter. These prove the filter
 * contract (jitter attenuation, bounded tracking lag, visibility-held occlusion window,
 * clean reacquisition), not photographic smoothness — the tuning stays experimental until
 * T90 real-subject testing.
 */
class PoseSmootherTest {

    private val index = PoseJoint.NOSE
    private val frameMs = 120L

    private fun oneJoint(ts: Long, x: Float, y: Float, visible: Boolean = true): PoseSample {
        val point = if (visible) PosePoint(x, y, visibility = 1f) else PosePoint(0f, 0f, visibility = 0f)
        return PoseSample(List(PoseSmoother.JOINT_COUNT) { if (it == index) point else PosePoint(0f, 0f, visibility = 0f) }, ts)
    }

    private fun PoseSample.where() = points[index]

    @Test
    fun firstFrame_isPassthrough() {
        val smoother = PoseSmoother()
        val out = smoother.accept(oneJoint(0L, .42f, .31f))
        assertEquals(.42f, out.where().x, 1e-6f)
        assertEquals(.31f, out.where().y, 1e-6f)
        assertTrue(out.where().isVisible)
    }

    @Test
    fun staticJitter_isAttenuated() {
        val smoother = PoseSmoother()
        // Alternating +/-8 px-normalised noise around a still joint.
        val noise = floatArrayOf(.008f, -.008f, .008f, -.008f, .008f, -.008f, .008f, -.008f, .008f, -.008f, .008f, -.008f)
        smoother.accept(oneJoint(0L, .5f, .5f))
        val tail = (1..12).map { frame ->
            smoother.accept(oneJoint(frame * frameMs, .5f + noise[frame - 1], .5f)).where()
        }.drop(8)
        assertTrue(tail.all { abs(it.x - .5f) < .005f })
    }

    @Test
    fun fastMotion_tracksWithBoundedLag() {
        val smoother = PoseSmoother()
        smoother.accept(oneJoint(0L, .2f, .5f))
        smoother.accept(oneJoint(frameMs, .8f, .5f)) // one fast 0.6-unit jump
        val frames = (2..6).map { frame ->
            smoother.accept(oneJoint(frame * frameMs, .8f, .5f)).where().x
        }
        // Within a few frames of holding the new position the filter must have caught up;
        // a low-pass-only filter (beta = 0) would lag far longer.
        assertTrue("tracked positions: $frames", abs(frames.last() - .8f) < .01f)
    }

    @Test
    fun briefOcclusion_holdsLastPosition() {
        val smoother = PoseSmoother()
        repeat(5) { frame -> smoother.accept(oneJoint(frame * frameMs, .5f, .5f)) }
        val heldAt = 5 * frameMs // 120 ms since the last sighting, inside the 350 ms window
        val held = smoother.accept(oneJoint(heldAt, 0f, 0f, visible = false)).where()
        assertTrue(held.isVisible)
        assertEquals(.5f, held.x, .01f)
    }

    @Test
    fun occlusionPastTimeout_dropsPoint() {
        val smoother = PoseSmoother()
        repeat(5) { frame -> smoother.accept(oneJoint(frame * frameMs, .5f, .5f)) }
        val droppedAt = (4 * frameMs) + PoseSmoother.DEFAULT_HOLD_TIMEOUT_MS + 1L
        val dropped = smoother.accept(oneJoint(droppedAt, 0f, 0f, visible = false)).where()
        assertFalse(dropped.isVisible)
    }

    @Test
    fun reappearanceAfterLongGap_adoptsInstantly() {
        val smoother = PoseSmoother()
        repeat(3) { frame -> smoother.accept(oneJoint(frame * frameMs, .2f, .2f)) }
        // Gap far beyond reacquireMs, person reappears on the other side of the frame.
        val out = smoother.accept(oneJoint(3 * frameMs + PoseSmoother.DEFAULT_REACQUIRE_MS + 50L, .9f, .9f)).where()
        assertEquals(.9f, out.x, 1e-6f)
        assertEquals(.9f, out.y, 1e-6f)
    }

    @Test
    fun reset_clearsHeldPositions() {
        val smoother = PoseSmoother()
        repeat(3) { frame -> smoother.accept(oneJoint(frame * frameMs, .5f, .5f)) }
        smoother.reset()
        val out = smoother.accept(oneJoint(4 * frameMs, 0f, 0f, visible = false)).where()
        assertFalse(out.isVisible)
        assertEquals(0f, out.x, 0f)
    }

    @Test
    fun backwardsClock_resetsInsteadOfSpiking() {
        val smoother = PoseSmoother()
        smoother.accept(oneJoint(1_000L, .5f, .5f))
        // A new graph session can restart the timestamp base; output must be passthrough,
        // never a NaN or a position interpolated across a negative dt.
        val out = smoother.accept(oneJoint(0L, .7f, .3f)).where()
        assertEquals(.7f, out.x, 1e-6f)
        assertEquals(.3f, out.y, 1e-6f)
        assertFalse(out.x.isNaN())
    }
}
