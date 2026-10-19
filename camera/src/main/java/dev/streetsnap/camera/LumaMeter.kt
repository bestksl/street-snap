package dev.streetsnap.camera

import android.os.SystemClock
import androidx.camera.core.ImageProxy

/**
 * Real exposure histogram sampled from live ImageAnalysis frames. It never invents
 * values: when [active] is false or frames arrive faster than the throttle interval
 * it stays silent, and the UI simply keeps the previous sample.
 *
 * analyze() runs synchronically on the analysis thread and never closes the ImageProxy;
 * the caller that owns the analysis use case is responsible for closing it.
 */
class LumaMeter(
    val bins: Int = 48,
    private val onSample: (HistogramSample) -> Unit,
) {
    init { require(bins in 16..128) }

    @Volatile
    var active: Boolean = false

    private var lastSampleAtMs = 0L

    fun analyze(image: ImageProxy) {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAtMs < MIN_INTERVAL_MS) return
        lastSampleAtMs = now
        val crop = image.cropRect
        // 64px edge is plenty for an exposure histogram and keeps the YUV walk trivial.
        val sampled = YuvFrameSampler.copy(
            image.width, image.height,
            FrameCrop(crop.left, crop.top, crop.right, crop.bottom),
            image.imageInfo.rotationDegrees, 64,
            image.planes.map { YuvPlane(it.buffer, it.rowStride, it.pixelStride) })
        val counts = IntArray(bins)
        val pixels = sampled.pixels
        var i = 0
        while (i < pixels.size) {
            val packed = pixels[i]
            val red = (packed shr 16) and 0xFF
            val green = (packed shr 8) and 0xFF
            val blue = packed and 0xFF
            val luma = (0.299f * red + 0.587f * green + 0.114f * blue).toInt().coerceIn(0, 255)
            counts[(luma * bins / 256).coerceAtMost(bins - 1)]++
            i++
        }
        onSample(HistogramSample(counts, ExposureStats.from(counts)))
    }

    private companion object { const val MIN_INTERVAL_MS = 200L }
}
