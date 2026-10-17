package dev.streetsnap.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposurePolicyTest {
    @Test
    fun eachModeUsesItsRealIsoAndShutterCombination() {
        val caps = capabilities()
        val manual = ExposurePolicy.select(CaptureSettings(), ExposureMode.MANUAL, caps)
        assertEquals(ExposureMode.MANUAL, manual.exposureMode)
        assertEquals(200, manual.iso)
        assertEquals(8_000_000L, manual.exposureTimeNs)

        val shutter = ExposurePolicy.select(manual, ExposureMode.SHUTTER_PRIORITY, caps)
        assertEquals(ExposureMode.SHUTTER_PRIORITY, shutter.exposureMode)
        assertNull(shutter.iso)
        assertEquals(manual.exposureTimeNs, shutter.exposureTimeNs)

        val program = ExposurePolicy.select(shutter, ExposureMode.PROGRAM, caps)
        assertEquals(ExposureMode.PROGRAM, program.exposureMode)
        assertNull(program.iso)
        assertNull(program.exposureTimeNs)
    }

    @Test
    fun unsupportedShutterPriorityDoesNotFallBackToIgnoredManualShutter() {
        val caps = capabilities(shutterPriority = false)
        assertEquals(listOf(ExposureMode.PROGRAM, ExposureMode.MANUAL), caps.exposureModes)
        assertFalse(ExposurePolicy.isExposureValid(CaptureSettings(exposureTimeNs = 8_000_000L), caps))
        val rejected = runCatching { ExposurePolicy.select(CaptureSettings(), ExposureMode.SHUTTER_PRIORITY, caps) }
        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun automaticOnlyDeviceStillHasAWorkingProgramReset() {
        val caps = capabilities(manual = false, shutterPriority = false)
        assertEquals(listOf(ExposureMode.PROGRAM), caps.exposureModes)
        val reset = ExposurePolicy.reset(CaptureSettings(
            iso = 800, exposureTimeNs = 1_000_000L, exposureCompensationIndex = -3,
        ), supportsAutoFocus = false)
        assertTrue(ExposurePolicy.isExposureValid(reset, caps))
        assertEquals(ExposureMode.PROGRAM, reset.exposureMode)
        assertEquals(0, reset.exposureCompensationIndex)
    }

    @Test
    fun resetClearsExposureAndFocusButKeepsFramingAndWhiteBalance() {
        val before = CaptureSettings(
            focalLength = FocalLength.MM50, iso = 800, exposureTimeNs = 1_000_000L,
            exposureCompensationIndex = 2, autoExposureLocked = true, focusDistanceDiopters = 0.5f,
            whiteBalance = WhiteBalanceMode.DAYLIGHT,
        )
        val reset = ExposurePolicy.reset(before, supportsAutoFocus = true)
        assertNull(reset.iso)
        assertNull(reset.exposureTimeNs)
        assertNull(reset.focusDistanceDiopters)
        assertFalse(reset.autoExposureLocked)
        assertEquals(0, reset.exposureCompensationIndex)
        assertEquals(before.focalLength, reset.focalLength)
        assertEquals(before.whiteBalance, reset.whiteBalance)
    }

    @Test
    fun resetDoesNotPromiseAutofocusOnAManualOnlyLens() {
        val reset = ExposurePolicy.reset(CaptureSettings(focusDistanceDiopters = 0.5f), supportsAutoFocus = false)
        assertEquals(0.5f, reset.focusDistanceDiopters)
    }

    @Test
    fun invalidAndOutOfRangeRestoredExposureIsRejected() {
        val caps = capabilities()
        assertFalse(ExposurePolicy.isExposureValid(CaptureSettings(iso = 200), caps))
        assertFalse(ExposurePolicy.isExposureValid(CaptureSettings(iso = 1, exposureTimeNs = 8_000_000L), caps))
        assertFalse(ExposurePolicy.isExposureValid(CaptureSettings(exposureTimeNs = Long.MAX_VALUE), caps))
        assertTrue(ExposurePolicy.isExposureValid(CaptureSettings(exposureTimeNs = 8_000_000L), caps))
    }

    @Test
    fun enteringAModeClampsInheritedValuesAndReleasesAeLock() {
        val selected = ExposurePolicy.select(CaptureSettings(
            iso = 3200, exposureTimeNs = Long.MAX_VALUE, autoExposureLocked = true,
        ), ExposureMode.MANUAL, capabilities())
        assertEquals(1600, selected.iso)
        assertEquals(1_000_000_000L, selected.exposureTimeNs)
        assertFalse(selected.autoExposureLocked)
    }

    private fun capabilities(manual: Boolean = true, shutterPriority: Boolean = true) = CameraCapabilities(
        cameraId = "test", fixedPhysicalCameraId = null, device = "test", sensorOrientationDegrees = 90,
        nativeEquivalentFocalLengthMm = 24.0, focalLengthOptions = listOf(FocalLength.MM28), maxZoomRatio = 4f,
        exposureCompensationMin = -3, exposureCompensationMax = 3, exposureCompensationStepEv = 1.0,
        supportsManualExposure = manual, isoMin = 50, isoMax = 1600,
        exposureTimeMinNs = 100_000L, exposureTimeMaxNs = 1_000_000_000L,
        supportsManualFocus = true, minimumFocusDistanceDiopters = 5f, focusDistanceCalibration = "approximate",
        supportsAutoExposureLock = true, supportsFocusMetering = true, notes = emptyList(),
        supportsShutterPriority = shutterPriority,
    )
}
