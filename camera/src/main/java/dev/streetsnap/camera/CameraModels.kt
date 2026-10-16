package dev.streetsnap.camera

import java.io.File

/** Equivalent focal lengths are crops of the selected rear camera, never optical-lens claims. */
enum class FocalLength(val equivalentMm: Int?) {
    NATIVE(null), MM28(28), MM35(35), MM50(50);

    val label: String get() = equivalentMm?.let { "${it}mm" } ?: "原生视角"

    companion object {
        fun fromMm(mm: Int): FocalLength? = entries.firstOrNull { it.equivalentMm == mm }
    }
}

/** Manual exposure is active only when both values are provided. Focus is in diopters, not metres. */
data class CaptureSettings(
    val focalLength: FocalLength = FocalLength.MM28,
    val exposureCompensationIndex: Int = 0,
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val autoExposureLocked: Boolean = false,
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,
)

data class CameraState(
    val isBound: Boolean = false,
    val isReady: Boolean = false,
    val isCapturing: Boolean = false,
    val isFocusing: Boolean = false,
    val capabilities: CameraCapabilities? = null,
    val settings: CaptureSettings = CaptureSettings(),
    val zoomRatio: Float = 1f,
    val error: String? = null,
)

data class CameraCapabilities(
    val cameraId: String,
    val fixedPhysicalCameraId: String?,
    val device: String,
    val sensorOrientationDegrees: Int,
    val nativeEquivalentFocalLengthMm: Double?,
    val focalLengthOptions: List<FocalLength>,
    val maxZoomRatio: Float,
    val exposureCompensationMin: Int,
    val exposureCompensationMax: Int,
    val exposureCompensationStepEv: Double,
    val supportsManualExposure: Boolean,
    val isoMin: Int?,
    val isoMax: Int?,
    val exposureTimeMinNs: Long?,
    val exposureTimeMaxNs: Long?,
    val supportsManualFocus: Boolean,
    val minimumFocusDistanceDiopters: Float?,
    val focusDistanceCalibration: String,
    val supportsAutoExposureLock: Boolean,
    val supportsFocusMetering: Boolean,
    val notes: List<String>,
    val whiteBalanceModes: List<WhiteBalanceMode> = listOf(WhiteBalanceMode.AUTO),
) {
    fun toJson(): String = CameraCapabilityJson.encode(this)
}

/**
 * CameraX writes an EXIF-oriented, viewport-cropped JPEG into application cache only.
 * The app must process it, publish the finished result and delete this file in a finally block.
 * Read JPEG EXIF orientation before processing: the sensor pixels can still be rotated/mirrored.
 */
data class CaptureResult(
    val jpegFile: File,
    val settings: CaptureSettings,
    val capabilities: CameraCapabilities,
    val requestedAtElapsedRealtimeMs: Long,
    val completedAtElapsedRealtimeMs: Long,
)
