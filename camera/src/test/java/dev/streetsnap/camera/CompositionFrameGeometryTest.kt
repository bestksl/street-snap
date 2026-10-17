package dev.streetsnap.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionFrameGeometryTest {
    @Test
    fun uprightCoordinatesMapIntoTheActualSharedViewportCrop() {
        val geometry = geometry(90)
        assertPoint(CompositionPoint(.125f, .875f), geometry.viewportToSource(CompositionPoint(0f, 0f)))
        assertPoint(CompositionPoint(.875f, .125f), geometry.viewportToSource(CompositionPoint(1f, 1f)))
        assertPoint(CompositionPoint(.5f, .5f), geometry.viewportToSource(CompositionPoint(.5f, .5f)))
    }

    @Test
    fun roundTripAcrossEveryDeviceRotationPreservesPositions() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val geometry = geometry(rotation)
            for (point in listOf(CompositionPoint(0f, 0f), CompositionPoint(1f, 1f),
                CompositionPoint(.23f, .67f), CompositionPoint(.5f, .5f))) {
                assertPoint(point, geometry.sourceToViewport(geometry.viewportToSource(point)))
            }
        }
    }

    private fun geometry(rotation: Int) = CompositionFrameGeometry(640, 480,
        FrameCrop(80, 60, 560, 420), rotation,
        if (rotation % 180 == 0) 384 else 288, if (rotation % 180 == 0) 288 else 384)

    private fun assertPoint(expected: CompositionPoint, actual: CompositionPoint) {
        assertEquals(expected.x, actual.x, .00001f)
        assertEquals(expected.y, actual.y, .00001f)
    }
}
