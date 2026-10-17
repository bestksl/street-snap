package dev.streetsnap.camera

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionFrameSourceLifecycleTest {
    @Test(expected = IllegalArgumentException::class)
    fun minimumIntervalBelowLowerBoundIsRejected() {
        CompositionFrameSource(minimumIntervalMs = 99L, onFrame = {})
    }

    @Test(expected = IllegalArgumentException::class)
    fun sceneIntervalBelowLowerBoundIsRejected() {
        CompositionFrameSource(sceneIntervalMs = 99L, onFrame = {})
    }

    @Test
    fun aLiveSourceCanReuseItsExecutorAfterCameraRebinding() {
        val source = CompositionFrameSource(onFrame = {})
        try {
            val firstSession = source.activate()
            assertEquals("first", source.executor.submit<String> { "first" }.get(1, TimeUnit.SECONDS))

            source.deactivate()
            source.shutdownExecutor()
            assertFalse(source.executor.isShutdown)

            val secondSession = source.activate()
            assertTrue(secondSession > firstSession)
            assertEquals("rebound", source.executor.submit<String> { "rebound" }.get(1, TimeUnit.SECONDS))

            source.deactivate()
            source.shutdownExecutor()
            source.close()
            assertTrue(source.executor.isShutdown)
        } finally {
            source.close()
            source.shutdownExecutor()
        }
    }

    @Test
    fun disposalKeepsTheExecutorAliveUntilCameraXDetachesItsAnalyzer() {
        val source = CompositionFrameSource(onFrame = {})
        try {
            source.activate()
            source.close()
            assertFalse(source.executor.isShutdown)
            // CameraX may still dispatch a final callback during the unbind handoff.
            assertEquals("last callback", source.executor.submit<String> { "last callback" }.get(1, TimeUnit.SECONDS))
            source.deactivate()
            source.shutdownExecutor()
            assertTrue(source.executor.isShutdown)
        } finally {
            source.close()
            source.shutdownExecutor()
        }
    }
}
