package dev.streetsnap.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionResultGateTest {
    @Test fun invalidationRejectsAnInferenceStartedBeforeTheNewView() {
        val gate = CompositionResultGate(maximumAgeMs = 5_000)
        val ticket = gate.ticket(100)!!
        gate.invalidate(200)
        assertFalse(gate.accepts(ticket, 300, frameIsCurrent = true))
        val fresh = gate.ticket(210)!!
        assertTrue(gate.accepts(fresh, 220, frameIsCurrent = true))
    }

    @Test fun oldOrStaleFramesCannotPublishAfterTheAgeWindow() {
        val gate = CompositionResultGate(maximumAgeMs = 100)
        val ticket = gate.ticket(100)!!
        assertFalse(gate.isFreshForInference(ticket, 200))
        assertFalse(gate.accepts(ticket, 200, frameIsCurrent = true))
        assertFalse(gate.accepts(ticket, 150, frameIsCurrent = false))
        assertNotNull(gate.ticket(250))
    }

    @Test fun aFreshFrameMayStartInferenceBeforeItIsPublished() {
        val gate = CompositionResultGate(maximumAgeMs = 500)
        val ticket = gate.ticket(100)!!
        assertTrue(gate.isFreshForInference(ticket, 599))
        assertEquals(1L, gate.remainingMs(ticket, 599))
        assertTrue(gate.accepts(ticket, 599, frameIsCurrent = true))
    }
}
