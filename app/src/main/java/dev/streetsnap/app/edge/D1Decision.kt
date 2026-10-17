package dev.streetsnap.app.edge

import kotlin.math.exp

/**
 * Turns d1's marker logits into the app's shared CropRanking shape.
 *
 * d1 evaluates only the option markers, so the softmax support over A/B/C is always 1.0;
 * the meaningful abstention signal is the top-1/top-2 margin, gated with the same
 * experimental threshold as the SmolVLM path. Image answers deliberately skip the
 * per-question-type temperature calibration (official prompt.py, image path).
 */
internal object D1Decision {
    fun toRanking(logits: FloatArray): CropRanking? {
        require(logits.size in 2..10) { "d1 returned ${logits.size} option scores" }
        if (logits.any { !it.isFinite() }) return null
        val maximum = logits.max()
        val relative = logits.map { exp((it - maximum).toDouble()).toFloat() }
        val mass = relative.sum()
        val probabilities = relative.map { it / mass }
        val order = probabilities.indices.sortedByDescending { probabilities[it] }
        val margin = probabilities[order[0]] - probabilities[order[1]]
        val selected = order[0].takeIf { margin >= CropRankingScores.MIN_MARGIN }
        return CropRanking(selected, support = 1f, margin = margin,
            probabilities = probabilities, rawScores = logits.toList())
    }
}
