package dev.streetsnap.app.composition

import dev.streetsnap.camera.NormalizedBoundingBox
import kotlin.math.max

internal enum class AlignmentEvent { ALIGNED }

/**
 * T28 §2.2 standard 3 (T34): once the user follows the move sentence and the
 * suggested crop collapses onto the full frame, the guide gives one brief
 * confirmation (green stroke + check, optional haptic). Pure geometry and a
 * small frame state machine; photos and the ranking layer are untouched.
 */
internal object AlignmentFeedback {
    // Crop scales step in 0.08 bands, so a tighter suggestion keeps at least 4%
    // on every side: anything within 3% is, in practice, the full frame — the
    // framing the move was asking for.
    const val FRAME_TOLERANCE = 0.03f

    // ~2 s at the 250 ms realtime advice cadence; a single flickering frame
    // must not flash the confirmation.
    const val CONFIRM_FRAMES = 8

    // Staying outside the band for ~0.75 s ends the achieved alignment and
    // arms one more confirmation for the same scene.
    const val REARM_FRAMES = 3

    fun isFramed(crop: NormalizedBoundingBox, style: CompositionStyle): Boolean {
        // Problem styles (edge cut, too tight, bisected horizon, conservative)
        // carry their own goal, never a move sentence: no "well done" for them.
        if (style in CompositionActions.ACTION_BUILT_IN) return false
        val inset = max(max(crop.left, crop.top), max(1f - crop.right, 1f - crop.bottom))
        return inset <= FRAME_TOLERANCE
    }
}

/**
 * Per-scene confirmation state, fed one evaluation per analysed frame.
 *
 * An already-good framing observed right after the feature switches on earns
 * no praise: the scene must first offer an actionable move (a non-full-frame
 * suggestion with a move sentence), then stay framed for [confirmFrames]
 * consecutive frames. One [AlignmentEvent.ALIGNED] is emitted per achieved
 * alignment; leaving the band for [rearmFrames] frames rearms it.
 */
internal class AlignmentTracker(
    private val confirmFrames: Int = AlignmentFeedback.CONFIRM_FRAMES,
    private val rearmFrames: Int = AlignmentFeedback.REARM_FRAMES,
) {
    init {
        require(confirmFrames > 0 && rearmFrames > 0)
    }

    private var seenActionableNeed = false
    private var alignedStreak = 0
    private var latched = false
    private var lostStreak = 0

    /**
     * @param framed the current top suggestion is essentially the full frame
     * @param actionable the rule layer derived a move sentence for this frame
     */
    fun observe(framed: Boolean, actionable: Boolean): AlignmentEvent? {
        if (latched) {
            if (framed) {
                lostStreak = 0
                return null
            }
            if (++lostStreak < rearmFrames) return null
            // Drifting away from an achieved alignment is itself the next need;
            // no point demanding a fresh move sentence for the rearm.
            latched = false
            alignedStreak = 0
            seenActionableNeed = true
            return null
        }
        if (!framed) {
            if (actionable) seenActionableNeed = true
            alignedStreak = 0
            return null
        }
        if (!seenActionableNeed) return null
        if (++alignedStreak < confirmFrames) return null
        latched = true
        lostStreak = 0
        return AlignmentEvent.ALIGNED
    }

    /** Scene change, session restart, or advice cleared: the story starts over. */
    fun reset() {
        seenActionableNeed = false
        alignedStreak = 0
        latched = false
        lostStreak = 0
    }
}
