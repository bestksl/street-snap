package dev.streetsnap.app.inference

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class InferenceStatsTest {
    @Before
    fun reset() = InferenceStats.resetForTests()

    @After
    fun cleanUp() = InferenceStats.resetForTests()

    private fun snapshot() = InferenceStats.snapshots.value

    @Test
    fun startsEmpty() {
        val s = snapshot()
        assertEquals(0, s.rankingsFinished)
        assertNull(s.averageRankMs)
        assertNull(s.firstRankMs)
        assertNull(s.adoptionRate)
        assertNull(s.currentForegroundMs)
        assertEquals(0, s.foregroundSessions)
    }

    @Test
    fun rankingsAreBucketedAndAveraged() {
        InferenceStats.recordRanking(InferenceStats.RankOutcome.ADOPTED, 10_000L, 8_000L, 200L, 900L, 217, nowMs = 1)
        InferenceStats.recordRanking(InferenceStats.RankOutcome.ADOPTED, 20_000L, 8_000L, 200L, 900L, 217, nowMs = 2)
        InferenceStats.recordRanking(InferenceStats.RankOutcome.ABSTAINED, 5_000L, 4_000L, 100L, 300L, 217, nowMs = 3)
        InferenceStats.recordRanking(InferenceStats.RankOutcome.TIMED_OUT, 30_000L, null, null, null, null, nowMs = 4)
        InferenceStats.recordRanking(InferenceStats.RankOutcome.FAILED, null, null, null, null, null, nowMs = 5)

        val s = snapshot()
        assertEquals(5, s.rankingsFinished)
        assertEquals(2, s.adopted)
        assertEquals(1, s.abstained)
        assertEquals(1, s.timedOut)
        assertEquals(1, s.failed)
        // Failure carries no duration; the four known durations average to 16.25 s.
        assertEquals(4, s.timedRankings)
        assertEquals(65_000L / 4L, s.averageRankMs)
        assertEquals(10_000L, s.firstRankMs)
        assertEquals(.4f, s.adoptionRate!!, 1e-6f)
        // The latest pass that carried diagnostics wins; later timeout/failure keep them.
        assertEquals(4_000L, s.lastVisionMs)
        assertEquals(100L, s.lastEmbeddingsMs)
        assertEquals(300L, s.lastDecisionMs)
        assertEquals(217, s.lastPromptTokens)
    }

    @Test
    fun notMeasuredSentinelsDoNotOverwriteLatestPass() {
        InferenceStats.recordRanking(InferenceStats.RankOutcome.ADOPTED, 10_000L, 8_000L, 200L, 900L, 217, nowMs = 1)
        InferenceStats.recordRanking(InferenceStats.RankOutcome.TIMED_OUT, 30_000L, -1L, -1L, -1L, -1, nowMs = 2)
        val s = snapshot()
        assertEquals(8_000L, s.lastVisionMs)
        assertEquals(200L, s.lastEmbeddingsMs)
        assertEquals(900L, s.lastDecisionMs)
        assertEquals(217, s.lastPromptTokens)
    }

    @Test
    fun foregroundSessionsAreCountedAndAveraged() {
        InferenceStats.onForeground(true, nowMs = 0L)
        assertEquals(5_000L, InferenceStats.currentForegroundDurationMs(5_000L))
        // Duplicate resume must not restart the open session.
        InferenceStats.onForeground(true, nowMs = 2_000L)
        assertEquals(10_000L, InferenceStats.currentForegroundDurationMs(10_000L))
        InferenceStats.onForeground(false, nowMs = 10_000L)
        assertNull(InferenceStats.currentForegroundDurationMs(99_000L))
        // Duplicate pause is ignored.
        InferenceStats.onForeground(false, nowMs = 12_000L)
        InferenceStats.onForeground(true, nowMs = 20_000L)
        assertEquals(6_000L, InferenceStats.currentForegroundDurationMs(26_000L))
        InferenceStats.onForeground(false, nowMs = 30_000L)

        val s = snapshot()
        assertEquals(2, s.foregroundSessions)
        // (10 s + 10 s) / 2
        assertEquals(10_000L, s.averageForegroundMs)
        assertNull(s.currentForegroundMs)
    }

    @Test
    fun nonMonotonicClockIsClampedForOpenSession() {
        InferenceStats.onForeground(true, nowMs = 1_000L)
        // A backwards test clock must not publish a negative open-session length.
        assertEquals(0L, InferenceStats.currentForegroundDurationMs(0L))
        InferenceStats.onForeground(false, nowMs = 500L)
        val s = snapshot()
        assertEquals(1, s.foregroundSessions)
        assertEquals(0L, s.averageForegroundMs)
    }
}
