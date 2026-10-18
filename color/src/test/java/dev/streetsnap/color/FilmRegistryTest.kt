package dev.streetsnap.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmRegistryTest {
    @Test fun eachFilmHasADistinctRecipeBeyondItsBaseLut() {
        val films = FilmRegistry.entries
        assertEquals(7, films.size)
        assertEquals(films.size, films.map { it.id }.toSet().size)
        assertEquals(films.size, films.map { it.defaults }.toSet().size)
        for (film in films) {
            val recipe = film.defaults
            assertFalse(recipe.isIdentity)
            assertNotEquals(ColorRecipe(preset = film.basePreset), recipe)
            assertTrue(recipe.preset != ColorPreset.CUSTOM)
            // Reconstructing validates all finite shader values and supported ranges.
            assertEquals(recipe, recipe.copy())
        }
    }

    @Test fun filmDefaultsLeaveWhiteBalanceAndDiffusionToTheCamera() {
        for (film in FilmRegistry.entries) {
            assertEquals(0, film.defaults.whiteBalanceAmber)
            assertEquals(0, film.defaults.whiteBalanceGreen)
            assertFalse(film.defaults.hdfEnabled)
        }
    }

    @Test fun triXSwatchIsANeutralGrayRamp() {
        // Monochrome stock: swatches must stay neutral (R == G == B), light to dark,
        // never a colored placeholder that the artwork layer would have to special-case.
        val triX = FilmRegistry.find("MONO_01")!!
        for (argb in listOf(triX.swatchStart, triX.swatchEnd)) {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            assertEquals("swatch $argb must be neutral gray", r.toLong(), g.toLong())
            assertEquals(r.toLong(), b.toLong())
        }
        assertTrue((triX.swatchStart and 0xFFFFFF) > (triX.swatchEnd and 0xFFFFFF))
    }

    @Test fun grainStocksOrderCoarseAboveFineAndStayInRange() {
        for (film in FilmRegistry.entries) {
            val g = film.defaults.grain
            assertTrue("${film.id} grain $g", g in 0f..1f)
        }
        // The gritty B&W stock is the strongest, fine-grained daylight stock the lightest.
        assertTrue(FilmRegistry.find("MONO_01")!!.defaults.grain >
            FilmRegistry.find("NEG_01")!!.defaults.grain)
    }
}
