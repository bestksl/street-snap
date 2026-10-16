package dev.streetsnap.color

import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorRecipeTest {
    @Test fun recipeRejectsNonFiniteAndOutOfRangeAmounts() {
        assertThrows(IllegalArgumentException::class.java) { ColorRecipe(intensity = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ColorRecipe(grain = Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { ColorRecipe(vignette = -.1f) }
        assertThrows(IllegalArgumentException::class.java) { ColorRecipe(intensity = 1.1f) }
        assertThrows(IllegalArgumentException::class.java) { ColorRecipe(hdfStrength = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ColorRecipe(hdfStrength = -.1f) }
        assertThrows(IllegalArgumentException::class.java) { ColorRecipe(hdfStrength = 1.1f) }
    }

    @Test fun creativeControlsRejectUnsafeAmountsAndAcceptSignedEndpoints() {
        val constructors: List<(Float) -> ColorRecipe> = listOf(
            { ColorRecipe(saturation = it) }, { ColorRecipe(hue = it) },
            { ColorRecipe(contrast = it) }, { ColorRecipe(brightness = it) },
            { ColorRecipe(highlights = it) }, { ColorRecipe(shadows = it) },
            { ColorRecipe(sharpness = it) }, { ColorRecipe(clarity = it) },
        )
        for (create in constructors) {
            for (amount in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1.01f, 1.01f)) {
                assertThrows(IllegalArgumentException::class.java) { create(amount) }
            }
            assertFalse(create(-1f).isIdentity)
            assertFalse(create(1f).isIdentity)
            assertTrue(create(0f).isIdentity)
        }
        assertThrows(IllegalArgumentException::class.java) { ColorRecipe(whiteBalanceAmber = 10) }
        assertThrows(IllegalArgumentException::class.java) { ColorRecipe(whiteBalanceGreen = -10) }
        assertFalse(ColorRecipe(whiteBalanceAmber = -9, whiteBalanceGreen = 9).isIdentity)
    }

    @Test fun zeroLookIntensityDoesNotDiscardOtherAdjustments() {
        val neutral = ColorRecipe(preset = ColorPreset.POSITIVE, intensity = 0f)
        assertTrue(neutral.isIdentity)
        assertTrue(ColorRecipe().isIdentity)
        for (preset in ColorPreset.entries.filter { it != ColorPreset.ORIGINAL }) {
            assertFalse(ColorRecipe(preset = preset).isIdentity)
        }
        val edited = listOf(
            neutral.copy(grain = .1f), neutral.copy(vignette = .1f),
            neutral.copy(saturation = -.1f), neutral.copy(hue = .1f),
            neutral.copy(contrast = .1f), neutral.copy(brightness = -.1f),
            neutral.copy(highlights = -.1f), neutral.copy(shadows = .1f),
            neutral.copy(sharpness = .1f), neutral.copy(clarity = -.1f),
            neutral.copy(whiteBalanceAmber = 1), neutral.copy(whiteBalanceGreen = -1),
            neutral.copy(hdfEnabled = true),
        )
        assertTrue(edited.all { !it.isIdentity })
    }

    @Test fun disabledOrZeroStrengthDiffusionKeepsIdentityFastPath() {
        assertTrue(ColorRecipe(hdfEnabled = false, hdfStrength = 1f).isIdentity)
        assertTrue(ColorRecipe(hdfEnabled = true, hdfStrength = 0f).isIdentity)
        assertFalse(ColorRecipe(hdfEnabled = true, hdfStrength = .01f).isIdentity)
    }
}
