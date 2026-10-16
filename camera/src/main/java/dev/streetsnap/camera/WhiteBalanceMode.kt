package dev.streetsnap.camera

import android.hardware.camera2.CaptureRequest

/** Native camera presets, not inferred Kelvin temperatures. */
enum class WhiteBalanceMode(val displayName: String, internal val camera2Mode: Int) {
    AUTO("自动", CaptureRequest.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("日光", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("阴天", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("阴影", CaptureRequest.CONTROL_AWB_MODE_SHADE),
    INCANDESCENT("白炽灯", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("荧光灯", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
}

internal object WhiteBalanceSupport {
    /** AUTO is the baseline; expose other presets only when the selected sensor reports them. */
    fun availableModes(camera2Modes: IntArray?): List<WhiteBalanceMode> {
        val supported = camera2Modes?.toSet().orEmpty()
        return WhiteBalanceMode.entries.filter {
            it == WhiteBalanceMode.AUTO || it.camera2Mode in supported
        }
    }
}
