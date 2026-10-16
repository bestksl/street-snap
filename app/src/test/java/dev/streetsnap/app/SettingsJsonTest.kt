package dev.streetsnap.app

import dev.streetsnap.color.ColorPreset
import org.junit.Assert.*
import org.junit.Test

class SettingsJsonTest {
    @Test fun upgradeKeepsTheOldLookAndDoesNotApplyItsEditsToOtherLooks() {
        val old = """{"version":1,"preset":"SUNNY","intensity":0.6,"grain":0.3,"vignette":0.2,"focalLength":35,"iso":400,"exposureTimeNs":8000000}"""
        val loaded = SettingsJson.decode(old)
        assertEquals(LookSettings(intensity = .6f, grain = .3f, vignette = .2f), loaded.activeLook)
        assertEquals(LookSettings(), loaded.copy(preset = "POSITIVE").activeLook)
        assertEquals(35, loaded.focalLength)
        assertEquals(400, loaded.iso)
        assertEquals(8_000_000L, loaded.exposureTimeNs)
        assertEquals(loaded, SettingsJson.decode(SettingsJson.encode(loaded)))
    }

    @Test fun switchingLooksAndRestartingPreservesTheirIndependentEdits() {
        var settings = AppSettings(preset = "POSITIVE")
            .updateLook { it.copy(saturation = .5f, sharpness = -.25f) }
        settings = settings.copy(preset = "POWDER_BLUE").updateLook { it.copy(contrast = -.5f, hue = .25f) }
        val loaded = SettingsJson.decode(SettingsJson.encode(settings))
        assertEquals(-.5f, loaded.activeLook.contrast, 0f)
        assertEquals(.5f, loaded.copy(preset = "POSITIVE").activeLook.saturation, 0f)
        assertEquals(-.25f, loaded.copy(preset = "POSITIVE").activeLook.sharpness, 0f)
        assertEquals(LookSettings(), loaded.copy(preset = "NEGATIVE").activeLook)
    }

    @Test fun resetOnlyChangesTheSelectedLook() {
        val settings = AppSettings(preset = "NEGATIVE", whiteBalance = "CLOUDY", whiteBalanceAmber = 3, hdfEnabled = true)
            .updateLook { it.copy(shadows = .5f) }
            .copy(preset = "MONO").updateLook { it.copy(contrast = .75f) }
            .resetLook()
        assertEquals(LookSettings(), settings.activeLook)
        assertEquals(.5f, settings.copy(preset = "NEGATIVE").activeLook.shadows, 0f)
        assertEquals("CLOUDY", settings.whiteBalance)
        assertEquals(3, settings.whiteBalanceAmber)
        assertTrue(settings.hdfEnabled)
    }

    @Test fun allNewParametersAndCustomLutSurviveRoundTripAndReachRenderer() {
        val look = LookSettings(.75f, .2f, .4f, -.5f, .25f, .5f, -.25f, -.75f, .5f, .75f, -.25f)
        val settings = AppSettings(
            preset = "CUSTOM", lutName = "My street.cube", looks = mapOf("CUSTOM" to look),
            hdfEnabled = true, hdfStrength = .6f, whiteBalance = "INCANDESCENT",
            whiteBalanceAmber = -4, whiteBalanceGreen = 7,
        )
        val loaded = SettingsJson.decode(SettingsJson.encode(settings))
        assertEquals(settings, loaded)
        val recipe = loaded.colorRecipe()
        assertEquals(ColorPreset.CUSTOM, recipe.preset)
        assertEquals(look.clarity, recipe.clarity, 0f)
        assertEquals(look.highlights, recipe.highlights, 0f)
        assertEquals(look.sharpness, recipe.sharpness, 0f)
        assertTrue(recipe.hdfEnabled)
        assertEquals(-4, recipe.whiteBalanceAmber)
        assertEquals(7, recipe.whiteBalanceGreen)
    }

    @Test fun outOfRangeAndNonFiniteStoredValuesCannotReachShader() {
        val json = """{"version":2,"preset":"POWDER_BLUE","hdfStrength":-3,"whiteBalance":"UNKNOWN","whiteBalanceAmber":99,"whiteBalanceGreen":-99,"looks":{"POWDER_BLUE":{"saturation":12,"hue":-12,"sharpness":"NaN","grain":"Infinity","highlights":0.25},"INVALID":{"contrast":1}}}"""
        val settings = SettingsJson.decode(json)
        assertEquals(1f, settings.activeLook.saturation, 0f)
        assertEquals(-1f, settings.activeLook.hue, 0f)
        assertEquals(0f, settings.activeLook.sharpness, 0f)
        assertEquals(0f, settings.activeLook.grain, 0f)
        assertEquals(0f, settings.hdfStrength, 0f)
        assertEquals("AUTO", settings.whiteBalance)
        assertEquals(9, settings.whiteBalanceAmber)
        assertEquals(-9, settings.whiteBalanceGreen)
        assertEquals(setOf("POWDER_BLUE"), settings.looks.keys)
        settings.colorRecipe() // Validation must succeed for repaired settings.
    }

    @Test fun malformedDocumentsRecoverAndUnrelatedValidFieldsSurviveBadLookEntries() {
        assertEquals(AppSettings(), SettingsJson.decode("{broken"))
        assertEquals(AppSettings(), SettingsJson.decode(null))
        val settings = SettingsJson.decode("""{"version":2,"preset":"MONO","focalLength":50,"looks":{"MONO":false,"POSITIVE":{"contrast":0.5}}}""")
        assertEquals(50, settings.focalLength)
        assertEquals(LookSettings(), settings.activeLook)
        assertEquals(.5f, settings.copy(preset = "POSITIVE").activeLook.contrast, 0f)
    }
}
