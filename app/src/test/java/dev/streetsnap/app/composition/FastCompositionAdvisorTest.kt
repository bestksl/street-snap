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
    fun mirrorStructureOnBothFlanksReadsAsSymmetry() {
        val result = FastCompositionAdvisor.suggest(96, 64, terracedBands())
        val first = result.candidates.first()
        assertEquals(CompositionStyle.SYMMETRY, first.style)
        assertTrue(first.reason.startsWith(CompositionStyle.SYMMETRY.title))
    }

    @Test
    fun aSingleCentralMassDoesNotReadAsSymmetry() {
        val pixels = IntArray(96 * 64) { 0xFF262626.toInt() }
        for (y in 0 until 64) for (x in 38..58) pixels[y * 96 + x] = 0xFFE6E6E6.toInt()
        val styles = FastCompositionAdvisor.suggest(96, 64, pixels).candidates.map { it.style }
        assertTrue("central bar must claim CENTERED not SYMMETRY, got $styles",
            styles.first() == CompositionStyle.CENTERED)
        assertTrue("no flank structure means symmetry never appears, got $styles",
            styles.none { it == CompositionStyle.SYMMETRY })
    }

    @Test
    fun regularGridReadsAsRepetition() {
        val first = FastCompositionAdvisor.suggest(96, 64, dotGrid()).candidates.first()
        assertEquals(CompositionStyle.REPETITION, first.style)
        assertTrue(first.reason.startsWith(CompositionStyle.REPETITION.title))
    }

    @Test
    fun severalSpreadVerticalEdgesReadAsVerticalLines() {
        val first = FastCompositionAdvisor.suggest(96, 64, tallBeams()).candidates.first()
        assertEquals(CompositionStyle.VERTICAL, first.style)
        assertTrue(first.reason.startsWith(CompositionStyle.VERTICAL.title))
    }

    @Test
    fun aLongHorizontalBoundaryReadsAsHorizon() {
        val result = FastCompositionAdvisor.suggest(96, 64, horizonBands())
        val first = result.candidates.first()
        assertEquals(CompositionStyle.HORIZON, first.style)
        assertTrue(first.reason.startsWith(CompositionStyle.HORIZON.title))
        assertTrue("landscape must not be claimed by symmetry, got ${result.candidates.map { it.style }}",
            result.candidates.none { it.style == CompositionStyle.SYMMETRY })
    }

    @Test
    fun verticalTonalSplitReadsAsContrastNotHorizon() {
        val pixels = IntArray(96 * 64) { index ->
            if (index % 96 < 44) 0xFF242424.toInt() else 0xFFE6E6E6.toInt()
        }
        val first = FastCompositionAdvisor.suggest(96, 64, pixels).candidates.first()
        assertEquals(CompositionStyle.CONTRAST, first.style)
        assertTrue(first.reason.startsWith(CompositionStyle.CONTRAST.title))
    }

    @Test
    fun bilateralInwardDiagonalsReadAsConvergence() {
        val first = FastCompositionAdvisor.suggest(96, 64, convergingLines()).candidates.first()
        assertEquals(CompositionStyle.CONVERGENCE, first.style)
        assertTrue(first.reason.startsWith(CompositionStyle.CONVERGENCE.title))
    }

    @Test
    fun strongEvidenceSeedsADiverseFullFrameCandidate() {
        val result = FastCompositionAdvisor.suggest(96, 64, symmetricWithOneSign())
        val styles = result.candidates.map { it.style }
        assertTrue("seeded full-frame SYMMETRY expected among $styles",
            styles.contains(CompositionStyle.SYMMETRY))
        val symmetryCrop = result.candidates.first { it.style == CompositionStyle.SYMMETRY }.crop
        assertTrue("injected crop keeps most of the frame, got ${symmetryCrop.right - symmetryCrop.left}",
            symmetryCrop.right - symmetryCrop.left >= 0.95f)
    }

    @Test
    fun noSeedInjectionForFeaturelessOrUnsupportedFrames() {
        val uniform = IntArray(96 * 64) { 0xFF707070.toInt() }
        assertTrue(FastCompositionAdvisor.suggest(96, 64, uniform).candidates
            .all { it.style == CompositionStyle.CONSERVATIVE })
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

    // --- T32: third wave of composition language ---

    @Test
    fun aHorizonStrandedInTheMiddleReadsAsBisectedFrame() {
        val result = FastCompositionAdvisor.suggest(96, 64, centeredHorizonSplit())
        val first = result.candidates.first()
        assertEquals(CompositionStyle.HORIZON_SPLIT, first.style)
        assertTrue(first.reason.startsWith(CompositionStyle.HORIZON_SPLIT.title))
        assertTrue("copy must offer a vertical move", first.reason.contains("抬高") || first.reason.contains("压低"))
    }

    @Test
    fun aThirdLineHorizonStaysHorizonNotBisected() {
        val styles = FastCompositionAdvisor.suggest(96, 64, horizonBands()).candidates.map { it.style }
        assertEquals(CompositionStyle.HORIZON, styles.first())
        assertTrue("a third-line horizon must not be called a split, got $styles",
            styles.none { it == CompositionStyle.HORIZON_SPLIT })
    }

    @Test
    fun anEdgeFilledBusyFrameReadsAsTooTight() {
        val first = FastCompositionAdvisor.suggest(96, 64, crowdedTexture()).candidates.first()
        assertEquals(CompositionStyle.TOO_TIGHT, first.style)
        assertTrue(first.reason.contains("退后"))
    }

    @Test
    fun aCenteredColumnIsNotTooTightOrEdgeCut() {
        val pixels = IntArray(96 * 64) { 0xFF262626.toInt() }
        for (y in 0 until 64) for (x in 38..58) pixels[y * 96 + x] = 0xFFE6E6E6.toInt()
        val styles = FastCompositionAdvisor.suggest(96, 64, pixels).candidates.map { it.style }
        assertTrue("got $styles", styles.none { it == CompositionStyle.TOO_TIGHT })
        assertTrue("a column that nearly spans the frame is not a sideways cut, got $styles",
            styles.none { it == CompositionStyle.EDGE_CUT })
    }

    @Test
    fun aRegularGridIsNeitherTooTightNorMinimal() {
        val styles = FastCompositionAdvisor.suggest(96, 64, dotGrid()).candidates.map { it.style }
        assertTrue("got $styles", styles.none { it == CompositionStyle.TOO_TIGHT })
        assertTrue("got $styles", styles.none { it == CompositionStyle.MINIMAL })
    }

    @Test
    fun aSubjectRunningOffTheLeftEdgeReadsAsEdgeCutWithDirection() {
        val result = FastCompositionAdvisor.suggest(96, 64, leftEdgeSubject())
        val styles = result.candidates.map { it.style }
        val edge = result.candidates.first { it.style == CompositionStyle.EDGE_CUT }
        assertTrue("edge-cut candidate must keep the full frame",
            edge.crop.right - edge.crop.left >= 0.95f)
        assertTrue(edge.reason.contains("左边缘") && edge.reason.contains("向右"))
        // The opposite side carries a secondary mass, so this is not a negative-space reading.
        assertTrue(styles.none { it == CompositionStyle.NEGATIVE_SPACE })
    }

    @Test
    fun anOffCenterStripDoesNotReadAsEdgeCut() {
        val styles = FastCompositionAdvisor.suggest(96, 64, scene(0.24f)).candidates.map { it.style }
        assertTrue("strip starts at x≈0.17, well inside the rim, got $styles",
            styles.none { it == CompositionStyle.EDGE_CUT })
    }

    @Test
    fun oneSmallMassOnAQuietFieldReadsAsMinimal() {
        val result = FastCompositionAdvisor.suggest(96, 64, minimalField())
        val minimal = result.candidates.firstOrNull { it.style == CompositionStyle.MINIMAL }
        assertTrue("minimal candidate expected among ${result.candidates.map { it.style }}", minimal != null)
        assertTrue(minimal!!.reason.startsWith(CompositionStyle.MINIMAL.title))
    }

    @Test
    fun newWaveStylesNeverAppearOnFeaturelessOrTonalSplitFrames() {
        val uniform = IntArray(96 * 64) { 0xFF707070.toInt() }
        assertTrue(FastCompositionAdvisor.suggest(96, 64, uniform).candidates
            .all { it.style == CompositionStyle.CONSERVATIVE })
        val verticalSplit = IntArray(96 * 64) { index ->
            if (index % 96 < 44) 0xFF242424.toInt() else 0xFFE6E6E6.toInt()
        }
        val splitStyles = FastCompositionAdvisor.suggest(96, 64, verticalSplit).candidates.map { it.style }
        assertTrue("tonal split must not trigger edge/split/crowd language, got $splitStyles",
            splitStyles.none {
                it == CompositionStyle.EDGE_CUT || it == CompositionStyle.HORIZON_SPLIT ||
                    it == CompositionStyle.TOO_TIGHT
            })
    }

    private fun centeredHorizonSplit(): IntArray = IntArray(96 * 64) { index ->
        if (index / 96 < 32) 0xFFD8D8D8.toInt() else 0xFF303030.toInt()
    }

    /** Aperiodic 4px texture blocks filling the whole frame (a crowded, over-full scene). */
    private fun crowdedTexture(): IntArray = IntArray(96 * 64) { index ->
        // Per-block hash keyed on block coordinates, so blocks stay contiguous without
        // producing the column/row periodicity the REPETITION detector keys on.
        val block = ((index % 96) / 4) * 73 + (index / 96 / 4) * 101
        // Use the TOP bit of the multiplicative hash: the low bit just mirrors
        // (col + row) parity and collapses the blocks into a checkerboard.
        val v = if (((block * 2654435761.toInt()) ushr 31) == 0) 0x2C else 0xD8
        0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
    }

    /** Bright mass spilling out of the left rim; a smaller mass on the right denies negative space. */
    private fun leftEdgeSubject(): IntArray {
        val pixels = IntArray(96 * 64) { 0xFF262626.toInt() }
        for (y in 8..56) for (x in 0..21) pixels[y * 96 + x] = 0xFFE8E8E8.toInt()
        for (y in 22..44) for (x in 62..80) pixels[y * 96 + x] = 0xFF8A8A8A.toInt()
        return pixels
    }

    /** One small warm mass on a flat dark field: minimalism, not a featureless frame. */
    private fun minimalField(): IntArray {
        val pixels = IntArray(96 * 64) { 0xFF202020.toInt() }
        for (y in 25..37) for (x in 41..53) pixels[y * 96 + x] = 0xFFF2D26C.toInt()
        return pixels
    }

    private fun terracedBands(): IntArray = IntArray(96 * 64) { index ->
        val x = index % 96
        val y = index / 96
        val band = when {
            y <= 10 -> 0x40
            y <= 24 -> 0x5A
            y <= 44 -> 0x78
            y <= 55 -> 0x9C
            else -> 0xB8
        }
        val shade = (kotlin.math.abs(x - 47.5) * 0.8).toInt().coerceAtMost(0x30)
        val v = (band - shade).coerceIn(0, 255)
        0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
    }

    private fun dotGrid(): IntArray = IntArray(96 * 64) { index ->
        val x = index % 96
        val y = index / 96
        val onX = intArrayOf(10, 26, 42, 58, 74).any { kotlin.math.abs(x - it) <= 2 }
        val onY = intArrayOf(10, 26, 42, 58).any { kotlin.math.abs(y - it) <= 2 }
        if (onX && onY) 0xFFD8D8D8.toInt() else 0xFF2C2C2C.toInt()
    }

    private fun tallBeams(): IntArray = IntArray(96 * 64) { index ->
        when (index % 96) {
            in 10..14 -> 0xFFC8C8C8.toInt()
            in 62..68 -> 0xFFB0B0B0.toInt()
            else -> 0xFF303030.toInt()
        }
    }

    private fun horizonBands(): IntArray = IntArray(96 * 64) { index ->
        val y = index / 96
        when {
            y < 21 -> 0xFFD8D8D8.toInt()
            y == 30 || y == 45 || y == 58 -> 0xFF5C5C5C.toInt()
            else -> 0xFF6A6A6A.toInt()
        }
    }

    private fun convergingLines(): IntArray = IntArray(96 * 64) { index ->
        val x = (index % 96 + 0.5)
        val y = (index / 96 + 0.5)
        var onLine = false
        for (offset in intArrayOf(-15, 0, 15)) {
            val apexX = 48.0 + offset
            if (y >= 12) {
                val expectedX = if (x < apexX) apexX - (y - 12) / 0.85
                else apexX + (y - 12) / 0.85
                if (kotlin.math.abs(x - expectedX) <= 1.7) onLine = true
            }
        }
        if (onLine) 0xFFD0D0D0.toInt() else 0xFF262626.toInt()
    }

    /** Mirror-symmetric weak facade plus one off-axis high-contrast sign (seeding case). */
    private fun symmetricWithOneSign(): IntArray = IntArray(96 * 64) { index ->
        val x = index % 96
        val y = index / 96
        if (x in 1..11 && y in 52..62) 0xFFF2D26C.toInt()
        else {
            val band = when {
                y <= 10 -> 0x58
                y <= 24 -> 0x5E
                y <= 44 -> 0x64
                y <= 55 -> 0x6A
                else -> 0x70
            }
            val shade = (kotlin.math.abs(x - 47.5) * 0.5).toInt().coerceAtMost(20)
            val v = (band - shade).coerceIn(0, 255)
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
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
