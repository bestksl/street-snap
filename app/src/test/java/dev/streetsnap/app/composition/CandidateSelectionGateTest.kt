package dev.streetsnap.app.composition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateSelectionGateTest {
    private val gate = CandidateSelectionGate(listOf(1, 2, 0))

    @Test fun acceptsTheSameCropAtItsChangedPosition() {
        for (canonical in 0..2) {
            val validationPosition = gate.validationOrder.indexOf(canonical)
            val decision = gate.evaluate(canonical, validationPosition, hasVisualEvidence = true)
            assertEquals(CandidateSelectionGate.Verdict.ACCEPTED, decision.verdict)
            assertEquals(canonical, decision.canonicalIndex)
        }
    }

    @Test fun aFixedPreferenceForAnyPositionCannotPass() {
        for (position in 0..2) {
            val decision = gate.evaluate(position, position, hasVisualEvidence = true)
            assertEquals(CandidateSelectionGate.Verdict.INCONSISTENT, decision.verdict)
            assertNull(decision.canonicalIndex)
        }
    }

    @Test fun observedBThenBMapsToDifferentOriginalCrops() {
        assertEquals(2, gate.canonicalForValidation(1))
        assertEquals(CandidateSelectionGate.Verdict.INCONSISTENT,
            gate.evaluate(1, 1, hasVisualEvidence = true).verdict)
    }

    @Test fun eitherAbstentionOrMissingSecondPassPreventsAcceptance() {
        for ((first, second) in listOf(null to 0, 0 to null, null to null)) {
            val decision = gate.evaluate(first, second, hasVisualEvidence = true)
            assertEquals(CandidateSelectionGate.Verdict.ABSTAINED, decision.verdict)
            assertNull(decision.canonicalIndex)
        }
    }

    @Test fun invalidModelIndicesDoNotBecomeCropChoices() {
        for ((first, second) in listOf(-1 to 0, 3 to 0, 0 to -1, 0 to 3)) {
            assertEquals(CandidateSelectionGate.Verdict.ABSTAINED,
                gate.evaluate(first, second, hasVisualEvidence = true).verdict)
        }
    }

    @Test fun agreementOnALowSignalImageStillCannotClaimAChoice() {
        val decision = gate.evaluate(0, 2, hasVisualEvidence = false)
        assertEquals(CandidateSelectionGate.Verdict.INSUFFICIENT_IMAGE_SIGNAL, decision.verdict)
        assertNull(decision.canonicalIndex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unchangedPositionCannotBeUsedAsAnIndependentCheck() {
        CandidateSelectionGate(listOf(1, 0, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicatedCandidatesCannotBeUsedAsAnIndependentCheck() {
        CandidateSelectionGate(listOf(1, 2, 1))
    }
}
