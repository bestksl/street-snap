package dev.streetsnap.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Main-thread focus lifecycle; an old camera callback must never replace the newest tap. */
internal class FocusSession(
    private val publish: (focusing: Boolean, result: Boolean?, locked: Boolean) -> Unit,
    private val timeoutMs: Long = 5_000L,
) {
    internal class Request(val generation: Long, val lock: Boolean)

    private var generation = 0L
    private var cancelActive: (() -> Unit)? = null

    fun begin(lock: Boolean, cancel: () -> Unit): Request {
        val request = Request(++generation, lock)
        // CameraX startFocusAndMetering replaces its previous action. Explicitly cancelling
        // here would add another camera request between two rapid taps.
        cancelActive = cancel
        publish(true, null, false)
        return request
    }

    suspend fun await(request: Request, result: suspend () -> Boolean): Boolean {
        val successful = try {
            withTimeoutOrNull(timeoutMs) { result() } ?: false
        } catch (cancelled: CancellationException) {
            if (request.generation == generation) invalidate()
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (request.generation != generation) return false
        if (!successful) cancelMetering()
        publish(false, successful, request.lock && successful)
        return successful
    }

    /** Called on AF/MF transitions, explicit unlock and session teardown. */
    fun invalidate() {
        generation++
        cancelMetering()
        publish(false, null, false)
    }

    private fun cancelMetering() {
        val cancel = cancelActive
        cancelActive = null
        // Session shutdown or a rejected HAL request must not become a full-screen error.
        try {
            cancel?.invoke()
        } catch (_: Exception) {
            // CameraX also cancels the action when its camera becomes inactive.
        }
    }
}
