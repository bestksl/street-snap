package dev.streetsnap.camera

/**
 * Coarse exposure statistics derived from a luma histogram. Ratios are shares of the
 * sampled pixels, so thresholds stay comparable across frame sizes. Everything here is
 * pure Kotlin: feed a synthetic histogram in unit tests, no Android types involved.
 */
data class ExposureStats(
    val sampleCount: Long,
    /** Share of pixels in the brightest histogram bin (with 48 bins: luma 251..255). */
    val highlightRatio: Double,
    /** Share of pixels in the darkest histogram bin (with 48 bins: luma 0..5). */
    val shadowRatio: Double,
    /** Histogram-weighted mean luma in 0..1. */
    val meanLuma: Double,
) {
    companion object {
        fun from(counts: IntArray): ExposureStats {
            var total = 0L
            var weighted = 0.0
            for (index in counts.indices) {
                val count = counts[index]
                if (count < 0) return EMPTY
                total += count
                // Bin centre in 0..255; bin k covers [k*256/bins, (k+1)*256/bins).
                weighted += count * (index + 0.5) * 256.0 / counts.size
            }
            if (total <= 0L) return EMPTY
            val highlights = counts.last().toDouble()
            val shadows = counts.first().toDouble()
            return ExposureStats(
                sampleCount = total,
                highlightRatio = highlights / total,
                shadowRatio = shadows / total,
                meanLuma = weighted / total / 255.0,
            )
        }

        val EMPTY = ExposureStats(0L, 0.0, 0.0, 0.0)
    }
}

/** One histogram delivery: the UI bins plus the exposure statistics over the same frame. */
data class HistogramSample(
    val counts: IntArray,
    val stats: ExposureStats,
) {
    // IntArray identity equals would miss equal-content samples; content comparison instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HistogramSample) return false
        return counts.contentEquals(other.counts) && stats == other.stats
    }

    override fun hashCode(): Int = 31 * counts.contentHashCode() + stats.hashCode()
}
