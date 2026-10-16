package dev.streetsnap.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class WhiteBalanceSupportTest {
    @Test
    fun missingOrEmptyMetadataKeepsAutomaticWhiteBalanceAvailable() {
        assertEquals(listOf(WhiteBalanceMode.AUTO), WhiteBalanceSupport.availableModes(null))
        assertEquals(listOf(WhiteBalanceMode.AUTO), WhiteBalanceSupport.availableModes(intArrayOf()))
    }

    @Test
    fun unsupportedAndManualOffModesDoNotBecomeSelectablePresets() {
        val modes = WhiteBalanceSupport.availableModes(intArrayOf(
            CaptureRequest.CONTROL_AWB_MODE_OFF,
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            99,
        ))

        assertEquals(listOf(WhiteBalanceMode.AUTO, WhiteBalanceMode.DAYLIGHT, WhiteBalanceMode.CLOUDY), modes)
    }

    @Test
    fun presetOrderRemainsStableAcrossVendorMetadataOrdering() {
        val modes = WhiteBalanceSupport.availableModes(intArrayOf(
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
            CaptureRequest.CONTROL_AWB_MODE_SHADE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
        ))

        assertEquals(listOf(
            WhiteBalanceMode.AUTO,
            WhiteBalanceMode.SHADE,
            WhiteBalanceMode.INCANDESCENT,
            WhiteBalanceMode.FLUORESCENT,
        ), modes)
    }
}
