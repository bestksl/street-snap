package dev.streetsnap.app

import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.FilmRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmBadgePaletteTest {
    private fun channels(c: androidx.compose.ui.graphics.Color): Triple<Float, Float, Float> =
        Triple(c.red, c.green, c.blue)

    @Test fun everyFilmStockHasABadgePalette() {
        for (film in FilmRegistry.entries) {
            val p = LookBadgePalette.of(film.id, film.basePreset)
            // The gate must never render as an empty/flat plate.
            assertTrue("${film.id} gate gradient", p.gateStart != p.gateEnd || p.mono)
            assertTrue("${film.id} perf visible", p.perf.alpha > 0f)
        }
    }

    @Test fun everyPresetIncludingCustomAndNullFilmResolves() {
        for (preset in ColorPreset.values()) {
            val p = LookBadgePalette.of(null, preset)
            assertTrue(preset.name, p.gateStart != p.gateEnd || p.original)
        }
        // An unknown film id must not crash or hide the badge; it falls back to the preset.
        assertEquals(
            LookBadgePalette.of(null, ColorPreset.SUNNY),
            LookBadgePalette.of("NOT_A_REAL_FILM", ColorPreset.SUNNY),
        )
    }

    @Test fun monochromeLooksAreNeutralAndCarryNoChromaAccent() {
        val mono = listOf(
            LookBadgePalette.of("MONO_01", ColorPreset.MONO),
            LookBadgePalette.of(null, ColorPreset.MONO),
            LookBadgePalette.of(null, ColorPreset.ACROS),
            LookBadgePalette.of(null, ColorPreset.LUMIX_MONO),
        )
        for (p in mono) {
            assertTrue(p.mono)
            assertNull(p.accent)
            // Mono ramps may carry a faint warm/cool paper cast, but stay near gray.
            for (c in listOf(p.gateStart, p.gateEnd)) {
                val (r, g, b) = channels(c)
                assertTrue("low-saturation ramp $r/$g/$b", maxOf(r, g, b) - minOf(r, g, b) < 0.05f)
            }
        }
        // Color stocks/looks stay non-mono.
        assertFalse(LookBadgePalette.of("CHROME_01", ColorPreset.POSITIVE).mono)
        assertFalse(LookBadgePalette.of(null, ColorPreset.POSITIVE).mono)
    }

    @Test fun originalLookUsesTheNeutralGateWithRgbDotsNotAnAccentBlock() {
        val p = LookBadgePalette.of(null, ColorPreset.ORIGINAL)
        assertTrue(p.original)
        assertNull(p.accent)
    }
}
