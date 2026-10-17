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
    private val sceneCurrent: () -> Boolean,
) : AutoCloseable {
    /** Check again after inference; a rebind or disabled analysis invalidates an old result. */
    val isCurrent: Boolean get() = current()
    /** Check on completion: a slow result is usable only while the captured scene still matches. */
    val isSceneCurrent: Boolean get() = current() && sceneCurrent()
    private val released = AtomicBoolean(false)
    override fun close() { if (released.compareAndSet(false, true)) bitmap.recycle() }
}

/**
 * Optional, local-only input for a composition engine. This does not contain or load a model.
 * No ImageProxy escapes analyze(); inference holds one RGB bitmap with no queued bitmap.
 * The app checks isCurrent during inference and isSceneCurrent before publishing its result.
 */
class CompositionFrameSource(
    minimumIntervalMs: Long = 750L,
    private val maximumEdge: Int = 384,
    onError: (Throwable) -> Unit = {},
    private val onSceneChanged: () -> Unit = {},
    /** Read-only, thread-safe demand check on the camera executor, before copying a full RGB frame. */
    private val shouldCaptureFrame: () -> Boolean = { true },
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
    private val sampling = SerialFrameClock(minimumIntervalMs * 1_000_000L)
    private val sceneSampling = FrameSampleClock(500_000_000L)
    private val sceneTracker = FrameSceneTracker()
    private var generation = 0L
    private var frameEpoch = 0L
    private var active = false
    private var closed = false
    private var analyzerAttached = false
    private var executorClosed = false
    private val gate = LatestFrameGate(scope, release = { frame: CompositionFrame ->
        frame.close()
        synchronized(lock) { sampling.complete(SystemClock.elapsedRealtimeNanos()) }
    },
        consume = { frame -> if (frame.isCurrent) onFrame(frame) }, onError = onError)
    private val reportError = onError

    internal fun activate(): Long = synchronized(lock) {
        check(!closed) { "构图分析输入已关闭。" }
        generation++
        frameEpoch++
        active = true
        analyzerAttached = true
        sampling.reset()
        sceneSampling.reset()
        sceneTracker.reset()
        generation
    }

    internal fun deactivate() {
        synchronized(lock) {
            active = false; generation++; frameEpoch++; sampling.reset(); sceneSampling.reset(); sceneTracker.reset()
        }
        gate.dropPending()
        runCatching(onSceneChanged)
    }

    /** Called after detachment. A live source can be rebound after a rotation or preview resize. */
    internal fun shutdownExecutor() {
        synchronized(lock) {
            analyzerAttached = false
            shutdownExecutorIfDetached()
        }
    }

    /** Caller holds lock; both source disposal and analyzer detachment must precede shutdown. */
    private fun shutdownExecutorIfDetached() {
        if (closed && !analyzerAttached && !executorClosed) {
            executorClosed = true
            executor.shutdown()
        }
    }

    internal fun pause() {
        val wasActive = synchronized(lock) {
            val previous = active
            active = false; frameEpoch++; sampling.reset(); sceneSampling.reset(); sceneTracker.reset()
            previous
        }
        gate.dropPending()
        if (wasActive) runCatching(onSceneChanged)
    }

    internal fun resume() = synchronized(lock) {
        if (!closed && !active) {
            active = true; frameEpoch++; sampling.reset(); sceneSampling.reset(); sceneTracker.reset()
        }
    }

    /** Adjust the rest interval after inference without rebuilding CameraX's analysis use case. */
    fun setMinimumIntervalMs(value: Long) = synchronized(lock) {
        require(value in 100L..60_000L)
        sampling.setMinimumIntervalNs(value * 1_000_000L)
    }

    /** A manual request may skip the cooldown, while preserving scene checks and any in-flight frame. */
    fun requestNextFrame() = synchronized(lock) {
        if (!closed) sampling.reset()
    }

    /** Invalidate a model change or a framing change without adding another camera stream. */
    fun invalidateFrames() {
        synchronized(lock) { frameEpoch++; sampling.reset(); sceneSampling.reset(); sceneTracker.reset() }
        gate.dropPending()
        // Rotation/zoom invalidates a displayed rectangle as well as in-flight work.
        runCatching(onSceneChanged)
    }

    internal fun analyze(image: ImageProxy, session: Long) {
        var reserved = false
        var offered = false
        try {
            val now = SystemClock.elapsedRealtimeNanos()
            val captureRequested = shouldCaptureFrame()
            val (epoch, observeScene) = synchronized(lock) {
                if (!active || closed || generation != session) return
                reserved = captureRequested && sampling.accept(now)
                val sceneDue = sceneSampling.accept(now)
                frameEpoch to (reserved || sceneDue)
            }
            if (!observeScene) return
            val small = sample(image, 24)
            val signature = SceneSignature(small.geometry.bitmapWidth, small.geometry.bitmapHeight, small.pixels)
            val (sceneChanged, capturedScene) = synchronized(lock) {
                if (!active || closed || generation != session || frameEpoch != epoch) return
                val changed = sceneTracker.observe(signature, now)
                changed to if (reserved) sceneTracker.capture() else null
            }
            if (sceneChanged) runCatching(onSceneChanged)
            if (!reserved) return
            val frame = copyFrame(image, now / 1_000_000L,
                current = {
                    synchronized(lock) { active && !closed && generation == session && frameEpoch == epoch }
                },
                sceneCurrent = {
                    synchronized(lock) { sceneTracker.isCurrent(checkNotNull(capturedScene), SystemClock.elapsedRealtimeNanos()) }
                })
            if (frame.isCurrent) {
                offered = true
                gate.offer(frame)
            } else frame.close()
        } catch (error: Exception) {
            // Analysis failure must never fail the preview/capture session.
            runCatching { reportError(error) }
        } finally {
            if (reserved && !offered) synchronized(lock) { sampling.complete(SystemClock.elapsedRealtimeNanos()) }
            image.close()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true; active = false; generation++; frameEpoch++
            shutdownExecutorIfDetached()
        }
        gate.close()
        scope.cancel()
        // CameraX may still dispatch an analyzer callback until its use case is
        // unbound. CameraController shuts this executor down after clearAnalyzer
        // and unbind; keeping it alive here avoids rejected submissions during
        // that handoff when the app changes models or backgrounds.
    }

    private fun sample(image: ImageProxy, edge: Int): SampledRgbFrame {
        val crop = image.cropRect
        return YuvFrameSampler.copy(image.width, image.height,
            FrameCrop(crop.left, crop.top, crop.right, crop.bottom), image.imageInfo.rotationDegrees,
            edge, image.planes.map { YuvPlane(it.buffer, it.rowStride, it.pixelStride) })
    }

    private fun copyFrame(image: ImageProxy, receivedAt: Long, current: () -> Boolean,
        sceneCurrent: () -> Boolean): CompositionFrame {
        val copied = sample(image, maximumEdge)
        val geometry = copied.geometry
        val bitmap = Bitmap.createBitmap(copied.pixels, geometry.bitmapWidth, geometry.bitmapHeight, Bitmap.Config.ARGB_8888)
        return CompositionFrame(bitmap, image.imageInfo.timestamp, receivedAt, geometry, current, sceneCurrent)
    }
}
