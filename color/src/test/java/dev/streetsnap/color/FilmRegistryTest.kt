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
}
