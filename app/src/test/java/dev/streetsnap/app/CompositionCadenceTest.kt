package dev.streetsnap.app

import dev.streetsnap.app.composition.CompositionCadence

import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionCadenceTest {
    @Test fun firstFrameGetsThirtySecondsAndRestStartsAtThreeSeconds() {
        val cadence = CompositionCadence()
        assertEquals(30_000L, cadence.budgetMs())
        assertEquals(3_000L, cadence.cooldownMs())
    }

    @Test fun twentySecondInferenceCompletesAndLeavesMeasuredHeadroom() {
        val cadence = CompositionCadence()
        val result = cadence.recordCompleted(20_000L, hasUsableResult = true)
        assertEquals(CompositionCadence.Outcome.COMPLETED_WITH_RESULT, result.outcome)
        assertEquals(20_000L, result.elapsedMs)
        assertEquals(32_000L, result.nextBudgetMs)
        assertEquals(10_000L, result.cooldownMs)
        assertEquals(result.nextBudgetMs, cadence.budgetMs())
        assertEquals(result.cooldownMs, cadence.cooldownMs())
    }

    @Test fun unusableAnswerStillCountsAsCompletedInference() {
        val cadence = CompositionCadence()
        val result = cadence.recordCompleted(20_000L, hasUsableResult = false)
        assertEquals(CompositionCadence.Outcome.COMPLETED_NO_RESULT, result.outcome)
        assertEquals(32_000L, result.nextBudgetMs)
        assertEquals(10_000L, result.cooldownMs)
    }

    @Test fun timeoutAllowsALongerRetryAfterRest() {
        val cadence = CompositionCadence()
        val result = cadence.recordTimeout(30_000L)
        assertEquals(CompositionCadence.Outcome.TIMED_OUT, result.outcome)
        assertEquals(30_000L, result.elapsedMs)
        assertEquals(60_000L, result.nextBudgetMs)
        assertEquals(15_000L, result.cooldownMs)
    }

    @Test fun repeatedTimeoutsRemainBounded() {
        val cadence = CompositionCadence()
        repeat(10) {
            val result = cadence.recordTimeout(cadence.budgetMs())
            assertEquals(60_000L, result.nextBudgetMs)
            assertEquals(15_000L, result.cooldownMs)
        }
    }

    @Test fun fastCompletionRecoversFromTimeoutBackoff() {
        val cadence = CompositionCadence()
        cadence.recordTimeout(30_000L)
        val result = cadence.recordCompleted(2_000L, hasUsableResult = true)
        assertEquals(CompositionCadence.Outcome.COMPLETED_WITH_RESULT, result.outcome)
        assertEquals(30_000L, result.nextBudgetMs)
        assertEquals(3_000L, result.cooldownMs)
    }

    @Test fun slowCompletionNeverExceedsMaximumBudgetOrCooldown() {
        val cadence = CompositionCadence()
        val result = cadence.recordCompleted(50_000L, hasUsableResult = true)
        assertEquals(CompositionCadence.Outcome.COMPLETED_WITH_RESULT, result.outcome)
        assertEquals(60_000L, result.nextBudgetMs)
        assertEquals(15_000L, result.cooldownMs)
    }

    @Test fun extremeElapsedTimeDoesNotOverflowOrBecomeATimeout() {
        val cadence = CompositionCadence()
        val result = cadence.recordCompleted(Long.MAX_VALUE, hasUsableResult = false)
        assertEquals(CompositionCadence.Outcome.COMPLETED_NO_RESULT, result.outcome)
        assertEquals(60_000L, result.nextBudgetMs)
        assertEquals(15_000L, result.cooldownMs)
    }

    @Test fun resetRestoresFirstFramePolicy() {
        val cadence = CompositionCadence()
        cadence.recordTimeout(30_000L)
        cadence.reset()
        assertEquals(30_000L, cadence.budgetMs())
        assertEquals(3_000L, cadence.cooldownMs())
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeElapsedTimeIsRejected() {
        CompositionCadence().recordCompleted(-1L, hasUsableResult = true)
    }
}
