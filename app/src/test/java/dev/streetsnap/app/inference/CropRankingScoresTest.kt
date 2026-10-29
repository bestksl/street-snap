package dev.streetsnap.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropRankingScoresTest {
    private val labels = intArrayOf(1, 2, 3)

    @Test fun selectsSupportedLabelAndReturnsCandidateIndex() {
        val result = requireNotNull(CropRankingScores.fromLogits(floatArrayOf(0f, 1f, 4f, 0f), labels))
        assertEquals(1, result.selectedIndex)
        assertTrue(result.support > .9f)
        assertTrue(result.margin > .8f)
        assertEquals(1f, result.probabilities.sum(), .00001f)
        assertEquals(listOf(1f, 4f, 0f), result.rawScores)
    }

    @Test fun abstainsWhenVocabularyPrefersAnUnrelatedToken() {
        val result = requireNotNull(CropRankingScores.fromLogits(floatArrayOf(30f, 0f, 1f, 10f), labels))
        assertTrue(result.probabilities[2] > .99f)
        assertTrue(result.support < CropRankingScores.MIN_SUPPORT)
        assertNull(result.selectedIndex)
    }

    @Test fun abstainsOnAnAmbiguousChoiceDespiteHighLabelSupport() {
        val result = requireNotNull(CropRankingScores.fromLogits(floatArrayOf(-20f, 2f, 2f, 0f), labels))
        assertTrue(result.support > .99f)
        assertEquals(0f, result.margin, 0f)
        assertNull(result.selectedIndex)
    }

    @Test fun largeLogitsRemainStableAndPermutationFollowsLabels() {
        val logits = floatArrayOf(9990f, 10000f, 9990f, 9990f)
        val result = requireNotNull(CropRankingScores.fromLogits(logits, labels))
        assertEquals(0, result.selectedIndex)
        assertTrue(result.support.isFinite())
        val reordered = requireNotNull(CropRankingScores.fromLogits(logits, intArrayOf(3, 2, 1)))
        assertEquals(2, reordered.selectedIndex)
        assertEquals(result.support, reordered.support, .00001f)
    }

    @Test fun invalidModelNumbersNeverProduceAChoice() {
        assertNull(CropRankingScores.fromLogits(floatArrayOf(0f, Float.NaN, 1f, 2f), labels))
        assertNull(CropRankingScores.fromLogits(floatArrayOf(Float.POSITIVE_INFINITY, 0f, 1f, 2f), labels))
        assertNotNull(CropRankingScores.fromLogits(floatArrayOf(0f, -10000f, -9999f, -9998f), labels))
    }
}
