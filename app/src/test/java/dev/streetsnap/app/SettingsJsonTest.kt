package dev.streetsnap.app

import dev.streetsnap.app.edge.EdgeModelCatalog
import dev.streetsnap.app.edge.EdgeModelKind
import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.FilmRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsJsonTest {
    @Test fun compositionUpgradeDefaultsToManualAndRealtimeSurvivesRestart() {
        val legacy = SettingsJson.decode("""{"version":4,"compositionEnabled":true,"preset":"MONO"}""")
        assertTrue(legacy.compositionEnabled)
        assertFalse(legacy.compositionRealtime)
        assertEquals("MONO", legacy.preset)
        val realtime = legacy.copy(compositionRealtime = true)
        assertEquals(realtime, SettingsJson.decode(SettingsJson.encode(realtime)))
    }

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

    @Test fun hdrDefaultsOffSurvivesRoundTripAndRecoversFromLegacyOrBadJson() {
        assertFalse(AppSettings().hdrEnabled)
        assertEquals(.6f, AppSettings().hdrStrength, 0f)
        val enabled = AppSettings(hdrEnabled = true, hdrStrength = .85f)
        assertEquals(enabled, SettingsJson.decode(SettingsJson.encode(enabled)))
        // Legacy document without HDR fields: look stays off.
        val legacy = SettingsJson.decode("""{"version":2,"preset":"POSITIVE"}""")
        assertFalse(legacy.hdrEnabled)
        assertEquals(.6f, legacy.hdrStrength, 0f)
        // Out-of-range strength is clamped; malformed documents recover to defaults.
        val repaired = SettingsJson.decode("""{"hdrEnabled":true,"hdrStrength":5}""")
        assertTrue(repaired.hdrEnabled)
        assertEquals(1f, repaired.hdrStrength, 0f)
        assertEquals(AppSettings(), SettingsJson.decode("{broken"))
        assertTrue(enabled.colorRecipe().hdrEnabled)
    }

    @Test fun malformedDocumentsRecoverAndUnrelatedValidFieldsSurviveBadLookEntries() {
        assertEquals(AppSettings(), SettingsJson.decode("{broken"))
        assertEquals(AppSettings(), SettingsJson.decode(null))
        val settings = SettingsJson.decode("""{"version":2,"preset":"MONO","focalLength":50,"looks":{"MONO":false,"POSITIVE":{"contrast":0.5}}}""")
        assertEquals(50, settings.focalLength)
        assertEquals(LookSettings(), settings.activeLook)
        assertEquals(.5f, settings.copy(preset = "POSITIVE").activeLook.contrast, 0f)
    }

    @Test fun selectingFilmUsesItsAuthoredRecipeWithoutOverwritingCameraControls() {
        for (film in FilmRegistry.entries) {
            val settings = AppSettings(
                preset = "CUSTOM", filmId = film.id, lutName = "Street.cube",
                whiteBalance = "DAYLIGHT", whiteBalanceAmber = 2, whiteBalanceGreen = -1,
                hdfEnabled = true, hdfStrength = .55f,
            )
            assertEquals(
                film.defaults.copy(hdfEnabled = true, hdfStrength = .55f, whiteBalanceAmber = 2, whiteBalanceGreen = -1),
                settings.colorRecipe(),
            )
            assertEquals("CUSTOM", settings.preset)
            assertEquals("Street.cube", settings.lutName)
            assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
        }
    }

    @Test fun twoFilmsSharingALutKeepTheirOwnEditsAndRestoreThePreviousPreset() {
        val day = FilmRegistry.find("DAY_01")!!
        val slowDay = FilmRegistry.find("NEG_01")!!
        assertEquals(day.basePreset, slowDay.basePreset)
        val original = AppSettings(preset = day.basePreset.name)
            .updateLook { it.copy(grain = .9f, contrast = -.8f) }
        val settings = original.copy(filmId = day.id)
            .updateLook { it.copy(grain = .6f, saturation = -.4f) }
            .copy(filmId = slowDay.id)
            .updateLook { it.copy(grain = .1f, saturation = .2f) }

        val loaded = SettingsJson.decode(SettingsJson.encode(settings))
        assertEquals(settings, loaded)
        assertEquals(.1f, loaded.activeLook.grain, 0f)
        assertEquals(.2f, loaded.activeLook.saturation, 0f)
        assertEquals(.6f, loaded.copy(filmId = day.id).activeLook.grain, 0f)
        assertEquals(-.4f, loaded.copy(filmId = day.id).activeLook.saturation, 0f)
        assertEquals(original.activeLook, loaded.copy(filmId = null).activeLook)
        assertEquals(original.colorRecipe(), loaded.copy(filmId = null).colorRecipe())
        assertEquals(setOf("SUNNY", "FILM:DAY_01", "FILM:NEG_01"), loaded.looks.keys)
    }

    @Test fun resettingFilmRestoresItsRecipeAndPreservesOtherLooksAndCameraPreferences() {
        val film = FilmRegistry.find("DAY_01")!!
        val settings = AppSettings(preset = "NEGATIVE", mascotCamera = "NIKON_F3", captureOrientation = "LANDSCAPE")
            .updateLook { it.copy(contrast = -.4f) }
            .copy(filmId = "PORTRAIT_01").updateLook { it.copy(saturation = .3f) }
            .copy(filmId = film.id).updateLook { it.copy(grain = 0f, sharpness = 1f) }
            .resetLook()
        assertEquals(film.defaults, settings.colorRecipe())
        assertEquals(-.4f, settings.copy(filmId = null).activeLook.contrast, 0f)
        assertEquals(.3f, settings.copy(filmId = "PORTRAIT_01").activeLook.saturation, 0f)
        assertEquals("NIKON_F3", settings.mascotCamera)
        assertEquals("LANDSCAPE", settings.captureOrientation)
        assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
    }

    @Test fun unknownFilmFallsBackToSavedPresetAndIgnoresUnknownLookKeys() {
        val settings = SettingsJson.decode("""{"version":3,"preset":"MONO","filmId":"REMOVED","filmFrame":"UNKNOWN","dateStamp":"UNKNOWN","looks":{"MONO":{"contrast":0.5},"FILM:REMOVED":{"grain":0.9},"FILM:DAY_01":{"grain":0.4}}}""")
        assertNull(settings.filmId)
        assertEquals(ColorPreset.MONO, settings.colorRecipe().preset)
        assertEquals(.5f, settings.activeLook.contrast, 0f)
        assertEquals(setOf("MONO", "FILM:DAY_01"), settings.looks.keys)
    }

    @Test fun partialAndInvalidFilmEditsRecoverFromItsDefaults() {
        val defaults = LookSettings.fromRecipe(FilmRegistry.find("NIGHT_01")!!.defaults)
        val settings = SettingsJson.decode("""{"version":3,"preset":"ORIGINAL","filmId":"NIGHT_01","looks":{"FILM:NIGHT_01":{"grain":"NaN","sharpness":99,"saturation":-99,"brightness":0.1}}}""")
        assertEquals(defaults.copy(sharpness = 1f, saturation = -1f, brightness = .1f), settings.activeLook)
        settings.colorRecipe()
    }

    @Test fun upgradingVersionThreeDropsDecorationAndPreservesCaptureAndFilmEdits() {
        val legacy = """{"version":3,"filmFrame":"WHITE","dateStamp":"DATE_TIME","preset":"NEGATIVE","filmId":"NIGHT_01","iso":640,"exposureTimeNs":16000000,"focalLength":50,"whiteBalance":"DAYLIGHT","looks":{"FILM:NIGHT_01":{"grain":0.6,"saturation":-0.3}}}"""
        val loaded = SettingsJson.decode(legacy)
        assertEquals("NIGHT_01", loaded.filmId)
        assertEquals(.6f, loaded.activeLook.grain, 0f)
        assertEquals(-.3f, loaded.activeLook.saturation, 0f)
        assertEquals(640, loaded.iso)
        assertEquals(16_000_000L, loaded.exposureTimeNs)
        assertEquals(50, loaded.focalLength)
        assertEquals("DAYLIGHT", loaded.whiteBalance)
        // Pre-T19 documents have no nameplate field; null now means "follow the active look".
        assertNull(loaded.mascotCamera)
        assertEquals("AUTO", loaded.captureOrientation)

        val saved = JSONObject(SettingsJson.encode(loaded))
        assertEquals(4, saved.getInt("version"))
        assertFalse(saved.has("filmFrame"))
        assertFalse(saved.has("dateStamp"))
        assertEquals(loaded, SettingsJson.decode(saved.toString()))
    }

    @Test fun everyNameplateChoiceAndCaptureDirectionSurviveRestart() {
        // null = follow the active look, HIDDEN = no nameplate, camera ids = pinned body.
        val mascots = listOf("RICOH_GR", "FUJIFILM_X100VI", "SIGMA_FP", "PANASONIC_LX100", "NIKON_F3",
            CameraPreferences.HIDDEN, null)
        for (mascot in mascots) for (orientation in listOf("AUTO", "LANDSCAPE", "PORTRAIT")) {
            val settings = AppSettings(mascotCamera = mascot, captureOrientation = orientation,
                preset = "POWDER_BLUE", whiteBalanceAmber = 3)
            assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
        }
    }

    @Test fun missingOrNullNameplateMeansFollowLookWhileHiddenIsAString() {
        // A legacy document without the field follows the active look (T19 migration)...
        val legacy = SettingsJson.decode("""{"version":2,"preset":"SUNNY"}""")
        assertNull(legacy.mascotCamera)
        assertEquals("AUTO", legacy.captureOrientation)
        // ...and so does an explicit JSON null, which used to mean "hidden" before T19.
        val follow = SettingsJson.decode("""{"version":4,"mascotCamera":null,"captureOrientation":"PORTRAIT"}""")
        assertNull(follow.mascotCamera)
        assertEquals("PORTRAIT", follow.captureOrientation)
        assertEquals(follow, SettingsJson.decode(SettingsJson.encode(follow)))
        // The hidden state is the dedicated HIDDEN string, encoded verbatim rather than as null.
        val hiddenJson = JSONObject(SettingsJson.encode(AppSettings(mascotCamera = CameraPreferences.HIDDEN)))
        assertEquals(CameraPreferences.HIDDEN, hiddenJson.getString("mascotCamera"))
        val hidden = SettingsJson.decode(hiddenJson.toString())
        assertEquals(CameraPreferences.HIDDEN, hidden.mascotCamera)
    }

    @Test fun unknownCameraPreferencesRecoverWithoutDiscardingTheSelectedLook() {
        val settings = SettingsJson.decode("""{"version":4,"mascotCamera":"REMOVED_CAMERA","captureOrientation":"SIDEWAYS","preset":"POSITIVE","looks":{"POSITIVE":{"contrast":0.5}}}""")
        // An unknown body id recovers to follow-look (null), never to a hard-coded body.
        assertNull(settings.mascotCamera)
        assertEquals("AUTO", settings.captureOrientation)
        assertEquals("POSITIVE", settings.preset)
        assertEquals(.5f, settings.activeLook.contrast, 0f)
        val encoded = SettingsJson.decode(SettingsJson.encode(AppSettings(mascotCamera = "UNKNOWN", captureOrientation = "UNKNOWN")))
        assertNull(encoded.mascotCamera)
        assertEquals("AUTO", encoded.captureOrientation)
    }

    @Test fun viewfinderOsdDefaultsOffAndEveryModeSurvivesRestart() {
        // Pure viewfinder by default; documents written before T20 simply lack the field.
        assertEquals(ViewfinderOsd.OFF, AppSettings().viewfinderOsd)
        assertEquals(ViewfinderOsd.OFF,
            SettingsJson.decode("""{"version":4,"preset":"MONO"}""").viewfinderOsd)
        for (mode in listOf(ViewfinderOsd.OFF, ViewfinderOsd.HISTOGRAM, ViewfinderOsd.PARAMS)) {
            val settings = AppSettings(viewfinderOsd = mode)
            assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
        }
        // Corrupt/unknown values never leave a phantom overlay on screen.
        assertEquals(ViewfinderOsd.OFF,
            SettingsJson.decode("""{"version":4,"viewfinderOsd":"LEVELS"}""").viewfinderOsd)
        // The corner button walks the full cycle and returns to off.
        var mode: String? = null
        val sequence = List(4) { mode = ViewfinderOsd.next(mode); mode }
        assertEquals(listOf(ViewfinderOsd.HISTOGRAM, ViewfinderOsd.PARAMS, ViewfinderOsd.OFF, ViewfinderOsd.HISTOGRAM), sequence)
    }

    @Test fun lookAdviceDefaultsOnAndDismissMemorySurvivesRoundTrip() {
        // Fresh installs get suggestions; legacy documents without the field default to on.
        assertTrue(AppSettings().lookAdviceEnabled)
        assertTrue(AppSettings().exposureAdviceEnabled)
        assertEquals(0, AppSettings().lookAdviceDismissStreak)
        assertTrue(AppSettings().lookDismissedAt.isEmpty())
        val legacy = SettingsJson.decode("""{"version":4,"preset":"MONO"}""")
        assertTrue(legacy.lookAdviceEnabled)
        assertTrue(legacy.exposureAdviceEnabled)
        val at = 1_700_000_000_000L
        val saved = AppSettings(
            lookAdviceEnabled = false,
            exposureAdviceEnabled = false,
            lookAdviceDismissStreak = 2,
            lookDismissedAt = mapOf("POSITIVE" to at, "FILM:MONO_01" to at + 1L),
        )
        val loaded = SettingsJson.decode(SettingsJson.encode(saved))
        assertEquals(saved, loaded)
        assertFalse(loaded.lookAdviceEnabled)
        assertFalse(loaded.exposureAdviceEnabled)
        assertEquals(2, loaded.lookAdviceDismissStreak)
        assertEquals(at, loaded.lookDismissedAt["POSITIVE"])
        // Bad streak values are clamped back into the valid 0..3 range.
        val repaired = SettingsJson.decode(
            """{"version":4,"lookAdviceEnabled":false,"lookAdviceDismissStreak":99,"lookDismissedAt":{"X":0,"VALID":123}}""")
        assertEquals(3, repaired.lookAdviceDismissStreak)
        assertEquals(setOf("VALID"), repaired.lookDismissedAt.keys)
    }

    @Test fun freshInstallSelectsTheRecommendedD1RankerAndUnknownIdsFallBack() {
        assertEquals("d1-omni-600m", EdgeModelCatalog.DEFAULT_MODEL_ID)
        assertEquals(EdgeModelCatalog.DEFAULT_MODEL_ID, AppSettings().compositionModel)
        assertEquals(EdgeModelKind.D1_OMNI_CHOICE,
            EdgeModelCatalog.find(AppSettings().compositionModel)!!.kind)
        // A document without the field (upgrades) and one with a removed/unknown id both recover to the default.
        assertEquals(EdgeModelCatalog.DEFAULT_MODEL_ID,
            SettingsJson.decode("""{"version":4,"preset":"MONO"}""").compositionModel)
        assertEquals(EdgeModelCatalog.DEFAULT_MODEL_ID,
            SettingsJson.decode("""{"version":4,"compositionModel":"removed-model"}""").compositionModel)
        // An explicit, still-shipped selection is preserved rather than silently migrated.
        assertEquals("smolvlm-256m",
            SettingsJson.decode("""{"version":4,"compositionModel":"smolvlm-256m"}""").compositionModel)
    }
}
