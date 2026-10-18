package dev.streetsnap.app.edge

import kotlin.math.exp

/** A/B/C refer to the caller's contact sheet, never to a newly generated rectangle. */
data class CropRanking(
    val selectedIndex: Int?,
    /** Probability mass of all three allowed labels in the complete vocabulary. */
    val support: Float,
    /** Difference between the two largest probabilities, conditional on A/B/C. */
    val margin: Float,
    val probabilities: List<Float>,
    val rawScores: List<Float>,
)

/** Keep abstention separate from constrained decoding, which always produces a winner. */
internal object CropRankingScores {
    // Initial experimental gates; these are observable diagnostics, not calibrated
    // probabilities of aesthetic quality. Validate with candidate permutations.
    const val MIN_SUPPORT = .10f
    const val MIN_MARGIN = .10f

    fun fromLogits(logits: FloatArray, labelTokenIds: IntArray): CropRanking? {
        require(labelTokenIds.size == 3 && labelTokenIds.distinct().size == 3)
        require(labelTokenIds.all { it in logits.indices })
        if (logits.any { !it.isFinite() }) return null
        val raw = labelTokenIds.map { logits[it] }
        val globalMaximum = logits.maxOrNull()?.toDouble() ?: return null
        val vocabularyMass = logits.sumOf { exp(it.toDouble() - globalMaximum) }
        val labelMass = raw.sumOf { exp(it.toDouble() - globalMaximum) }
        val support = (labelMass / vocabularyMass).toFloat().coerceIn(0f, 1f)
        // Normalize labels independently so their relative probabilities remain
        // meaningful even when their full-vocabulary support underflows to zero.
        val labelMaximum = raw.max().toDouble()
        val relative = raw.map { exp(it.toDouble() - labelMaximum) }
        val relativeMass = relative.sum()
        val probabilities = relative.map { (it / relativeMass).toFloat() }
        val order = probabilities.indices.sortedByDescending { probabilities[it] }
        val margin = probabilities[order[0]] - probabilities[order[1]]
        val selected = order[0].takeIf { support >= MIN_SUPPORT && margin >= MIN_MARGIN }
        return CropRanking(selected, support, margin, probabilities, raw)
    }
}
