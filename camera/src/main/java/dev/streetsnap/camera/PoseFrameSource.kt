package dev.streetsnap.camera

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors

/**
 * T72: low-rate, local-only RGB frames for the optional on-device pose assistant.
 *
 * Mirrors [CompositionFrameSource]'s lifecycle but never owns the [ImageProxy]: the
 * composition source (or the camera controller) closes it. The frame callback runs
 * synchronously on the analysis executor and hands the consumer an upright bitmap;
 * inference must be async (detect-and-return), so at most one small bitmap is live.
 */
class PoseFrameSource(
    private val intervalMs: Long = 120L,
    private val maximumEdge: Int = 384,
    private val onError: (Throwable) -> Unit = {},
    private val onFrame: (Bitmap) -> Unit,
) : AutoCloseable {
    init {
        require(intervalMs in 50L..2_000L)
        require(maximumEdge in 128..640)
    }

    internal val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "street-snap-pose").apply { priority = Thread.MIN_PRIORITY }
    }
    private val lock = Any()
    private var generation = 0L
    private var active = false
    private var closed = false
    private var analyzerAttached = false
    private var executorClosed = false
    private var lastAcceptedAtMs = 0L

    internal fun activate(): Long = synchronized(lock) {
        check(!closed) { "姿势分析输入已关闭。" }
        generation++
        active = true
        analyzerAttached = true
        lastAcceptedAtMs = 0L
        generation
    }

    internal fun deactivate() = synchronized(lock) {
        active = false
        generation++
        lastAcceptedAtMs = 0L
    }

    internal fun shutdownExecutor() {
        synchronized(lock) {
            analyzerAttached = false
            shutdownExecutorIfDetached()
        }
    }

    private fun shutdownExecutorIfDetached() {
        if (closed && !analyzerAttached && !executorClosed) {
            executorClosed = true
            executor.shutdown()
        }
    }

    // Pause/resume only gates frame acceptance: the bound analyzer (and the session id it
    // captured at activate()) stays valid across camera OPEN transitions, so generation must
    // NOT change here — bumping it made every frame fail the session check until rebind.
    internal fun pause() = synchronized(lock) {
        active = false
        lastAcceptedAtMs = 0L
    }

    internal fun resume() = synchronized(lock) {
        if (!closed && !active) {
            active = true
            lastAcceptedAtMs = 0L
        }
    }

    /** Borrows the proxy while it is still open; never closes it. */
    internal fun analyze(image: ImageProxy, session: Long) {
        val bitmap: Bitmap?
        synchronized(lock) {
            if (!active || closed || generation != session) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastAcceptedAtMs < intervalMs) return
            lastAcceptedAtMs = now
            val crop = image.cropRect
            val sampled = YuvFrameSampler.copy(
                image.width, image.height,
                FrameCrop(crop.left, crop.top, crop.right, crop.bottom),
                image.imageInfo.rotationDegrees, maximumEdge,
                image.planes.map { YuvPlane(it.buffer, it.rowStride, it.pixelStride) })
            bitmap = Bitmap.createBitmap(sampled.pixels, sampled.geometry.bitmapWidth,
                sampled.geometry.bitmapHeight, Bitmap.Config.ARGB_8888)
        }
        // The bitmap is borrowed: an async consumer (MediaPipe graph) must copy it inside
        // the callback before returning, so it can be recycled here immediately.
        val frame = bitmap ?: return
        try {
            onFrame(frame)
        } catch (error: Exception) {
            runCatching { onError(error) }
        } finally {
            frame.recycle()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            active = false
            generation++
            shutdownExecutorIfDetached()
        }
    }
}
