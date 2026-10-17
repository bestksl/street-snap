package dev.streetsnap.camera

import kotlin.math.abs

/** Tiny RGB samples retain spatial layout, so panning is visible even with a similar histogram. */
internal class SceneSignature(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(width > 0 && height > 0 && pixels.size == width * height) }

    fun resembles(other: SceneSignature): Boolean {
        if (width != other.width || height != other.height) return false
        var difference = 0L
        var changedPixels = 0
        for (index in pixels.indices) {
            val first = pixels[index]
            val second = other.pixels[index]
            val delta = abs((first shr 16 and 255) - (second shr 16 and 255)) +
                abs((first shr 8 and 255) - (second shr 8 and 255)) +
                abs((first and 255) - (second and 255))
            difference += delta
            if (delta > 3 * 45) changedPixels++
        }
        // Tolerate sensor noise and small auto-exposure shifts, but reject spatial changes.
        return difference <= pixels.size * 3L * 18 && changedPixels <= pixels.size * 0.16f
    }
}

/** Caller serializes access. Motion changes publication eligibility, never the inference job. */
internal class FrameSceneTracker {
    private var latest: SceneSignature? = null
    private var reference: SceneSignature? = null
    private var observedAtNs = Long.MIN_VALUE
    private var previouslyCurrent = true

    /** True only when the captured scene has just changed; callers can clear a visible overlay. */
    fun observe(signature: SceneSignature, nowNs: Long): Boolean {
        latest = signature
        observedAtNs = nowNs
        val current = reference?.resembles(signature) ?: true
        val changed = previouslyCurrent && !current
        previouslyCurrent = current
        return changed
    }

    fun capture(): SceneSignature {
        val signature = checkNotNull(latest)
        reference = signature
        previouslyCurrent = true
        return signature
    }

    fun isCurrent(signature: SceneSignature, nowNs: Long): Boolean =
        nowNs >= observedAtNs && nowNs - observedAtNs <= 2_000_000_000L &&
            latest?.let(signature::resembles) == true

    fun reset() {
        latest = null
        reference = null
        observedAtNs = Long.MIN_VALUE
        previouslyCurrent = true
    }
}
