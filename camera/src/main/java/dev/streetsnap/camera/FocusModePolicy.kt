package dev.streetsnap.camera

import android.hardware.camera2.CaptureRequest

/** Focus controls depend on lens controls, independently of manual sensor exposure. */
internal object FocusModePolicy {
    fun supportsManualFocus(
        maximumDiopters: Float?,
        availableAfModes: IntArray,
        acceptsFocusDistance: Boolean,
    ): Boolean = maximumDiopters != null && maximumDiopters.isFinite() && maximumDiopters > 0f &&
        CaptureRequest.CONTROL_AF_MODE_OFF in availableAfModes && acceptsFocusDistance

    /** Null is AF; 0 D is a real MF setting (infinity), never an alias for AF. */
    fun nextDistance(
        currentDistance: Float?,
        rememberedDistance: Float,
        maximumDiopters: Float?,
        supportsManual: Boolean,
        supportsAuto: Boolean,
    ): Float? {
        if (currentDistance != null) return if (supportsAuto) null else currentDistance
        if (!supportsManual || maximumDiopters == null ||
            !maximumDiopters.isFinite() || maximumDiopters <= 0f) return null
        return rememberedDistance.takeIf { it.isFinite() }?.coerceIn(0f, maximumDiopters) ?: 0f
    }
}
