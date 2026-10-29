package dev.streetsnap.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class D1DecisionTest {
    @Test
    fun convertsDecisiveLogitsToSelection() {
        val ranking = requireNotNull(D1Decision.toRanking(floatArrayOf(5f, 0f, 0f)))
        assertEquals(0, ranking.selectedIndex)
        assertEquals(1f, ranking.support, 0f)
        assertEquals(1f, ranking.probabilities.sum(), 1e-5f)
        assertTrue(ranking.probabilities[0] >= .98f)
        assertTrue(ranking.margin > CropRankingScores.MIN_MARGIN)
        assertEquals(listOf(5f, 0f, 0f), ranking.rawScores)
    }

    @Test
    fun selectsTheArgmaxIndexEvenWhenNotFirst() {
        val ranking = requireNotNull(D1Decision.toRanking(floatArrayOf(0f, 4f, 0.5f)))
        assertEquals(1, ranking.selectedIndex)
        assertTrue(ranking.probabilities[1] > ranking.probabilities[0])
    }

    @Test
    fun abstainsOnExactTie() {
        val ranking = requireNotNull(D1Decision.toRanking(floatArrayOf(1f, 1f, 1f)))
        assertNull(ranking.selectedIndex)
        assertEquals(0f, ranking.margin, 0f)
        assertEquals(listOf(1f / 3f, 1f / 3f, 1f / 3f), ranking.probabilities)
    }

    @Test
    fun abstainsWhenMarginIsBelowGate() {
        // softmax(0.1, 0, 0): top - second ~= 0.034, well inside the abstention margin.
        val ranking = requireNotNull(D1Decision.toRanking(floatArrayOf(0.1f, 0f, 0f)))
        assertNull(ranking.selectedIndex)
        assertTrue(ranking.margin >= 0f)
        assertTrue(ranking.margin < CropRankingScores.MIN_MARGIN)
    }

    @Test
    fun marginGateCrossesAroundExpectedSoftmaxGap() {
        // softmax gap ~= 0.22, comfortably above the 0.10 gate
        val delta = 0.45f
        val ranking = requireNotNull(D1Decision.toRanking(floatArrayOf(delta, 0f, -8f)))
        val denom = exp(delta.toDouble()) + 1.0 + exp(-8.0)
        val expectedMargin = (exp(delta.toDouble()) - 1.0) / denom
        assertEquals(expectedMargin, ranking.margin.toDouble(), 1e-4)
        assertEquals(0, ranking.selectedIndex)
    }

    @Test
    fun nonFiniteScoresReturnNull() {
        assertNull(D1Decision.toRanking(floatArrayOf(Float.NaN, 1f, 0f)))
        assertNull(D1Decision.toRanking(floatArrayOf(Float.POSITIVE_INFINITY, 1f, 0f)))
        assertNull(D1Decision.toRanking(floatArrayOf(0f, Float.NEGATIVE_INFINITY, 0f)))
    }

    @Test
    fun rejectsMarkerCountOutsideContract() {
        assertThrows(IllegalArgumentException::class.java) { D1Decision.toRanking(floatArrayOf(1f)) }
        assertThrows(IllegalArgumentException::class.java) {
            D1Decision.toRanking(FloatArray(11) { it.toFloat() })
        }
    }

    @Test
    fun diagnosticsDefaultToNotMeasuredSentinels() {
        val ranking = requireNotNull(D1Decision.toRanking(floatArrayOf(4f, 0f, 0f)))
        assertEquals(-1L, ranking.visionMs)
        assertEquals(-1L, ranking.embeddingsMs)
        assertEquals(-1L, ranking.decisionMs)
        assertEquals(-1, ranking.promptTokens)
    }

    @Test
    fun perPassDiagnosticsArePassedThrough() {
        val ranking = requireNotNull(D1Decision.toRanking(
            floatArrayOf(4f, 0f, 0f),
            visionMs = 12_345L,
            embeddingsMs = 40L,
            decisionMs = 88L,
            promptTokens = 217,
        ))
        assertEquals(12_345L, ranking.visionMs)
        assertEquals(40L, ranking.embeddingsMs)
        assertEquals(88L, ranking.decisionMs)
        assertEquals(217, ranking.promptTokens)
    }

    @Test
    fun supportsTwoToTenMarkers() {
        val ranking = requireNotNull(D1Decision.toRanking(floatArrayOf(4f, 0f)))
        assertEquals(0, ranking.selectedIndex)
        assertEquals(2, ranking.probabilities.size)

        val many = requireNotNull(D1Decision.toRanking(FloatArray(10) { if (it == 7) 5f else 0f }))
        assertEquals(7, many.selectedIndex)
        assertEquals(10, many.probabilities.size)
    }
}
