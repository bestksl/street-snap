package dev.streetsnap.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseFrameSourceLifecycleTest {
    @Test(expected = IllegalArgumentException::class)
    fun intervalBelowLowerBoundIsRejected() {
        PoseFrameSource(intervalMs = 49L, onFrame = {})
    }

    @Test
    fun pauseAndResumeDoNotInvalidateTheBoundAnalyzerSession() {
        // Camera state OPEN flips call pause()/resume() while the analyzer stays bound, so the
        // session id captured at activate() must remain current. Regresses the frame-drop bug
        // where pause/resume each bumped generation and every frame then failed the session
        // check (the old code returned session + 3 here instead of session + 1).
        val source = PoseFrameSource(onFrame = {})
        try {
            val session = source.activate()
            source.pause()
            source.resume()
            assertEquals(session + 1L, source.activate())
        } finally {
            source.close()
            source.shutdownExecutor()
        }
    }

    @Test
    fun deactivateAdvancesGenerationSoStaleAnalyzersAreRejected() {
        val source = PoseFrameSource(onFrame = {})
        try {
            val first = source.activate()
            source.deactivate()
            assertEquals(first + 2L, source.activate())
        } finally {
            source.close()
            source.shutdownExecutor()
        }
    }
}
