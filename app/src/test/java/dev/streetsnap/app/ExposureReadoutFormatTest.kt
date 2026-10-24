package dev.streetsnap.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shoulder strip shows the camera's real metered shutter speed, so its formatter must
 * render actual CaptureResult exposure times the way cameras print them.
 */
class ExposureReadoutFormatTest {
    @Test fun subSecondExposuresRenderAsFractions() {
        assertEquals("1/250 s", shutterLabel(4_000_000L))
        assertEquals("1/125 s", shutterLabel(8_000_000L))
        assertEquals("1/60 s", shutterLabel(16_666_667L))
        assertEquals("1/30 s", shutterLabel(33_333_333L))
        assertEquals("1/10 s", shutterLabel(100_000_000L))
        assertEquals("1/2 s", shutterLabel(500_000_000L))
    }

    @Test fun secondScaleExposuresRenderAsDecimals() {
        assertEquals("1.0 s", shutterLabel(1_000_000_000L))
        assertEquals("2.5 s", shutterLabel(2_500_000_000L))
    }
}
