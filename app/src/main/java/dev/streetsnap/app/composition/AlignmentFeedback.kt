package dev.streetsnap.app.composition

import dev.streetsnap.camera.NormalizedBoundingBox
import kotlin.math.abs
import kotlin.math.max

internal enum class AlignmentEvent { ALIGNED }

/**
 * What the current top suggestion is asking for, reduced to the one thing the alignment
 * closed loop cares about. The tracker never sees crop pixels or wording, only this label.
 */
internal enum class FrameGuidance {
    /** A positive composition language (thirds, centred, …) with a move still outstanding. */
    NEEDS_MOVE,

    /** A positive language whose suggested frame already sits in the alignment band. */
    ALIGNED,

    /** A problem to fix (edge cut, too tight, bisected horizon): success is it going away. */
    PROBLEM,

    /** Conservative / generic fallback with no actionable move — no praise, no streak. */
    NEUTRAL,
}

/**
 * T28 §2.2 standard 3 (T34; reworked T40): the guide closes the loop once the user has
 * satisfied the move it asked for. The earlier test was "suggested crop ≈ full frame within
 * 3 %", which was structurally unreachable — crop scales step 0.68/0.76/0.84/0.92/1.0, so an
 * obedient walk that settles on the 0.92 first choice (4 % inset) never lit up (T37 M4:
 * 49/147, every failure stuck at inset 0.03–0.10). The goal of a move sentence is instead
 * read from geometry: the suggested frame recentres and grows to a near-full frame. Entry
 * (move generation) and exit (alignment) use asymmetric bands so a frame hovering around a
 * move threshold does not flicker between guiding and confirming.
 *
 * Pure geometry and a small frame state machine; photos, model ranking and exposure are
 * untouched.
 */
internal object AlignmentFeedback {
    // A centred suggestion whose centre is within this fraction of the frame centre and
    // whose scale keeps at least this fraction of the frame is "where the move was going".
    // Bands are deliberately a little wider than CompositionActions.*_MIN (the move trigger):
    // 0.09 vs 0.06 for pan/tilt, 0.88 scale vs the 0.93 (1-0.07) forward trigger. The 0.92
    // crop band (8 % inset) therefore reads as aligned, which is the steady state obedient
    // re-framing actually reaches. A probe run at 0.84 changed nothing once the walk budget
    // covered the confirmation window, so the tighter band is kept on purpose.
    const val ALIGN_CENTRE = 0.09f
    const val ALIGN_MIN_SCALE = 0.88f

    /**
     * Map a styled first suggestion to the guidance label the tracker consumes. Problem and
     * conservative styles carry their own goal (or none); every other style is aligned by
     * geometry alone — the wording is irrelevant.
     */
    fun guidanceFor(crop: NormalizedBoundingBox, style: CompositionStyle): FrameGuidance = when (style) {
        CompositionStyle.EDGE_CUT,
        CompositionStyle.TOO_TIGHT,
        CompositionStyle.HORIZON_SPLIT -> FrameGuidance.PROBLEM

        CompositionStyle.CONSERVATIVE -> FrameGuidance.NEUTRAL

        else -> if (isAligned(crop)) FrameGuidance.ALIGNED else FrameGuidance.NEEDS_MOVE
    }

    /**
     * The suggested frame sits centred and near full-size: no meaningful pan/tilt/dolly is
     * left to ask for. This replaces the old inset ≤ 3 % full-frame test.
     */
    fun isAligned(crop: NormalizedBoundingBox): Boolean {
        val centreU = (crop.left + crop.right) / 2f
        val centreV = (crop.top + crop.bottom) / 2f
        val scale = crop.right - crop.left
        return abs(centreU - 0.5f) <= ALIGN_CENTRE &&
            abs(centreV - 0.5f) <= ALIGN_CENTRE &&
            scale >= ALIGN_MIN_SCALE
    }

    // Kept for the long-press "trial framing" feature, which still needs an exact full-frame
    // test (there is nothing to preview when the suggestion already is the whole frame).
    const val FRAME_TOLERANCE = 0.03f

    fun isFramed(crop: NormalizedBoundingBox, style: CompositionStyle): Boolean {
        if (style in CompositionActions.ACTION_BUILT_IN) return false
        val inset = max(max(crop.left, crop.top), max(1f - crop.right, 1f - crop.bottom))
        return inset <= FRAME_TOLERANCE
    }
}

/**
 * Per-scene confirmation state, fed one evaluation per analysed frame.
 *
 * An already-good framing observed right after the feature switches on earns no praise: the
 * scene must first offer something to do (a move sentence, or a problem warning), then the
 * guidance must read [FrameGuidance.ALIGNED] for [confirmFrames] consecutive frames. One
 * [AlignmentEvent.ALIGNED] is emitted per achieved alignment. After a problem warning the
 * win is reaching a positive, aligned framing; a frame that merely goes quiet (NEUTRAL)
 * does not count. Staying aligned never re-fires, and drifting out for [rearmFrames] frames
 * rearms exactly one more confirmation for the same scene.
 */
internal class AlignmentTracker(
    private val confirmFrames: Int = CONFIRM_FRAMES,
    private val rearmFrames: Int = REARM_FRAMES,
) {
    init {
        require(confirmFrames > 0 && rearmFrames > 0)
    }

    private var seenNeed = false
    private var alignedStreak = 0
    private var latched = false
    private var lostStreak = 0

    /**
     * Persistent phase for the UI, distinct from the one-shot [AlignmentEvent]. True once an
     * alignment has been confirmed and until the framing drifts out long enough to rearm; the
     * one-to-two frame grace keeps the guide from winking green while handheld frames jitter.
     */
    val isConfirmedAligned: Boolean get() = latched

    fun observe(guidance: FrameGuidance): AlignmentEvent? {
        if (latched) {
            if (guidance == FrameGuidance.ALIGNED) {
                lostStreak = 0
                return null
            }
            if (++lostStreak < rearmFrames) return null
            // Drifting away from an achieved alignment is itself the next need; no fresh
            // move sentence is demanded for the rearm.
            latched = false
            alignedStreak = 0
            seenNeed = true
            return null
        }
        when (guidance) {
            FrameGuidance.NEEDS_MOVE, FrameGuidance.PROBLEM -> {
                seenNeed = true
                alignedStreak = 0
                return null
            }
            FrameGuidance.NEUTRAL -> {
                // No confident reading this frame: do not advance, do not forget the need.
                alignedStreak = 0
                return null
            }
            FrameGuidance.ALIGNED -> {
                if (!seenNeed) return null
                if (++alignedStreak < confirmFrames) return null
                latched = true
                lostStreak = 0
                return AlignmentEvent.ALIGNED
            }
        }
    }

    /** Scene change, session restart, or advice cleared: the story starts over. */
    fun reset() {
        seenNeed = false
        alignedStreak = 0
        latched = false
        lostStreak = 0
    }

    companion object {
        // ~1.25 s at the 250 ms realtime advice cadence; a single flickering frame must not
        // flash the confirmation, but two seconds of perfect stillness was more than the
        // T37 walk (and a handheld street frame) can hold.
        const val CONFIRM_FRAMES = 5

        // Staying outside the band for ~0.75 s ends the achieved alignment and arms one
        // more confirmation for the same scene.
        const val REARM_FRAMES = 3
    }
}
