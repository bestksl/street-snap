package dev.streetsnap.app.pose

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MediaPipe PoseLandmarker LIVE_STREAM wrapper (T72). The only file that touches the
 * MediaPipe API. Runs the lite .task bundle on CPU (emulator-safe; GPU delegate support
 * is device-dependent), one person, and converts the 33 landmarks into our [PoseSample].
 *
 * [detect] returns immediately (the graph works asynchronously) and is safe to call from
 * the camera analysis executor; frames dropped while the graph is busy are ignored.
 * The input bitmap is owned by the caller — this class copies it before returning, so
 * the caller may recycle immediately.
 */
internal class MediaPipePoseSource(
    context: Context,
    modelFile: File,
    private val onResult: (PoseSample?) -> Unit,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lastTimestamp = AtomicLong(0L)

    private val landmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .setDelegate(Delegate.CPU)
                    .build(),
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> publish(result) }
            .setErrorListener { error -> onError(error) }
            .build(),
    )

    fun detect(bitmap: Bitmap) {
        if (closed.get()) return
        // Monotonic timestamps are required by LIVE_STREAM; analysis-frame timestamps
        // occasionally tie after throttling, so bump rather than crash the graph.
        val timestamp = SystemClock.uptimeMillis().let { now ->
            var previous = lastTimestamp.get()
            while (now <= previous) {
                if (lastTimestamp.compareAndSet(previous, previous + 1)) return@let previous + 1
                previous = lastTimestamp.get()
            }
            lastTimestamp.compareAndSet(previous, now)
            now
        }
        // The MediaPipe bitmap container may retain the bitmap until the async result is
        // produced; the source recycles its bitmap on return, so hand over a private copy.
        val owned = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        try {
            landmarker.detectAsync(BitmapImageBuilder(owned).build(), timestamp)
        } catch (error: RuntimeException) {
            // Graph busy / stale timestamp / torn-down session: drop this frame.
            owned.recycle()
            onError(error)
        }
    }

    private fun publish(result: PoseLandmarkerResult) {
        if (closed.get()) return
        val landmarks = result.landmarks().firstOrNull()
        if (landmarks == null) {
            onResult(null)
            return
        }
        val points = landmarks.map { landmark ->
            PosePoint(
                landmark.x(),
                landmark.y(),
                landmark.z(),
                landmark.visibility().orElse(0f).coerceIn(0f, 1f),
            )
        }
        onResult(PoseSample(points, SystemClock.elapsedRealtime()))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { landmarker.close() }
    }
}
