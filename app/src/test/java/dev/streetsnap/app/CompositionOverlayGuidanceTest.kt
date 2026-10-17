package dev.streetsnap.app

import dev.streetsnap.app.composition.CompositionCandidate
import dev.streetsnap.app.edge.CompositionSuggestion
import dev.streetsnap.camera.NormalizedBoundingBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionOverlayGuidanceTest {
    private val box = NormalizedBoundingBox(0.1f, 0.2f, 0.8f, 0.9f)
    private val candidate = CompositionCandidate(box, score = 1f, reason = "三分构图：主体放在交点上")
    private val overlay = CompositionOverlay(
        CompositionSuggestion(box.left, box.top, box.right, box.bottom, candidate.reason),
        expiresAtMs = 100_000L,
    )

    @Test fun identicalCropWordingAndOriginIsRedundant() {
        assertTrue(overlay.carriesSameGuidance(AdviceOrigin.IMAGE_ANALYSIS, candidate, null))
    }

    @Test fun ruleToModelHandoverMustRepublishEvenForTheSameCrop() {
        assertFalse(overlay.carriesSameGuidance(AdviceOrigin.MODEL_SELECTION, candidate, null))
    }

    @Test fun shiftedCropMustRepublish() {
        val moved = candidate.copy(crop = NormalizedBoundingBox(0.12f, 0.2f, 0.82f, 0.9f))
        assertFalse(overlay.carriesSameGuidance(AdviceOrigin.IMAGE_ANALYSIS, moved, null))
    }

    @Test fun changedWordingMustRepublish() {
        val reworded = candidate.copy(reason = "居中构图：主体置于画面中心")
        assertFalse(overlay.carriesSameGuidance(AdviceOrigin.IMAGE_ANALYSIS, reworded, null))
    }

    @Test fun anIncomingEasterEggMustRepublish() {
        assertFalse(overlay.carriesSameGuidance(AdviceOrigin.IMAGE_ANALYSIS, candidate, "试试退后一步"))
    }

    @Test fun anEasterEggAlreadyShownMustNotBeSuppressed() {
        val eggOverlay = overlay.copy(easterEgg = "试试退后一步")
        assertFalse(eggOverlay.carriesSameGuidance(AdviceOrigin.IMAGE_ANALYSIS, candidate, null))
    }
}
