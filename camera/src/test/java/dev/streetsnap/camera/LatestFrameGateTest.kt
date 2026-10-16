package dev.streetsnap.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestFrameGateTest {
    @Test
    fun slowInferenceRetainsOnlyTheLatestPendingFrame() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val lastReleased = CompletableDeferred<Unit>()
        val processed = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        var simultaneous = 0
        var maximumSimultaneous = 0
        val gate = LatestFrameGate(this, release = { value: Int ->
            released += value
            if (value == 3) lastReleased.complete(Unit)
        }, consume = { value ->
            simultaneous++
            maximumSimultaneous = maxOf(simultaneous, maximumSimultaneous)
            processed += value
            if (value == 1) { firstStarted.complete(Unit); finishFirst.await() }
            simultaneous--
        })
        try {
            gate.offer(1)
            withTimeout(1_000) { firstStarted.await() }
            gate.offer(2)
            gate.offer(3)
            assertEquals(listOf(2), released)
            finishFirst.complete(Unit)
            withTimeout(1_000) { lastReleased.await() }
            assertEquals(listOf(1, 3), processed)
            assertEquals(1, maximumSimultaneous)
            assertEquals(listOf(1, 2, 3), released.sorted())
        } finally { gate.close() }
    }

    @Test
    fun closingReleasesCurrentPendingAndSubsequentFramesExactlyOnce() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstReleased = CompletableDeferred<Unit>()
        val released = mutableListOf<Int>()
        val gate = LatestFrameGate(this, release = { value: Int ->
            released += value
            if (value == 1) firstReleased.complete(Unit)
        }, consume = { firstStarted.complete(Unit); awaitCancellation() })
        gate.offer(1)
        withTimeout(1_000) { firstStarted.await() }
        gate.offer(2)
        gate.close()
        gate.offer(3)
        withTimeout(1_000) { firstReleased.await() }
        assertEquals(listOf(1, 2, 3), released.sorted())
    }

    @Test
    fun inferenceFailureDoesNotKillTheInputWorker() = runBlocking {
        val firstReleased = CompletableDeferred<Unit>()
        val secondReleased = CompletableDeferred<Unit>()
        val gate = LatestFrameGate(this, release = { value: Int ->
            if (value == 1) firstReleased.complete(Unit) else secondReleased.complete(Unit)
        }, consume = { value -> if (value == 1) error("rejected input") },
            onError = { error("failed error reporter") })
        try {
            gate.offer(1)
            withTimeout(1_000) { firstReleased.await() }
            gate.offer(2)
            withTimeout(1_000) { secondReleased.await() }
        } finally { gate.close() }
    }

    @Test
    fun aModelsOwnCancellationDoesNotStopFutureFrames() = runBlocking {
        val firstReleased = CompletableDeferred<Unit>()
        val secondReleased = CompletableDeferred<Unit>()
        val gate = LatestFrameGate(this, release = { value: Int ->
            if (value == 1) firstReleased.complete(Unit) else secondReleased.complete(Unit)
        }, consume = { value -> if (value == 1) throw CancellationException("model timeout") })
        try {
            gate.offer(1)
            withTimeout(1_000) { firstReleased.await() }
            gate.offer(2)
            withTimeout(1_000) { secondReleased.await() }
        } finally { gate.close() }
    }

    @Test
    fun samplingUsesElapsedTimeAndResetsWithTheSession() {
        val clock = FrameSampleClock(750)
        assertTrue(clock.accept(1000))
        assertFalse(clock.accept(1749))
        assertTrue(clock.accept(1750))
        assertFalse(clock.accept(1751))
        clock.reset()
        assertTrue(clock.accept(1752))
    }
}
