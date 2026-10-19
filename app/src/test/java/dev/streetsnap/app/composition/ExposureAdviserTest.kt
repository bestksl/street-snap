package dev.streetsnap.app.composition

import dev.streetsnap.camera.ExposureStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExposureAdviserTest {

    private fun stats(highlights: Double = 0.0, shadows: Double = 0.0) =
        ExposureStats(sampleCount = 1000L, highlightRatio = highlights, shadowRatio = shadows, meanLuma = 0.5)

    private val blown = stats(highlights = 0.10)
    private val crushed = stats(shadows = 0.30)
    private val both = stats(highlights = 0.08, shadows = 0.25)
    private val healthy = stats(highlights = 0.01, shadows = 0.02)

    private fun ExposureAdviser.feed(stats: ExposureStats?, times: Int,
        evAdjustable: Boolean = true, canLowerEv: Boolean = true, canRaiseEv: Boolean = true): ExposureAdvice? {
        var last: ExposureAdvice? = null
        repeat(times) {
            last = onFrame(stats, evAdjustable, canLowerEv, canRaiseEv)
        }
        return last
    }

    @Test fun highlightsNeedSixPersistentFrames() {
        val adviser = ExposureAdviser(confirmFrames = 6, clearFrames = 3)
        assertNull(adviser.feed(blown, 5))
        assertEquals(ExposureIssue.HIGHLIGHTS_CLIPPED, adviser.feed(blown, 1)!!.issue)
        assertEquals(ExposureIssue.HIGHLIGHTS_CLIPPED, adviser.visibleIssue)
    }

    @Test fun shadowsRaiseEvHint() {
        val adviser = ExposureAdviser(confirmFrames = 2)
        assertEquals(ExposureIssue.SHADOWS_CLIPPED, adviser.feed(crushed, 2)!!.issue)
        assertEquals(+1, ExposureIssue.SHADOWS_CLIPPED.evDirection)
        assertEquals(-1, ExposureIssue.HIGHLIGHTS_CLIPPED.evDirection)
    }

    @Test fun bothEndsClippingMeansWideRangeFirst() {
        val adviser = ExposureAdviser(confirmFrames = 2)
        // Wide-range advice fires even though EV compensation is unavailable (manual mode).
        assertEquals(ExposureIssue.WIDE_DYNAMIC_RANGE,
            adviser.feed(both, 2, evAdjustable = false)!!.issue)
        assertEquals(0, ExposureIssue.WIDE_DYNAMIC_RANGE.evDirection)
    }

    @Test fun thresholdEdgesDoNotFire() {
        val adviser = ExposureAdviser(confirmFrames = 1)
        assertNull(adviser.feed(stats(highlights = ExposureAdviser.HIGHLIGHT_RATIO - 1e-9), 10))
        assertNull(adviser.feed(stats(shadows = ExposureAdviser.SHADOW_RATIO - 1e-9), 10))
    }

    @Test fun manualModeWithSinglePoleAbstains() {
        val adviser = ExposureAdviser(confirmFrames = 1)
        assertNull(adviser.feed(blown, 10, evAdjustable = false))
        assertNull(adviser.feed(crushed, 10, evAdjustable = false))
    }

    @Test fun compensationRangeEndWithholdsTheHint() {
        val adviser = ExposureAdviser(confirmFrames = 1)
        // Already at the negative EV end: a "lower EV" nudge would have no available action.
        assertNull(adviser.feed(blown, 10, canLowerEv = false))
        assertEquals(ExposureIssue.HIGHLIGHTS_CLIPPED, adviser.feed(blown, 1, canLowerEv = true)!!.issue)
    }

    @Test fun sameIssueIsShownAtMostOncePerSession() {
        val adviser = ExposureAdviser(confirmFrames = 2, clearFrames = 2)
        assertEquals(ExposureIssue.HIGHLIGHTS_CLIPPED, adviser.feed(blown, 2)!!.issue)
        // Healthy frames withdraw the card...
        assertNull(adviser.feed(healthy, 2))
        assertNull(adviser.visibleIssue)
        // ...but the same issue cannot come straight back in this session.
        assertNull(adviser.feed(blown, 10))
    }

    @Test fun resetSessionAllowsTheIssueAgain() {
        val adviser = ExposureAdviser(confirmFrames = 1)
        assertEquals(ExposureIssue.HIGHLIGHTS_CLIPPED, adviser.feed(blown, 1)!!.issue)
        adviser.resetSession()
        assertEquals(ExposureIssue.HIGHLIGHTS_CLIPPED, adviser.feed(blown, 1)!!.issue)
    }

    @Test fun switchingCandidateRestartsTheStreak() {
        val adviser = ExposureAdviser(confirmFrames = 4)
        assertNull(adviser.feed(blown, 3))
        // One wide-range frame replaces the candidate instead of adding to the streak.
        assertNull(adviser.feed(both, 1))
        assertNull(adviser.feed(both, 2))
        assertEquals(ExposureIssue.WIDE_DYNAMIC_RANGE, adviser.feed(both, 1)!!.issue)
    }

    @Test fun dismissHidesAndSuppressesForTheSession() {
        val adviser = ExposureAdviser(confirmFrames = 1)
        val advice = adviser.feed(blown, 1)!!
        adviser.dismiss(advice.issue)
        assertNull(adviser.visibleIssue)
        assertNull(adviser.feed(blown, 10))
    }

    @Test fun emptyOrNullSamplesNeverFire() {
        val adviser = ExposureAdviser(confirmFrames = 1)
        assertNull(adviser.feed(null, 10))
        assertNull(adviser.feed(ExposureStats.EMPTY, 10))
    }

    @Test fun sharedBudgetCapsFiveTotalNudgesAcrossKinds() {
        val budget = AdviceSessionBudget()
        val looks = LookAdviser(stableFrames = 1, budget = budget)
        val exposure = ExposureAdviser(confirmFrames = 1, budget = budget)
        // Three looks spend the look cap and 3 of 5 total nudges.
        val sky = SceneReading(40, 30, 0.5, 0.0, ColorWarmth.COOL, 0.3, 0.22, 0.0, 0.0, 0.6, 0.0, DayPhase.DAY)
        val dusk = SceneReading(40, 30, 0.4, 0.12, ColorWarmth.WARM, 0.3, 0.22, 0.0, 0.0, 0.0, 0.0, DayPhase.DUSK)
        val night = SceneReading(40, 30, 0.12, 0.0, ColorWarmth.NEUTRAL, 0.30, 0.30, 0.0, 0.0, 0.0, 0.0, DayPhase.NIGHT)
        assertEquals("POSITIVE", looks.onFrame(sky, "ORIGINAL", emptyMap())!!.key)
        assertEquals("CLASSIC_NEGATIVE", looks.onFrame(dusk, "ORIGINAL", emptyMap())!!.key)
        assertEquals("FILM:MONO_01", looks.onFrame(night, "ORIGINAL", emptyMap())!!.key)
        // The look cap is spent: a fourth look stays silent even though total has room.
        val chrome = SceneReading(40, 30, 0.5, 0.0, ColorWarmth.NEUTRAL, 0.3, 0.22, 0.0, 0.0, 0.0, 0.4, DayPhase.DAY)
        assertNull(looks.onFrame(chrome, "ORIGINAL", emptyMap()))
        // Two exposure hints still fit the total of 5...
        assertEquals(ExposureIssue.HIGHLIGHTS_CLIPPED, exposure.feed(blown, 1)!!.issue)
        assertEquals(ExposureIssue.SHADOWS_CLIPPED, exposure.feed(crushed, 1)!!.issue)
        // ...the next one is blocked even though this issue was never shown yet.
        assertNull(exposure.feed(both, 10))
        exposure.resetSession() // also resets the shared budget
        assertEquals(ExposureIssue.WIDE_DYNAMIC_RANGE, exposure.feed(both, 1)!!.issue)
    }
}
