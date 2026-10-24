package dev.streetsnap.app.composition

import dev.streetsnap.camera.ExposureStats

/** What the exposure hint card should say; [evDirection] is -1/0/+1 exposure-compensation stops. */
internal enum class ExposureIssue(val evDirection: Int) {
    /** Both ends clip at once: EV cannot save both, so the hint points at HDF/composition. */
    WIDE_DYNAMIC_RANGE(0),
    HIGHLIGHTS_CLIPPED(-1),
    SHADOWS_CLIPPED(+1),
}

internal data class ExposureAdvice(val issue: ExposureIssue)

/**
 * Anti-false-positive gate for exposure hints (Halide's "do not cry clipped highlights
 * until they are real" rule, docs §2.1): the same issue must persist [confirmFrames]
 * before a card is shown, and [clearFrames] healthy frames (or a different issue)
 * withdraw it. Because the meter has no scene-signature stream, reversal is the
 * scene-change contract.
 *
 * One issue is shown at most once per foreground session, within the shared
 * [AdviceSessionBudget] (≤5 total nudges, exposure is not a "look"). Pure logic.
 */
internal class ExposureAdviser(
    private val confirmFrames: Int = DEFAULT_CONFIRM_FRAMES,
    private val clearFrames: Int = DEFAULT_CLEAR_FRAMES,
    private val highlightLimit: Double = HIGHLIGHT_RATIO,
    private val shadowLimit: Double = SHADOW_RATIO,
    private val budget: AdviceSessionBudget = AdviceSessionBudget(),
) {
    private var candidate: ExposureIssue? = null
    private var candidateCount = 0
    private var clearCount = 0
    private val shown = mutableSetOf<ExposureIssue>()
    private var visibleField: ExposureIssue? = null

    /** The issue whose card is currently on screen, or null after healthy frames withdraw it. */
    val visibleIssue: ExposureIssue? get() = visibleField

    /**
     * @param evAdjustable false in manual ISO/shutter mode, where EV compensation does
     * not apply; wide-range HDF advice is still allowed.
     * @param canLowerEv / canRaiseEv false at the compensation range ends — a nudge with
     * no available action is withheld rather than shown.
     * @param diffusionEnabled true while either diffusion look (the HDF button or the
     * soft-diffusion look) is on: the wide-dynamic-range card only tells the user to
     * switch HDF on, so once it already is, the card has no action left and is withheld.
     * EV nudges are unaffected because HDF does not change exposure.
     * Returns an advice exactly on the frame it becomes visible, otherwise null.
     */
    fun onFrame(
        stats: ExposureStats?,
        evAdjustable: Boolean,
        canLowerEv: Boolean,
        canRaiseEv: Boolean,
        diffusionEnabled: Boolean = false,
    ): ExposureAdvice? {
        val detected = detect(stats, evAdjustable, canLowerEv, canRaiseEv, diffusionEnabled)
        if (detected == null) {
            candidate = null
            candidateCount = 0
            if (visibleField != null && ++clearCount >= clearFrames) visibleField = null
            return null
        }
        clearCount = 0
        if (candidate != detected) {
            candidate = detected
            candidateCount = 1
        } else {
            candidateCount++
        }
        if (candidateCount < confirmFrames) return null
        if (visibleField == detected || detected in shown || !budget.canAcquire(isLook = false)) return null
        visibleField = detected
        shown += detected
        budget.acquire(isLook = false)
        return ExposureAdvice(detected)
    }

    private fun detect(
        stats: ExposureStats?,
        evAdjustable: Boolean,
        canLowerEv: Boolean,
        canRaiseEv: Boolean,
        diffusionEnabled: Boolean,
    ): ExposureIssue? {
        if (stats == null || stats.sampleCount <= 0L) return null
        val highlights = stats.highlightRatio >= highlightLimit
        val shadows = stats.shadowRatio >= shadowLimit
        // Both ends clip first: EV cannot save both poles at once, so this must never fall
        // through to a single-pole EV nudge. With diffusion already on, the card's only
        // suggestion ("switch HDF on") is applied, so there is nothing left to show.
        if (highlights && shadows) {
            return if (diffusionEnabled) null else ExposureIssue.WIDE_DYNAMIC_RANGE
        }
        return when {
            highlights && evAdjustable && canLowerEv -> ExposureIssue.HIGHLIGHTS_CLIPPED
            shadows && evAdjustable && canRaiseEv -> ExposureIssue.SHADOWS_CLIPPED
            else -> null
        }
    }

    /** × on the card: withdraw it and never repeat the same issue this session. */
    fun dismiss(issue: ExposureIssue) {
        shown += issue
        if (visibleField == issue) visibleField = null
        candidate = null
        candidateCount = 0
    }

    fun resetSession() {
        candidate = null
        candidateCount = 0
        clearCount = 0
        shown.clear()
        visibleField = null
        budget.reset()
    }

    companion object {
        /** Meter frames are throttled to 200 ms: six ≈ 1.2 s of persistent clipping. */
        const val DEFAULT_CONFIRM_FRAMES = 6
        const val DEFAULT_CLEAR_FRAMES = 3
        /** ≥4% luma-251..255 pixels reads as a blown-out area. */
        const val HIGHLIGHT_RATIO = 0.04
        /** ≥15% luma-0..5 pixels reads as crushed shadows (tolerated more than highlights). */
        const val SHADOW_RATIO = 0.15
    }
}
