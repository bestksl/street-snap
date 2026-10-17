package dev.streetsnap.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One consumer and one replaceable pending item, with deterministic ownership on every path. */
internal class LatestFrameGate<T>(
    scope: CoroutineScope,
    private val release: (T) -> Unit,
    consume: suspend (T) -> Unit,
    onError: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val frames = Channel(Channel.CONFLATED, onUndeliveredElement = release)
    private val worker = scope.launch {
        for (frame in frames) {
            try {
                consume(frame)
            } catch (cancelled: CancellationException) {
                // A model's own timeout cancels that inference, not every future frame.
                currentCoroutineContext().ensureActive()
                runCatching { onError(cancelled) }
            } catch (error: Exception) {
                runCatching { onError(error) }
            } finally {
                release(frame)
            }
        }
    }

    fun offer(frame: T) {
        if (frames.trySend(frame).isFailure) release(frame)
    }

    fun dropPending() {
        while (true) release(frames.tryReceive().getOrNull() ?: break)
    }

    override fun close() {
        frames.cancel()
        worker.cancel()
    }
}

/** Uses elapsed time, not wall time, and handles a new camera session explicitly. */
internal class FrameSampleClock(private val minimumIntervalNs: Long) {
    private var lastAcceptedNs: Long? = null

    init { require(minimumIntervalNs > 0) }

    fun accept(nowNs: Long): Boolean {
        val last = lastAcceptedNs
        if (last != null && nowNs >= last && nowNs - last < minimumIntervalNs) return false
        lastAcceptedNs = nowNs
        return true
    }

    fun reset() { lastAcceptedNs = null }
}
