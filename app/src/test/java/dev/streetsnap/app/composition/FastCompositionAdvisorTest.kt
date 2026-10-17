package dev.streetsnap.app.composition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastCompositionAdvisorTest {
    @Test
    fun suggestedCropMovesWithAnOffCenterVisualFeature() {
        val left = FastCompositionAdvisor.suggest(96, 64, scene(0.24f)).candidates.first().crop
        val right = FastCompositionAdvisor.suggest(96, 64, scene(0.76f)).candidates.first().crop
        assertTrue("Crop must follow image content", right.left - left.left > 0.15f)
        assertTrue(left.left <= 0.17f && left.right >= 0.31f)
        assertTrue(right.left <= 0.69f && right.right >= 0.83f)
    }

    @Test
    fun proposalsAreDistinctFiniteAndPreserveTheOriginalAspectRatio() {
        val suggestions = FastCompositionAdvisor.suggest(96, 64, scene(0.30f)).candidates
        assertEquals(3, suggestions.size)
        assertEquals(3, suggestions.map { it.crop }.distinct().size)
        suggestions.forEach { suggestion ->
            val crop = suggestion.crop
            val width = crop.right - crop.left
            val height = crop.bottom - crop.top
            assertEquals(width, height, 0.00001f)
            assertTrue(width >= 0.65f && width <= 1f)
            assertTrue(crop.left >= 0f && crop.top >= 0f && crop.right <= 1f && crop.bottom <= 1f)
            assertTrue(suggestion.score.isFinite() && suggestion.score in 0f..1f)
            assertTrue(suggestion.reason.isNotBlank())
            assertTrue(suggestion.reason.startsWith(suggestion.style.title) || suggestion.style == CompositionStyle.GENERIC)
            assertTrue(suggestion.supportsModelComparison)
        }
    }

    @Test
    fun uniformAndNearUniformFramesProduceDeterministicConservativeAdvice() {
        val uniform = IntArray(96 * 64) { 0xFF707070.toInt() }
        val first = FastCompositionAdvisor.suggest(96, 64, uniform).candidates
        assertEquals(first, FastCompositionAdvisor.suggest(96, 64, uniform).candidates)
        assertEquals(3, first.size)
        assertEquals(1f, first.first().crop.right - first.first().crop.left, 0f)
        assertTrue(first.all { it.crop.right - it.crop.left >= 0.89f })
        assertTrue(first.first().reason.contains("纹理"))
        first.forEach { assertFalse("Featureless crops must not reach the model", it.supportsModelComparison) }
        val noise = IntArray(96 * 64) { if (it % 3 == 0) 0xFF727272.toInt() else 0xFF707070.toInt() }
        assertEquals(first, FastCompositionAdvisor.suggest(96, 64, noise).candidates)
    }

    @Test
    fun colourContrastWorksEvenWithSimilarBrightness() {
        val pixels = IntArray(96 * 64) { 0xFF647464.toInt() }
        for (y in 20..43) for (x in 67..82) pixels[y * 96 + x] = 0xFFCE3A3A.toInt()
        val crop = FastCompositionAdvisor.suggest(96, 64, pixels).candidates.first().crop
        assertTrue(crop.left > 0.15f)
        assertTrue(crop.left <= 67f / 96 && crop.right >= 83f / 96)
    }

    @Test
    fun scatteredStrongFeaturesFavorKeepingMoreOfTheScene() {
        val pixels = IntArray(96 * 64) { 0xFF202020.toInt() }
        for (top in listOf(3, 49)) for (left in listOf(3, 81)) {
            for (y in top until top + 12) for (x in left until left + 12) pixels[y * 96 + x] = 0xFFE0E0E0.toInt()
        }
        val crop = FastCompositionAdvisor.suggest(96, 64, pixels).candidates.first().crop
        assertTrue("Do not discard one of the four strong regions", crop.right - crop.left >= 0.90f)
    }

    @Test
    fun aSinglePixelFrameStillHasThreeSafeSuggestions() {
        assertEquals(3, FastCompositionAdvisor.suggest(1, 1, intArrayOf(0xFF808080.toInt())).candidates.size)
    }

    @Test
    fun diagonalStripesReadAsDiagonalComposition() {
        val pixels = diagonalBands()
        val result = FastCompositionAdvisor.suggest(96, 64, pixels)
        val styles = result.candidates.map { it.style }
        assertTrue("diagonal scene expected, got $styles", styles.first() == CompositionStyle.DIAGONAL)
        assertTrue(result.candidates.first().reason.contains("对角线"))
    }

    @Test
    fun symmetricVerticalMassReadsAsCenteredComposition() {
        val pixels = IntArray(96 * 64) { 0xFF262626.toInt() }
        for (y in 0 until 64) for (x in 38..58) pixels[y * 96 + x] = 0xFFE6E6E6.toInt()
        val first = FastCompositionAdvisor.suggest(96, 64, pixels).candidates.first()
        assertEquals(CompositionStyle.CENTERED, first.style)
        assertTrue(first.reason.contains("中心构图"))
    }

    @Test
    fun differentScenesProduceDifferentPhotographicAdvice() {
        val offCenter = FastCompositionAdvisor.suggest(96, 64, scene(0.24f)).candidates.first().reason
        val diagonal = FastCompositionAdvisor.suggest(96, 64, diagonalBands()).candidates.first().reason
        val uniform = IntArray(96 * 64) { 0xFF707070.toInt() }
        val flat = FastCompositionAdvisor.suggest(96, 64, uniform).candidates.first().reason
        val distinct = listOf(offCenter, diagonal, flat).distinct()
        assertTrue("advice copy must vary with the scene, got $distinct", distinct.size >= 3)
    }

    @Test
    fun conservativeCopyVariesWithExposure() {
        val dark = IntArray(96 * 64) { 0xFF202020.toInt() }
        val bright = IntArray(96 * 64) { 0xFFF0F0F0.toInt() }
        val darkReasons = FastCompositionAdvisor.suggest(96, 64, dark).candidates.map { it.reason }.toSet()
        val brightReasons = FastCompositionAdvisor.suggest(96, 64, bright).candidates.map { it.reason }.toSet()
        assertTrue(darkReasons.any { it.contains("暗") })
        assertTrue(brightReasons.any { it.contains("亮") || it.contains("平") })
    }

    @Test
    fun aSmallBrightMassInsideTheFrameReadsAsADistantSubject() {
        val pixels = IntArray(96 * 64) { 0xFF242424.toInt() }
        for (y in 26..37) for (x in 42..54) pixels[y * 96 + x] = 0xFFF2D26C.toInt()
        val result = FastCompositionAdvisor.suggest(96, 64, pixels)
        assertTrue("tight inner bright mass should look far away", result.subjectLooksDistant)
    }

    @Test
    fun aLargeSubjectDoesNotReadAsDistant() {
        val pixels = IntArray(96 * 64) { 0xFF242424.toInt() }
        for (y in 12..52) for (x in 20..76) pixels[y * 96 + x] = 0xFFF2D26C.toInt()
        assertFalse(FastCompositionAdvisor.suggest(96, 64, pixels).subjectLooksDistant)
    }

    @Test
    fun featurelessFramesNeverClaimADistantSubject() {
        val uniform = IntArray(96 * 64) { 0xFF707070.toInt() }
        assertFalse(FastCompositionAdvisor.suggest(96, 64, uniform).subjectLooksDistant)
    }

    private fun diagonalBands(): IntArray = IntArray(96 * 64) { index ->
        val x = (index % 96 + 0.5f) / 96
        val y = (index / 96 + 0.5f) / 64
        val phase = (x + y * 1.5f) % 0.18f
        if (phase < 0.06f) 0xFFE8E8E8.toInt() else 0xFF2A2A2A.toInt()
    }

    private fun scene(centerX: Float): IntArray = IntArray(96 * 64) { index ->
        val x = (index % 96 + 0.5f) / 96
        val y = (index / 96 + 0.5f) / 64
        if (x in centerX - 0.07f..centerX + 0.07f && y in 0.32f..0.68f) 0xFFE8BA68.toInt()
        else 0xFF282828.toInt()
    }
}
