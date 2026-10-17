package dev.streetsnap.camera

enum class ExposureMode(val label: String) {
    PROGRAM("P"), SHUTTER_PRIORITY("S"), MANUAL("M"),
}

/** Exposure combinations map to real Camera2 controls; aperture is not simulated. */
internal object ExposurePolicy {
    fun mode(iso: Int?, exposureTimeNs: Long?): ExposureMode = when {
        iso != null -> ExposureMode.MANUAL
        exposureTimeNs != null -> ExposureMode.SHUTTER_PRIORITY
        else -> ExposureMode.PROGRAM
    }

    fun select(settings: CaptureSettings, mode: ExposureMode, caps: CameraCapabilities): CaptureSettings {
        require(mode in caps.exposureModes) { "当前设备不支持 ${mode.label} 档。" }
        val time = if (mode == ExposureMode.PROGRAM) null else {
            (settings.exposureTimeNs ?: 8_000_000L)
                .coerceIn(requireNotNull(caps.exposureTimeMinNs), requireNotNull(caps.exposureTimeMaxNs))
        }
        val iso = if (mode == ExposureMode.MANUAL) {
            (settings.iso ?: 200).coerceIn(requireNotNull(caps.isoMin), requireNotNull(caps.isoMax))
        } else null
        return settings.copy(iso = iso, exposureTimeNs = time, autoExposureLocked = false)
    }

    fun reset(settings: CaptureSettings, supportsAutoFocus: Boolean): CaptureSettings = settings.copy(
        iso = null,
        exposureTimeNs = null,
        exposureCompensationIndex = 0,
        autoExposureLocked = false,
        focusDistanceDiopters = if (supportsAutoFocus) null else settings.focusDistanceDiopters,
    )

    fun isExposureValid(settings: CaptureSettings, caps: CameraCapabilities): Boolean = when (settings.exposureMode) {
        ExposureMode.PROGRAM -> true
        ExposureMode.SHUTTER_PRIORITY -> caps.supportsShutterPriority && timeInRange(settings, caps)
        ExposureMode.MANUAL -> caps.supportsManualExposure && timeInRange(settings, caps) &&
            caps.isoMin != null && caps.isoMax != null &&
            settings.iso?.let { it in caps.isoMin..caps.isoMax } == true
    }

    private fun timeInRange(settings: CaptureSettings, caps: CameraCapabilities): Boolean =
        caps.exposureTimeMinNs != null && caps.exposureTimeMaxNs != null &&
            settings.exposureTimeNs?.let { it in caps.exposureTimeMinNs..caps.exposureTimeMaxNs } == true
}
