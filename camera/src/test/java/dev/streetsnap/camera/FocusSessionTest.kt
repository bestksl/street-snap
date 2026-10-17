package dev.streetsnap.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusSessionTest {
    @Test
    fun aSlowPreviousTapCannotReplaceTheNewestLockedResult() = runBlocking {
        val fixture = Fixture()
        val oldResult = CompletableDeferred<Boolean>()
        val oldRequest = fixture.session.begin(lock = false) {}
        val oldFocus = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.await(oldRequest) { oldResult.await() }
        }
        var newCancellations = 0
        val newest = fixture.session.begin(lock = true) { newCancellations++ }
        assertTrue(fixture.session.await(newest) { true })

        oldResult.complete(false)
        assertFalse(oldFocus.await())
        assertEquals(Feedback(false, true, true), fixture.feedback)
        assertEquals(0, newCancellations)
    }

    @Test
    fun cancellingAnOlderCoroutineDoesNotCancelTheNewFocus() = runBlocking {
        val fixture = Fixture()
        val oldRequest = fixture.session.begin(lock = false) {}
        val oldFocus = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.await(oldRequest) { awaitCancellation() }
        }
        var cancellations = 0
        val newest = fixture.session.begin(lock = true) { cancellations++ }

        oldFocus.cancelAndJoin()
        assertEquals(Feedback(true, null, false), fixture.feedback)
        assertTrue(fixture.session.await(newest) { true })
        assertEquals(0, cancellations)
    }

    @Test
    fun anUnresponsiveCameraTimesOutAndAllowsAnotherTap() = runBlocking {
        val fixture = Fixture(timeoutMs = 20L)
        var cancellations = 0
        val request = fixture.session.begin(lock = true) { cancellations++ }

        assertFalse(fixture.session.await(request) { awaitCancellation() })
        assertEquals(Feedback(false, false, false), fixture.feedback)
        assertEquals(1, cancellations)

        val retry = fixture.session.begin(lock = false) { cancellations++ }
        assertTrue(fixture.session.await(retry) { true })
        assertEquals(Feedback(false, true, false), fixture.feedback)
        assertEquals(1, cancellations)
    }

    @Test
    fun switchingModeOrDetachingInvalidatesPendingCallbacks() = runBlocking {
        val fixture = Fixture()
        val result = CompletableDeferred<Boolean>()
        var cancellations = 0
        val request = fixture.session.begin(lock = true) { cancellations++ }
        val focus = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.await(request) { result.await() }
        }

        fixture.session.invalidate()
        result.complete(true)
        assertFalse(focus.await())
        assertEquals(Feedback(false, null, false), fixture.feedback)
        assertEquals(1, cancellations)
    }

    @Test
    fun cancellingTheActiveCoroutineReleasesMeteringAndFeedback() = runBlocking {
        val fixture = Fixture()
        var cancellations = 0
        val request = fixture.session.begin(lock = true) { cancellations++ }
        val focus = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.session.await(request) { awaitCancellation() }
        }

        focus.cancelAndJoin()
        assertEquals(Feedback(false, null, false), fixture.feedback)
        assertEquals(1, cancellations)
    }

    @Test
    fun aParentTimeoutIsPropagatedAsCancellation() = runBlocking {
        val fixture = Fixture()
        var cancellations = 0
        val request = fixture.session.begin(lock = true) { cancellations++ }
        var cancelled = false
        try {
            withTimeout(20L) {
                fixture.session.await(request) { awaitCancellation() }
            }
        } catch (_: TimeoutCancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(Feedback(false, null, false), fixture.feedback)
        assertEquals(1, cancellations)
    }

    @Test
    fun aRejectedCameraOperationProducesOnlyFailedFocusFeedback() = runBlocking {
        val fixture = Fixture()
        val request = fixture.session.begin(lock = true) { error("camera already closed") }

        assertFalse(fixture.session.await(request) { error("AF request rejected") })
        assertEquals(Feedback(false, false, false), fixture.feedback)
    }

    @Test
    fun anExplicitUnlockReleasesTheSuccessfulFocusLockOnce() = runBlocking {
        val fixture = Fixture()
        var cancellations = 0
        val request = fixture.session.begin(lock = true) { cancellations++ }
        assertTrue(fixture.session.await(request) { true })
        assertEquals(Feedback(false, true, true), fixture.feedback)

        fixture.session.invalidate()
        fixture.session.invalidate()
        assertEquals(Feedback(false, null, false), fixture.feedback)
        assertEquals(1, cancellations)
    }

    private data class Feedback(val focusing: Boolean, val result: Boolean?, val locked: Boolean)

    private class Fixture(timeoutMs: Long = 5_000L) {
        var feedback = Feedback(false, null, false)
        val session = FocusSession(
            publish = { focusing, result, locked -> feedback = Feedback(focusing, result, locked) },
            timeoutMs = timeoutMs,
        )
    }
}
