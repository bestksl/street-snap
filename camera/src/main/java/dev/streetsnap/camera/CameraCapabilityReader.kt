package dev.streetsnap.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import kotlin.math.abs

@OptIn(ExperimentalCamera2Interop::class)
internal object CameraCapabilityReader {
    data class SensorSelection(
        val logicalCameraId: String,
        val physicalCameraId: String?,
        val characteristics: CameraCharacteristics,
        val isLogical: Boolean,
    )

    /** Pin the default physical sensor only when its focal length and sensor dimensions match uniquely. */
    fun selectSensor(context: Context, info: CameraInfo): SensorSelection {
        val id = Camera2CameraInfo.from(info).cameraId
        val manager = requireNotNull(context.getSystemService(CameraManager::class.java)) {
            "无法访问系统相机服务。"
        }
        val logical = manager.getCameraCharacteristics(id)
        val ids = logical.physicalCameraIds
        val focalLengths = logical[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]
        val sensorSize = logical[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
        val focal = focalLengths?.singleOrNull()
        val matches = if (focal != null && sensorSize != null) ids.mapNotNull { physicalId ->
            runCatching {
                val candidate = manager.getCameraCharacteristics(physicalId)
                val candidateFocals = candidate[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]
                val candidateSize = candidate[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
                val matchesFocal = candidateFocals?.singleOrNull()?.let { abs(it - focal) < 0.01f } == true
                val matchesSize = candidateSize != null &&
                    abs(candidateSize.width - sensorSize.width) < 0.01f &&
                    abs(candidateSize.height - sensorSize.height) < 0.01f
                if (matchesFocal && matchesSize) physicalId to candidate else null
            }.getOrNull()
        } else emptyList()
        val selected = matches.singleOrNull()
        return SensorSelection(id, selected?.first, selected?.second ?: logical, ids.isNotEmpty())
    }

    fun read(info: CameraInfo, sensor: SensorSelection): CameraCapabilities {
        val characteristics = sensor.characteristics
        val notes = mutableListOf<String>()
        val nativeEquivalentMm = estimateNativeEquivalentMm(characteristics)
        if (nativeEquivalentMm == null) {
            notes += "无法可靠推导原生等效视角，暂只开放原生视角；请在真机上验证镜头元数据。"
        } else {
            notes += "等效焦距按设备报告的焦距与有效传感器区域估算，须验证实际取景与成片。"
        }
        if (sensor.isLogical && sensor.physicalCameraId == null) {
            notes += "后置逻辑相机的默认物理传感器无法唯一确认，尚未固定主摄；变焦时可能由系统换镜头。"
        }
        val maxZoom = minOf(
            info.zoomState.value?.maxZoomRatio?.takeIf { it.isFinite() && it >= 1f } ?: 1f,
            characteristics[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM]
                ?.takeIf { it.isFinite() && it >= 1f } ?: 1f,
        ).coerceAtLeast(1f)
        val availableModes = characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf()
        val manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in availableModes
        val isoRange = characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
        val exposureRange = characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
        val requestKeys = characteristics.availableCaptureRequestKeys
        val shutterPriority = Build.VERSION.SDK_INT >= 36 && exposureRange != null &&
            CaptureRequest.CONTROL_AE_PRIORITY_MODE in requestKeys &&
            CaptureRequest.SENSOR_EXPOSURE_TIME in requestKeys &&
            CameraCharacteristics.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY in
                (characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES] ?: intArrayOf())
        val focusDistance = characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]
            ?.takeIf { it.isFinite() && it >= 0f }
        val afModes = characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        val autoFocusMode = when {
            CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ->
                CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CameraCharacteristics.CONTROL_AF_MODE_AUTO in afModes ->
                CameraCharacteristics.CONTROL_AF_MODE_AUTO
            else -> null
        }
        val manualFocus = FocusModePolicy.supportsManualFocus(
            maximumDiopters = focusDistance,
            availableAfModes = afModes,
            acceptsFocusDistance = CaptureRequest.LENS_FOCUS_DISTANCE in requestKeys,
        )
        val focusCalibration = when (characteristics[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION]) {
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrated"
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "approximate"
            else -> "uncalibrated"
        }
        if (manualFocus && focusCalibration != "calibrated") {
            notes += "手动对焦距离未经精确标定；使用屈光度控制，不提供米制距离承诺。"
        }
        val exposure = info.exposureState
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        return CameraCapabilities(
            cameraId = sensor.logicalCameraId,
            fixedPhysicalCameraId = sensor.physicalCameraId,
            device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            sensorOrientationDegrees = characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
            nativeEquivalentFocalLengthMm = nativeEquivalentMm,
            focalLengthOptions = FocalLengthMath.availableOptions(nativeEquivalentMm, maxZoom),
            maxZoomRatio = maxZoom,
            exposureCompensationMin = exposure.exposureCompensationRange.lower,
            exposureCompensationMax = exposure.exposureCompensationRange.upper,
            exposureCompensationStepEv = exposure.exposureCompensationStep.toDouble(),
            supportsManualExposure = manualSensor && isoRange != null && exposureRange != null,
            isoMin = isoRange?.lower,
            isoMax = isoRange?.upper,
            exposureTimeMinNs = exposureRange?.lower,
            exposureTimeMaxNs = exposureRange?.upper,
            supportsShutterPriority = shutterPriority,
            fixedAperture = characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]
                ?.filter { it.isFinite() && it > 0f }?.distinct()?.singleOrNull(),
            supportsManualFocus = manualFocus,
            minimumFocusDistanceDiopters = focusDistance,
            focusDistanceCalibration = focusCalibration,
            autoFocusMode = autoFocusMode,
            supportsAutoExposureLock = characteristics[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE] == true,
            supportsFocusMetering = info.isFocusMeteringSupported(
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build(),
            ),
            notes = notes,
            whiteBalanceModes = WhiteBalanceSupport.availableModes(
                characteristics[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES],
            ),
        )
    }

    private fun estimateNativeEquivalentMm(characteristics: CameraCharacteristics): Double? {
        val focal = characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]
            ?.singleOrNull()?.toDouble() ?: return null
        val physical = characteristics[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return null
        val pixels = characteristics[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE] ?: return null
        val active = characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return null
        if (pixels.width <= 0 || pixels.height <= 0) return null
        return FocalLengthMath.equivalentMm(
            focalLengthMm = focal,
            activeSensorWidthMm = physical.width.toDouble() * active.width() / pixels.width,
            activeSensorHeightMm = physical.height.toDouble() * active.height() / pixels.height,
        )
    }
}
