package dev.streetsnap.app

import dev.streetsnap.app.composition.AdviceOrigin
import dev.streetsnap.app.composition.CompositionEasterEgg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionEasterEggTest {
    private val eligible = CompositionEasterEgg.decide(
        subjectLooksDistant = true, AdviceOrigin.IMAGE_ANALYSIS, focalLengthMm = 28,
        draw = 0, nowMs = 100_000L, lastEggAtMs = 0L)

    @Test fun eligibleWideAngleDistantSubjectProducesANudge() {
        assertNotNull(eligible)
        assertTrue(eligible!!.contains("28mm") || eligible.contains("广角"))
    }

    @Test fun nonDistantScenesAndModelSelectionsAreSilent() {
        assertNull(CompositionEasterEgg.decide(false, AdviceOrigin.IMAGE_ANALYSIS, 28, 0, 100_000L, 0L))
        assertNull(CompositionEasterEgg.decide(true, AdviceOrigin.MODEL_SELECTION, 28, 0, 100_000L, 0L))
    }

    @Test fun telephotoAndAbsentFocalLengthAreSilent() {
        assertNull(CompositionEasterEgg.decide(true, AdviceOrigin.IMAGE_ANALYSIS, 50, 0, 100_000L, 0L))
        assertNull(CompositionEasterEgg.decide(true, AdviceOrigin.IMAGE_ANALYSIS, 0, 0, 100_000L, 0L))
    }

    @Test fun onlyOneFifthOfDrawsPassBeforeCooldown() {
        var shown = 0
        for (draw in 0 until 20) {
            if (CompositionEasterEgg.decide(true, AdviceOrigin.IMAGE_ANALYSIS, 28,
                    draw, 1_000_000L + draw, 0L) != null) shown++
        }
        assertEquals(4, shown)
    }

    @Test fun cooldownSuppressesEvenEligibleDraws() {
        val first = CompositionEasterEgg.decide(true, AdviceOrigin.IMAGE_ANALYSIS, 28, 0, 100_000L, 0L)
        assertNotNull(first)
        assertNull(CompositionEasterEgg.decide(true, AdviceOrigin.IMAGE_ANALYSIS, 28, 5, 120_000L, 100_000L))
        assertNotNull(CompositionEasterEgg.decide(true, AdviceOrigin.IMAGE_ANALYSIS, 28, 10, 200_000L, 100_000L))
    }
}
