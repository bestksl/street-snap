package dev.streetsnap.app.composition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookAdviserTest {

    private fun reading(
        dayPhase: DayPhase,
        sky: Double = 0.0,
        foliage: Double = 0.0,
        contrast: Double = 0.22,
        warmth: ColorWarmth = ColorWarmth.NEUTRAL,
    ) = SceneReading(
        sampleWidth = 40, sampleHeight = 30,
        meanLuma = if (dayPhase == DayPhase.NIGHT) 0.12 else 0.50,
        warmthIndex = 0.0, warmth = warmth,
        saturation = 0.30, contrast = contrast,
        highlightClipping = 0.0, shadowClipping = 0.0,
        skyRatioUpper = sky, foliageRatioUpper = foliage, dayPhase = dayPhase,
    )

    private val daySky = reading(DayPhase.DAY, sky = 0.60, warmth = ColorWarmth.COOL) // POSITIVE
    private val dusk = reading(DayPhase.DUSK, warmth = ColorWarmth.WARM) // CLASSIC_NEGATIVE
    private val night = reading(DayPhase.NIGHT, contrast = 0.30) // FILM:MONO_01
    private val ambiguous = reading(DayPhase.UNKNOWN) // no mapping

    private fun adviser(clockMs: Long = 1_000L, suppressMillis: Long = 5_000L): Pair<LookAdviser, () -> Long> {
        var clock = clockMs
        return LookAdviser(stableFrames = 3, suppressMillis = suppressMillis,
            now = { clock }) to { clock }
    }

    private fun LookAdviser.feed(scene: SceneReading?, times: Int, activeLookKey: String = "ORIGINAL",
        dismissed: Map<String, Long> = emptyMap()): LookAdvice? {
        var last: LookAdvice? = null
        repeat(times) { last = onFrame(scene, activeLookKey, dismissed) }
        return last
    }

    @Test fun firesOnlyAfterAStableStreak() {
        val (adviser) = adviser()
        assertNull(adviser.onFrame(ambiguous, "ORIGINAL", emptyMap()))
        assertNull(adviser.feed(daySky, times = 2))
        val fired = adviser.feed(daySky, times = 1)
        assertEquals("POSITIVE", fired!!.key)
        assertEquals(dev.streetsnap.color.ColorPreset.POSITIVE.displayName, fired.target.label)
    }

    @Test fun sameKeyIsSuggestedAtMostOncePerSession() {
        val (adviser) = adviser()
        assertEquals("POSITIVE", adviser.feed(daySky, 3)!!.key)
        assertNull(adviser.feed(daySky, 20))
    }

    @Test fun differentTargetResetsTheStreak() {
        val (adviser) = adviser()
        assertNull(adviser.feed(daySky, 2))
        // Two dusk frames are not enough after the target changed...
        assertNull(adviser.feed(dusk, 2))
        // ...the third one is.
        assertEquals("CLASSIC_NEGATIVE", adviser.feed(dusk, 1)!!.key)
    }

    @Test fun sceneChangeDropsThePendingStreakButShownHistorySurvives() {
        val (adviser) = adviser()
        assertNull(adviser.feed(daySky, 2))
        adviser.onSceneChanged()
        assertNull(adviser.feed(daySky, 2))
        assertEquals("POSITIVE", adviser.feed(daySky, 1)!!.key)
        // After a shown card, a scene change cannot make the same key bounce back.
        adviser.onSceneChanged()
        assertNull(adviser.feed(daySky, 10))
    }

    @Test fun sessionBudgetCapsAtTwoDistinctSuggestions() {
        val (adviser) = adviser()
        assertEquals("POSITIVE", adviser.feed(daySky, 3)!!.key)
        assertEquals("CLASSIC_NEGATIVE", adviser.feed(dusk, 3)!!.key)
        // Budget exhausted: even a fresh, never-shown, stable scene stays silent.
        assertNull(adviser.feed(night, 10))
    }

    @Test fun resetSessionRestoresBudgetsAndManualSilence() {
        val (adviser) = adviser()
        assertEquals("POSITIVE", adviser.feed(daySky, 3)!!.key)
        assertEquals("CLASSIC_NEGATIVE", adviser.feed(dusk, 3)!!.key)
        assertNull(adviser.feed(night, 10))
        adviser.resetSession()
        assertEquals("FILM:MONO_01", adviser.feed(night, 3)!!.key)
    }

    @Test fun manualLookPickSilencesUntilNextSession() {
        val (adviser) = adviser()
        adviser.onUserPickedLook()
        assertNull(adviser.feed(daySky, 10))
        adviser.resetSession()
        assertEquals("POSITIVE", adviser.feed(daySky, 3)!!.key)
    }

    @Test fun dismissedKeyIsSuppressedWithinTheMemoryWindow() {
        var clock = 1_000L
        val adviser = LookAdviser(stableFrames = 3, suppressMillis = 5_000L, now = { clock })
        val dismissed = mapOf("POSITIVE" to 0L) // 1_000 ms ago, inside the 5 s window
        assertNull(adviser.feed(daySky, 5, dismissed = dismissed))
        // A different key is unaffected.
        assertEquals("CLASSIC_NEGATIVE", adviser.feed(dusk, 3, dismissed = dismissed)!!.key)
        // Past the window the key is eligible again in a new session.
        clock = 6_000L
        adviser.resetSession()
        assertEquals("POSITIVE", adviser.feed(daySky, 3, dismissed = dismissed)!!.key)
    }

    @Test fun futureDismissTimestampNeverSuppresses() {
        val (adviser) = adviser(clockMs = 1_000L)
        val dismissed = mapOf("POSITIVE" to 9_999L)
        assertEquals("POSITIVE", adviser.feed(daySky, 3, dismissed = dismissed)!!.key)
    }

    @Test fun activeLookIsNeverSuggestedButOtherLooksStillAre() {
        val (adviser) = adviser()
        assertNull(adviser.feed(daySky, 10, activeLookKey = "POSITIVE"))
        assertEquals("CLASSIC_NEGATIVE", adviser.feed(dusk, 3, activeLookKey = "POSITIVE")!!.key)
    }

    @Test fun nullOrAbstainingReadingsNeverFire() {
        val (adviser) = adviser()
        assertNull(adviser.feed(null, 10))
        assertNull(adviser.feed(ambiguous, 10))
    }

    @Test fun defaultStreakIsEightFrames() {
        val adviser = LookAdviser()
        assertNull(adviser.feed(daySky, 7))
        assertEquals("POSITIVE", adviser.feed(daySky, 1)!!.key)
    }
}
