package dev.streetsnap.camera

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** RGB pixels belong to the source and remain valid only until the suspend callback returns. */
class CompositionFrame internal constructor(
    val bitmap: Bitmap,
    val timestampNs: Long,
    val receivedAtElapsedRealtimeMs: Long,
    val geometry: CompositionFrameGeometry,
    private val current: () -> Boolean,
) : AutoCloseable {
    /** Check again after inference; a rebind or disabled analysis invalidates an old result. */
    val isCurrent: Boolean get() = current()
    private val released = AtomicBoolean(false)
    override fun close() { if (released.compareAndSet(false, true)) bitmap.recycle() }
}

/**
 * Optional, local-only input for a composition engine. This does not contain or load a model.
 * No ImageProxy escapes analyze(); slow inference can hold at most one RGB frame plus the latest.
 * The app owns this source, calls close() on disposal, and checks frame.isCurrent before publishing.
 */
class CompositionFrameSource(
    minimumIntervalMs: Long = 750L,
    private val maximumEdge: Int = 384,
    onError: (Throwable) -> Unit = {},
    onFrame: suspend (CompositionFrame) -> Unit,
) : AutoCloseable {
    init {
        require(minimumIntervalMs in 100L..60_000L)
        require(maximumEdge in 64..640)
    }

    internal val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "street-snap-analysis").apply { priority = Thread.MIN_PRIORITY }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val sampling = FrameSampleClock(minimumIntervalMs * 1_000_000L)
    private var generation = 0L
    private var frameEpoch = 0L
    private var active = false
    private var closed = false
    private var executorClosed = false
    private val gate = LatestFrameGate(scope, CompositionFrame::close,
        consume = { frame -> if (frame.isCurrent) onFrame(frame) }, onError = onError)
    private val reportError = onError

    internal fun activate(): Long = synchronized(lock) {
        check(!closed) { "构图分析输入已关闭。" }
        generation++
        frameEpoch++
        active = true
        sampling.reset()
        generation
    }

    internal fun deactivate() {
        synchronized(lock) { active = false; generation++; frameEpoch++; sampling.reset() }
        gate.dropPending()
    }

    /** Called by CameraController after ImageAnalysis has been detached. */
    internal fun shutdownExecutor() {
        synchronized(lock) {
            if (executorClosed) return
            executorClosed = true
            executor.shutdown()
        }
    }

    internal fun pause() {
        synchronized(lock) { active = false; frameEpoch++; sampling.reset() }
        gate.dropPending()
    }

    internal fun resume() = synchronized(lock) {
        if (!closed && !active) { active = true; frameEpoch++; sampling.reset() }
    }

    /** Invalidate a model change or a framing change without adding another camera stream. */
    fun invalidateFrames() {
        synchronized(lock) { frameEpoch++; sampling.reset() }
        gate.dropPending()
    }

    internal fun analyze(image: ImageProxy, session: Long) {
        try {
            val now = SystemClock.elapsedRealtimeNanos()
            val epoch = synchronized(lock) {
                frameEpoch.takeIf { active && !closed && generation == session && sampling.accept(now) }
            } ?: return
            val frame = copyFrame(image, now / 1_000_000L) {
                synchronized(lock) { active && !closed && generation == session && frameEpoch == epoch }
            }
            if (frame.isCurrent) gate.offer(frame) else frame.close()
        } catch (error: Exception) {
            // Analysis failure must never fail the preview/capture session.
            runCatching { reportError(error) }
        } finally {
            image.close()
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; active = false; generation++; frameEpoch++ }
        gate.close()
        scope.cancel()
        // CameraX may still dispatch an analyzer callback until its use case is
        // unbound. CameraController shuts this executor down after clearAnalyzer
        // and unbind; keeping it alive here avoids rejected submissions during
        // that handoff when the app changes models or backgrounds.
    }

    private fun copyFrame(image: ImageProxy, receivedAt: Long, current: () -> Boolean): CompositionFrame {
        val crop = image.cropRect
        val copied = YuvFrameSampler.copy(image.width, image.height,
            FrameCrop(crop.left, crop.top, crop.right, crop.bottom), image.imageInfo.rotationDegrees,
            maximumEdge, image.planes.map { YuvPlane(it.buffer, it.rowStride, it.pixelStride) })
        val geometry = copied.geometry
        val bitmap = Bitmap.createBitmap(copied.pixels, geometry.bitmapWidth, geometry.bitmapHeight, Bitmap.Config.ARGB_8888)
        return CompositionFrame(bitmap, image.imageInfo.timestamp, receivedAt, geometry, current)
    }
}
