package dev.streetsnap.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceDetectPolicyTest {
    // 4000x3000 is a typical rear-sensor active array: landscape pixels, 90 degree mount.
    private val arrayW = 4000
    private val arrayH = 3000

    private fun box(
        left: Int, top: Int, right: Int, bottom: Int, orientation: Int,
    ): NormalizedBoundingBox? = FaceDetectPolicy.normalizeFace(
        left, top, right, bottom, arrayW, arrayH, orientation,
    )

    @Test
    fun selectsFullOverSimpleAndNullWhenUnavailable() {
        assertEquals(2, FaceDetectPolicy.selectMode(intArrayOf(0, 1, 2)))
        assertEquals(2, FaceDetectPolicy.selectMode(intArrayOf(2)))
        assertEquals(1, FaceDetectPolicy.selectMode(intArrayOf(0, 1)))
        assertEquals(1, FaceDetectPolicy.selectMode(intArrayOf(1)))
        assertNull(FaceDetectPolicy.selectMode(intArrayOf(0)))
        assertNull(FaceDetectPolicy.selectMode(intArrayOf()))
        assertNull(FaceDetectPolicy.selectMode(null))
    }

    @Test
    fun modeLabelDistinguishesFullSimpleAndUnsupported() {
        assertEquals("完整（含眼部点位）", FaceDetectPolicy.modeLabel(2))
        assertEquals("基础（人脸框）", FaceDetectPolicy.modeLabel(1))
        assertEquals("不支持", FaceDetectPolicy.modeLabel(0))
        assertEquals("不支持", FaceDetectPolicy.modeLabel(null))
    }

    @Test
    fun rotatesSensorRect90DegreesIntoPortraitNormalizedSpace() {
        // Sensor rect [0.25..0.50] x [0.20..0.50]; 90 degree mount maps (x,y) -> (y/h, 1-x/w).
        val b = box(1000, 600, 2000, 1500, 90)
        assertEquals(NormalizedBoundingBox(0.2f, 0.5f, 0.5f, 0.75f), b)
    }

    @Test
    fun rotatesSensorRect270DegreesIntoPortraitNormalizedSpace() {
        // Same rect; 270 degree mount maps (x,y) -> (1-y/h, x/w).
        val b = box(1000, 600, 2000, 1500, 270)
        assertEquals(NormalizedBoundingBox(0.5f, 0.25f, 0.8f, 0.5f), b)
    }

    @Test
    fun passesThroughZeroAndFlipsAt180Degrees() {
        // 0 degrees: plain normalization of the landscape rect.
        assertEquals(
            NormalizedBoundingBox(0.25f, 0.2f, 0.5f, 0.5f),
            box(1000, 600, 2000, 1500, 0),
        )
        // 180 degrees: both axes mirrored.
        assertEquals(
            NormalizedBoundingBox(0.5f, 0.5f, 0.75f, 0.8f),
            box(1000, 600, 2000, 1500, 180),
        )
    }

    @Test
    fun clampsOutOfBoundsRectsToTheActiveArray() {
        // HAL rectangles may extend past the active array on some devices.
        assertEquals(
            NormalizedBoundingBox(0f, 0f, 1f, 1f),
            box(-100, -100, 4500, 3300, 0),
        )
    }

    @Test
    fun rejectsDegenerateRectsUnsupportedOrientationAndEmptyArray() {
        // Zero width after clamping (rect starts past the array edge).
        assertNull(box(4100, 0, 4500, 3000, 0))
        // Zero height.
        assertNull(box(0, 3100, 4000, 3400, 0))
        // Negative/non-cardinal sensor orientations are not supported geometries.
        assertNull(box(1000, 600, 2000, 1500, 45))
        assertNull(box(1000, 600, 2000, 1500, -90))
        // Empty active array.
        assertNull(
            FaceDetectPolicy.normalizeFace(0, 0, 10, 10, 0, 0, 90),
        )
    }

    @Test
    fun primaryBoxPicksTheLargestFaceAndNullForNoFaces() {
        val small = NormalizedBoundingBox(0.1f, 0.1f, 0.2f, 0.2f)
        val large = NormalizedBoundingBox(0.3f, 0.3f, 0.9f, 0.9f)
        val medium = NormalizedBoundingBox(0.0f, 0.0f, 0.5f, 0.5f)
        assertEquals(large, FaceDetectPolicy.primaryBox(listOf(small, large, medium)))
        assertNull(FaceDetectPolicy.primaryBox(emptyList()))
    }
}
