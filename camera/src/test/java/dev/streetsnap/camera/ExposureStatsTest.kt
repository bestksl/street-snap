package dev.streetsnap.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureStatsTest {

    private val bins = 48

    private fun histogram(block: (IntArray) -> Unit): IntArray = IntArray(bins).also(block)

    @Test fun emptyHistogramYieldsEmptyStats() {
        val stats = ExposureStats.from(IntArray(bins))
        assertEquals(ExposureStats.EMPTY, stats)
        assertEquals(0L, stats.sampleCount)
        assertEquals(0.0, stats.highlightRatio, 0.0)
        assertEquals(0.0, stats.shadowRatio, 0.0)
    }

    @Test fun topBinPixelsReadAsHighlightClipping() {
        // With 48 bins the top bin (index 47) covers luma 251..255.
        val counts = histogram { it[47] = 100 }
        val stats = ExposureStats.from(counts)
        assertEquals(100L, stats.sampleCount)
        assertEquals(1.0, stats.highlightRatio, 0.0)
        assertEquals(0.0, stats.shadowRatio, 0.0)
        assertTrue("meanLuma=${stats.meanLuma}", stats.meanLuma > 0.97)
    }

    @Test fun bottomBinPixelsReadAsShadowClipping() {
        // The bottom bin covers luma 0..5.
        val counts = histogram { it[0] = 150 }
        val stats = ExposureStats.from(counts)
        assertEquals(1.0, stats.shadowRatio, 0.0)
        assertEquals(0.0, stats.highlightRatio, 0.0)
        assertTrue("meanLuma=${stats.meanLuma}", stats.meanLuma < 0.03)
    }

    @Test fun mixedHistogramComputesRatiosAndWeightedMean() {
        // 4% blown highlights, 16% crushed shadows, 80% midtones at bin 24 (~luma 130).
        val counts = histogram {
            it[47] = 40
            it[0] = 160
            it[24] = 800
        }
        val stats = ExposureStats.from(counts)
        assertEquals(1000L, stats.sampleCount)
        assertEquals(0.04, stats.highlightRatio, 1e-9)
        assertEquals(0.16, stats.shadowRatio, 1e-9)
        // Midtones dominate: mean sits near 0.5, pulled slightly by the clipped ends.
        assertTrue("meanLuma=${stats.meanLuma}", stats.meanLuma in 0.45..0.55)
    }

    @Test fun ratiosAreSizeIndependentForTheSameDistribution() {
        val small = histogram {
            it[47] = 4
            it[0] = 15
            it[20] = 81
        }
        val large = histogram {
            it[47] = 4_000
            it[0] = 15_000
            it[20] = 81_000
        }
        val a = ExposureStats.from(small)
        val b = ExposureStats.from(large)
        assertEquals(a.highlightRatio, b.highlightRatio, 1e-12)
        assertEquals(a.shadowRatio, b.shadowRatio, 1e-12)
        assertEquals(a.meanLuma, b.meanLuma, 1e-12)
    }

    @Test fun midGreyAtMiddleBinIsNearNeutralLuma() {
        val counts = histogram { it[24] = 1000 }
        val stats = ExposureStats.from(counts)
        // Centre of bin 24 with 48 bins: 24.5*256/48 ≈ 130.7 → ~0.51.
        assertEquals(0.512, stats.meanLuma, 0.01)
        assertEquals(0.0, stats.highlightRatio, 0.0)
        assertEquals(0.0, stats.shadowRatio, 0.0)
    }
}
