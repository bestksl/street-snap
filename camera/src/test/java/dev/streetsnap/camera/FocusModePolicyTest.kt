package dev.streetsnap.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusModePolicyTest {
    @Test
    fun afMfRoundTripRestoresRememberedManualDistanceIncludingInfinity() {
        for (remembered in listOf(0f, 0.5f, 3f)) {
            var current: Float? = null
            repeat(3) {
                current = toggle(current, remembered)
                assertEquals(remembered, current)
                current = toggle(current, remembered)
                assertNull(current)
            }
        }
    }

    @Test
    fun persistedDistanceIsClampedToTheNewCameraLensRange() {
        assertEquals(3f, toggle(null, 12f))
        assertEquals(0f, toggle(null, -2f))
        assertEquals(0f, toggle(null, Float.NaN))
        assertEquals(0f, toggle(null, Float.POSITIVE_INFINITY))
    }

    @Test
    fun unsupportedTransitionsKeepTheCurrentMode() {
        assertNull(FocusModePolicy.nextDistance(null, 1f, 3f, supportsManual = false, supportsAuto = true))
        assertEquals(1f, FocusModePolicy.nextDistance(1f, 1f, 3f, supportsManual = true, supportsAuto = false))
        for (distance in listOf(null, 0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(FocusModePolicy.nextDistance(null, 1f, distance, supportsManual = true, supportsAuto = true))
        }
    }

    @Test
    fun adjustableLensWithAfOffAndDistanceRequestSupportsManualFocus() {
        // A LIMITED camera can expose lens control without the MANUAL_SENSOR exposure capability.
        assertTrue(FocusModePolicy.supportsManualFocus(3f, intArrayOf(
            CaptureRequest.CONTROL_AF_MODE_OFF, CaptureRequest.CONTROL_AF_MODE_AUTO,
        ), acceptsFocusDistance = true))
    }

    @Test
    fun fixedLensOrUnavailableDistanceRequestDoesNotOfferManualFocus() {
        val afOff = intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF)
        for (distance in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(FocusModePolicy.supportsManualFocus(distance, afOff, acceptsFocusDistance = true))
        }
        assertFalse(FocusModePolicy.supportsManualFocus(3f, afOff, acceptsFocusDistance = false))
        assertFalse(FocusModePolicy.supportsManualFocus(3f,
            intArrayOf(CaptureRequest.CONTROL_AF_MODE_AUTO), acceptsFocusDistance = true))
    }

    private fun toggle(current: Float?, remembered: Float): Float? = FocusModePolicy.nextDistance(
        currentDistance = current,
        rememberedDistance = remembered,
        maximumDiopters = 3f,
        supportsManual = true,
        supportsAuto = true,
    )
}
