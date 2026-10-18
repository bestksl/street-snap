package dev.streetsnap.app.composition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneReadingsTest {

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun frame(w: Int, h: Int, pick: (x: Int, y: Int) -> Int): IntArray =
        IntArray(w * h) { i -> pick(i % w, i / w) }

    private fun uniform(w: Int, h: Int, r: Int, g: Int, b: Int) =
        frame(w, h) { _, _ -> rgb(r, g, b) }

    private fun horizontal(w: Int, h: Int, top: Int, bottom: Int) =
        frame(w, h) { _, y -> if (y * 2 < h) top else bottom }

    // 40x30 stays at 1:1 sampling (max edge 40 < 96), so synthetic pixels map exactly.
    private val w = 40
    private val h = 30

    @Test
    fun warmBrightSceneReadsWarmAndDaytime() {
        val reading = SceneReadings.read(w, h, uniform(w, h, 220, 185, 150))
        assertEquals(ColorWarmth.WARM, reading.warmth)
        assertTrue("warmthIndex=${reading.warmthIndex}", reading.warmthIndex > 0.06)
        assertEquals(DayPhase.DAY, reading.dayPhase)
        assertTrue(reading.meanLuma > 0.55)
        // A coloured field has within-pixel channel spread even though it is flat.
        assertTrue(reading.saturation > 0.25)
        assertEquals(0.0, reading.highlightClipping, 0.0)
        assertEquals(0.0, reading.shadowClipping, 0.0)
        assertEquals(0.0, reading.skyRatioUpper, 0.0)
        assertEquals(0.0, reading.foliageRatioUpper, 0.0)
    }

    @Test
    fun coolBlueFieldReadsCoolAndSkyInUpperHalf() {
        val reading = SceneReadings.read(w, h, uniform(w, h, 140, 172, 222))
        assertEquals(ColorWarmth.COOL, reading.warmth)
        assertTrue(reading.warmthIndex < -0.06)
        assertEquals(1.0, reading.skyRatioUpper, 0.01)
        assertEquals(0.0, reading.foliageRatioUpper, 0.0)
    }

    @Test
    fun neutralGrayIsNeutralZeroSaturationUnknownPhase() {
        val reading = SceneReadings.read(w, h, uniform(w, h, 128, 128, 128))
        assertEquals(ColorWarmth.NEUTRAL, reading.warmth)
        assertEquals(0.0, reading.warmthIndex, 0.002)
        assertEquals(0.0, reading.saturation, 0.001)
        assertEquals(DayPhase.UNKNOWN, reading.dayPhase)
        assertEquals(0.0, reading.skyRatioUpper, 0.0)
        assertEquals(0.0, reading.foliageRatioUpper, 0.0)
    }

    @Test
    fun blueSkyOverGroundCountsOnlyUpperHalf() {
        val pixels = horizontal(w, h, rgb(125, 165, 225), rgb(120, 110, 95))
        val reading = SceneReadings.read(w, h, pixels)
        assertEquals(1.0, reading.skyRatioUpper, 0.01)
        assertEquals(0.0, reading.foliageRatioUpper, 0.0)
        assertEquals(ColorWarmth.COOL, reading.warmth)
    }

    @Test
    fun greenTopReadsFoliageAndNotSky() {
        val pixels = horizontal(w, h, rgb(60, 140, 70), rgb(90, 90, 90))
        val reading = SceneReadings.read(w, h, pixels)
        assertEquals(1.0, reading.foliageRatioUpper, 0.01)
        assertEquals(0.0, reading.skyRatioUpper, 0.0)
    }

    @Test
    fun nearBlackFieldReadsNightAndShadowClipping() {
        val reading = SceneReadings.read(w, h, uniform(w, h, 8, 8, 10))
        assertEquals(DayPhase.NIGHT, reading.dayPhase)
        assertTrue(reading.meanLuma < SceneReadings.NIGHT_LUMA)
        assertEquals(1.0, reading.shadowClipping, 0.01)
        assertEquals(0.0, reading.highlightClipping, 0.0)
    }

    @Test
    fun warmDimFieldReadsDusk() {
        val pixels = horizontal(w, h, rgb(238, 150, 64), rgb(64, 42, 30))
        val reading = SceneReadings.read(w, h, pixels)
        assertEquals(DayPhase.DUSK, reading.dayPhase)
        assertTrue("warmthIndex=${reading.warmthIndex}", reading.warmthIndex >= SceneReadings.DUSK_WARMTH)
        assertTrue(reading.saturation > 0.3)
    }

    @Test
    fun splitBlackWhiteHasHighContrastAndBothClippings() {
        val pixels = frame(w, h) { x, _ -> if (x * 2 < w) rgb(0, 0, 0) else rgb(255, 255, 255) }
        val reading = SceneReadings.read(w, h, pixels)
        assertEquals(0.5, reading.contrast, 0.02)
        assertEquals(0.5, reading.highlightClipping, 0.02)
        assertEquals(0.5, reading.shadowClipping, 0.02)
    }

    @Test
    fun overcastWhiteIsNotMistakenForSky() {
        val reading = SceneReadings.read(w, h, uniform(w, h, 225, 228, 232))
        assertEquals(ColorWarmth.NEUTRAL, reading.warmth)
        assertEquals(0.0, reading.skyRatioUpper, 0.0)
        assertEquals(DayPhase.DAY, reading.dayPhase)
    }

    @Test
    fun sameInputIsDeterministicAndDownsamplingKeepsCategories() {
        val small = horizontal(w, h, rgb(125, 165, 225), rgb(120, 110, 95))
        val first = SceneReadings.read(w, h, small)
        val second = SceneReadings.read(w, h, small)
        assertEquals(first, second)
        // 192x144 downsamples to 96x72; the upper half stays solid sky blue.
        val large = horizontal(192, 144, rgb(125, 165, 225), rgb(120, 110, 95))
        val largeReading = SceneReadings.read(192, 144, large)
        assertEquals(96, largeReading.sampleWidth)
        assertEquals(72, largeReading.sampleHeight)
        assertTrue("sky=${largeReading.skyRatioUpper}", largeReading.skyRatioUpper > 0.95)
    }

    @Test
    fun rejectsInvalidInput() {
        assertThrows(IllegalArgumentException::class.java) { SceneReadings.read(0, h, IntArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { SceneReadings.read(w, h, IntArray(10)) }
    }
}
