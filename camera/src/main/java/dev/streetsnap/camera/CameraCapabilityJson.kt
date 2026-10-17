package dev.streetsnap.camera

import org.json.JSONArray
import org.json.JSONObject

internal object CameraCapabilityJson {
    fun encode(value: CameraCapabilities): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("cameraId", value.cameraId)
        put("fixedPhysicalCameraId", value.fixedPhysicalCameraId ?: JSONObject.NULL)
        put("device", value.device)
        put("sensorOrientationDegrees", value.sensorOrientationDegrees)
        put("nativeEquivalentFocalLengthMm", value.nativeEquivalentFocalLengthMm ?: JSONObject.NULL)
        put("equivalentFocalLengthSource", if (value.nativeEquivalentFocalLengthMm != null) {
            "estimated_from_camera_characteristics_4_3"
        } else "unverified")
        put("focalLengthOptions", JSONArray(value.focalLengthOptions.map { it.equivalentMm ?: "native" }))
        put("maxZoomRatio", value.maxZoomRatio.toDouble())
        put("exposureCompensation", JSONObject().apply {
            put("minIndex", value.exposureCompensationMin)
            put("maxIndex", value.exposureCompensationMax)
            put("stepEv", value.exposureCompensationStepEv)
        })
        put("manualExposure", JSONObject().apply {
            put("supported", value.supportsManualExposure)
            put("isoMin", value.isoMin ?: JSONObject.NULL)
            put("isoMax", value.isoMax ?: JSONObject.NULL)
            put("exposureTimeMinNs", value.exposureTimeMinNs ?: JSONObject.NULL)
            put("exposureTimeMaxNs", value.exposureTimeMaxNs ?: JSONObject.NULL)
        })
        put("exposureModes", JSONArray(value.exposureModes.map { it.label }))
        put("shutterPrioritySupported", value.supportsShutterPriority)
        put("fixedAperture", value.fixedAperture ?: JSONObject.NULL)
        put("focus", JSONObject().apply {
            put("manualSupported", value.supportsManualFocus)
            put("autoSupported", value.autoFocusMode != null)
            put("autoMode", value.autoFocusMode ?: JSONObject.NULL)
            put("maximumDiopters", value.minimumFocusDistanceDiopters ?: JSONObject.NULL)
            put("distanceCalibration", value.focusDistanceCalibration)
            put("meteringSupported", value.supportsFocusMetering)
        })
        put("autoExposureLockSupported", value.supportsAutoExposureLock)
        put("whiteBalanceModes", JSONArray(value.whiteBalanceModes.map { it.name }))
        put("notes", JSONArray(value.notes))
    }.toString(2)
}
