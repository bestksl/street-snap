package dev.streetsnap.app.pose

import kotlin.math.PI
import kotlin.math.hypot

/**
 * T108: temporal filter for the MediaPipe landmark stream. The lite model on CPU emits
 * visibly jittery joints frame to frame; without filtering the white skeleton shakes on a
 * standing subject and the coach score bar flickers. Each of the 33 joints gets its own
 * [One-Euro filter](https://dl.acm.org/doi/10.1145/2207676.2208639): strong smoothing at
 * low speeds, near-passthrough during fast motion, with a real (irregular) inter-result
 * delta-time so the uneven LIVE_STREAM cadence needs no fixed frame assumption.
 *
 * Visibility-gated, unlike a plain positional filter:
 * - a visible landmark updates the filter normally;
 * - a briefly occluded landmark HOLDS its last filtered position (with its last visibility)
 *   for up to [holdTimeoutMs], so an arm crossing the body for one or two frames does not
 *   snap the skeleton or reset the coach's streak;
 * - past the hold timeout the point goes invisible; after [reacquireMs] away a reappearing
 *   point is adopted instantly (the subject may have moved across the gap, never interpolate
 *   across it).
 *
 * Tunables are an experimental v1 calibration in normalised-image units and seconds; they
 * must be re-tuned on photographed subjects (T90). Pure Kotlin, JVM-tested.
 */
internal class PoseSmoother(
    private val minCutoff: Float = DEFAULT_MIN_CUTOFF,
    private val beta: Float = DEFAULT_BETA,
    private val derivativeCutoff: Float = DEFAULT_DERIVATIVE_CUTOFF,
    private val holdTimeoutMs: Long = DEFAULT_HOLD_TIMEOUT_MS,
    private val reacquireMs: Long = DEFAULT_REACQUIRE_MS,
) {
    private val joints = List(JOINT_COUNT) { JointState() }
    private var lastClockMs = 0L
    private var hasClock = false

    private class JointState {
        var seen = false
        var rawX = 0f
        var rawY = 0f
        var filteredX = 0f
        var filteredY = 0f
        var hasVelocity = false
        var velocityX = 0f
        var velocityY = 0f
        var updatedAtMs = 0L
        var seenAtMs = 0L
        var visibility = 0f
    }

    /** Clears every joint; call when live recognition starts, stops, or loses the person. */
    fun reset() {
        joints.forEach { state ->
            state.seen = false
            state.hasVelocity = false
            state.visibility = 0f
        }
        hasClock = false
    }

    /**
     * Filters one result. Output keeps the input's timestamp; occluded joints come back as
     * held positions while fresh ones are filtered. A result stream whose clock goes
     * backwards (new graph session) resets the filter instead of producing a spike.
     */
    fun accept(sample: PoseSample): PoseSample {
        val nowMs = sample.timestampMs
        if (hasClock && nowMs < lastClockMs) reset()
        val output = ArrayList<PosePoint>(JOINT_COUNT)
        for (index in 0 until JOINT_COUNT) {
            val state = joints[index]
            val raw = sample.points.getOrNull(index)
            if (raw != null && raw.isVisible) {
                val (x, y) = feed(state, raw, nowMs)
                output += PosePoint(x, y, raw.z, raw.visibility)
            } else if (state.seen && nowMs - state.seenAtMs <= holdTimeoutMs) {
                // Brief occlusion: keep drawing/scoring the last estimate so limbs do not
                // flicker out; same visibility as the last real sighting.
                output += PosePoint(state.filteredX, state.filteredY, 0f, state.visibility)
            } else {
                output += PosePoint(0f, 0f, 0f, visibility = 0f)
            }
        }
        hasClock = true
        lastClockMs = nowMs
        return PoseSample(output, nowMs)
    }

    private fun feed(state: JointState, raw: PosePoint, nowMs: Long): Pair<Float, Float> {
        if (!state.seen || nowMs - state.seenAtMs > reacquireMs) {
            // First sighting or a long gap: adopt the point directly so a reappearing joint
            // does not fly in from a stale position.
            state.seen = true
            state.rawX = raw.x
            state.rawY = raw.y
            state.filteredX = raw.x
            state.filteredY = raw.y
            state.hasVelocity = false
            state.velocityX = 0f
            state.velocityY = 0f
            state.updatedAtMs = nowMs
            state.seenAtMs = nowMs
            state.visibility = raw.visibility
            return raw.x to raw.y
        }
        val dtSeconds = (nowMs - state.updatedAtMs).coerceAtLeast(1L) / 1000f
        val instantVx = (raw.x - state.rawX) / dtSeconds
        val instantVy = (raw.y - state.rawY) / dtSeconds
        val derivativeAlpha = alpha(derivativeCutoff, dtSeconds)
        if (state.hasVelocity) {
            state.velocityX += derivativeAlpha * (instantVx - state.velocityX)
            state.velocityY += derivativeAlpha * (instantVy - state.velocityY)
        } else {
            state.hasVelocity = true
            state.velocityX = instantVx
            state.velocityY = instantVy
        }
        val speed = hypot(state.velocityX, state.velocityY)
        val positionAlpha = alpha(minCutoff + beta * speed, dtSeconds)
        state.filteredX += positionAlpha * (raw.x - state.filteredX)
        state.filteredY += positionAlpha * (raw.y - state.filteredY)
        state.rawX = raw.x
        state.rawY = raw.y
        state.updatedAtMs = nowMs
        state.seenAtMs = nowMs
        state.visibility = raw.visibility
        return state.filteredX to state.filteredY
    }

    private fun alpha(cutoff: Float, dtSeconds: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dtSeconds)
    }

    companion object {
        const val JOINT_COUNT = 33
        const val DEFAULT_MIN_CUTOFF = 1.0f
        const val DEFAULT_BETA = 0.8f
        const val DEFAULT_DERIVATIVE_CUTOFF = 1.0f
        const val DEFAULT_HOLD_TIMEOUT_MS = 350L
        const val DEFAULT_REACQUIRE_MS = 1_000L
    }
}
