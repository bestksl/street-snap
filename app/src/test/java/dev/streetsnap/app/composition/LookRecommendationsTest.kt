package dev.streetsnap.app.composition

import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.FilmRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookRecommendationsTest {

    private fun reading(
        dayPhase: DayPhase = DayPhase.DAY,
        warmth: ColorWarmth = ColorWarmth.NEUTRAL,
        saturation: Double = 0.30,
        contrast: Double = 0.22,
        sky: Double = 0.0,
        foliage: Double = 0.0,
        meanLuma: Double = 0.50,
        warmthIndex: Double = 0.0,
    ) = SceneReading(
        sampleWidth = 40, sampleHeight = 30,
        meanLuma = meanLuma, warmthIndex = warmthIndex, warmth = warmth,
        saturation = saturation, contrast = contrast,
        highlightClipping = 0.0, shadowClipping = 0.0,
        skyRatioUpper = sky, foliageRatioUpper = foliage, dayPhase = dayPhase,
    )

    @Test fun nightWithStrongContrastMapsToTriX() {
        val target = LookRecommendations.from(
            reading(dayPhase = DayPhase.NIGHT, contrast = LookRecommendations.NIGHT_CONTRAST, meanLuma = 0.12))
        val trix = requireNotNull(FilmRegistry.find("MONO_01"))
        assertEquals("FILM:MONO_01", target!!.key)
        assertEquals(trix.id, target.filmId)
        assertEquals(trix.basePreset, target.preset)
        assertEquals(trix.displayName, target.label)
        assertTrue(target.reason.contains("黑白"))
    }

    @Test fun flatDarkNightAbstains() {
        // Contrast below the threshold: black-and-white is a claim, so darkness alone is not enough.
        assertNull(LookRecommendations.from(
            reading(dayPhase = DayPhase.NIGHT, contrast = LookRecommendations.NIGHT_CONTRAST - 0.01, meanLuma = 0.10)))
    }

    @Test fun duskMapsToClassicNegative() {
        val target = LookRecommendations.from(reading(dayPhase = DayPhase.DUSK, warmth = ColorWarmth.WARM))
        assertEquals(ColorPreset.CLASSIC_NEGATIVE, target!!.preset)
        assertEquals(ColorPreset.CLASSIC_NEGATIVE.name, target.key)
        assertNull(target.filmId)
    }

    @Test fun sunnyDayWithSkyMapsToPositiveWithWarmWording() {
        val warm = LookRecommendations.from(reading(
            dayPhase = DayPhase.DAY, warmth = ColorWarmth.WARM, sky = LookRecommendations.SKY_STRONG))
        assertEquals(ColorPreset.POSITIVE, warm!!.preset)
        assertTrue(warm.reason.contains("暖"))
        val cool = LookRecommendations.from(reading(
            dayPhase = DayPhase.DAY, warmth = ColorWarmth.COOL, sky = 0.6))
        assertEquals(ColorPreset.POSITIVE, cool!!.preset)
        assertTrue(cool.reason.contains("蓝"))
    }

    @Test fun skyBelowThresholdDoesNotFirePositive() {
        assertNull(LookRecommendations.from(reading(
            dayPhase = DayPhase.DAY, warmth = ColorWarmth.WARM, sky = LookRecommendations.SKY_STRONG - 0.01)))
    }

    @Test fun coolFoliageMapsToClassicChrome() {
        val target = LookRecommendations.from(reading(
            dayPhase = DayPhase.DAY, warmth = ColorWarmth.COOL,
            foliage = LookRecommendations.FOLIAGE_STRONG, sky = LookRecommendations.FOLIAGE_MILD - 0.01))
        assertEquals(ColorPreset.CLASSIC_CHROME, target!!.preset)
    }

    @Test fun warmFoliageAndTwilightFoliageAbstainFromChrome() {
        // Warm light wins over the green reading: no Classic Chrome suggestion.
        assertNull(LookRecommendations.from(reading(
            dayPhase = DayPhase.DAY, warmth = ColorWarmth.WARM, foliage = 0.9)))
        // DUSK has its own higher-priority mapping, so foliage never overrides it.
        assertEquals(ColorPreset.CLASSIC_NEGATIVE, LookRecommendations.from(reading(
            dayPhase = DayPhase.DUSK, warmth = ColorWarmth.WARM, foliage = 0.9))!!.preset)
    }

    @Test fun overcastFlatDayMapsToPowderBlue() {
        val target = LookRecommendations.from(reading(
            dayPhase = DayPhase.DAY, warmth = ColorWarmth.COOL,
            saturation = LookRecommendations.LOW_SATURATION,
            contrast = LookRecommendations.FLAT_CONTRAST - 0.01))
        assertEquals(ColorPreset.POWDER_BLUE, target!!.preset)
    }

    @Test fun vividOrContrastyDayDoesNotMapToPowderBlue() {
        assertNull(LookRecommendations.from(reading(
            dayPhase = DayPhase.DAY, saturation = 0.25, contrast = 0.05)))
        assertNull(LookRecommendations.from(reading(
            dayPhase = DayPhase.DAY, saturation = 0.05, contrast = LookRecommendations.FLAT_CONTRAST)))
    }

    @Test fun ambiguousIndoorScenesAbstain() {
        // Neutral grey, unknown phase: no evidence, no suggestion.
        assertNull(LookRecommendations.from(reading()))
    }
}
