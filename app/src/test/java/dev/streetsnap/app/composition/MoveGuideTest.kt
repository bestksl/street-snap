package dev.streetsnap.app.composition

import dev.streetsnap.camera.NormalizedBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveGuideTest {

    /** Square crop of side [s] centred on ([cu], [cv]); callers keep it inside the frame. */
    private fun box(cu: Double = 0.5, cv: Double = 0.5, s: Double = 1.0) =
        NormalizedBoundingBox(
            (cu - s / 2).toFloat(), (cv - s / 2).toFloat(),
            (cu + s / 2).toFloat(), (cv + s / 2).toFloat())

    @Test
    fun cropToTheRightPointsRight() =
        assertEquals(GuideDirection.RIGHT, MoveGuides.fromCrop(box(cu = 0.63, s = 0.74))?.direction)

    @Test
    fun cropToTheLeftPointsLeft() =
        assertEquals(GuideDirection.LEFT, MoveGuides.fromCrop(box(cu = 0.40, s = 0.80))?.direction)

    @Test
    fun cropAbovePointsUp() =
        assertEquals(GuideDirection.UP, MoveGuides.fromCrop(box(cv = 0.36, s = 0.72))?.direction)

    @Test
    fun cropBelowPointsDown() =
        assertEquals(GuideDirection.DOWN, MoveGuides.fromCrop(box(cv = 0.64, s = 0.72))?.direction)

    @Test
    fun diagonalsCombineBothAxes() {
        assertEquals(GuideDirection.UP_RIGHT, MoveGuides.fromCrop(box(cu = 0.64, cv = 0.36, s = 0.72))?.direction)
        assertEquals(GuideDirection.UP_LEFT, MoveGuides.fromCrop(box(cu = 0.36, cv = 0.36, s = 0.72))?.direction)
        assertEquals(GuideDirection.DOWN_RIGHT, MoveGuides.fromCrop(box(cu = 0.64, cv = 0.64, s = 0.72))?.direction)
        assertEquals(GuideDirection.DOWN_LEFT, MoveGuides.fromCrop(box(cu = 0.36, cv = 0.64, s = 0.72))?.direction)
    }

    @Test
    fun panWinsOverDollyLikeTheActionSentenceOrder() {
        // Right offset and a tight crop: arrow points right, the dolly part stays in the text card.
        assertEquals(GuideDirection.RIGHT, MoveGuides.fromCrop(box(cu = 0.63, s = 0.50))?.direction)
    }

    @Test
    fun centredTightCropAsksToMoveIn() =
        assertEquals(GuideDirection.FORWARD, MoveGuides.fromCrop(box(s = 0.80))?.direction)

    @Test
    fun centredNearFullCropShowsNothing() {
        // gap 0.05 < DOLLY_MIN 0.07 and no centre offset: frame it as is.
        assertNull(MoveGuides.fromCrop(box(s = 0.95)))
    }

    @Test
    fun deadZoneEdgesAreInclusive() {
        // Exactly at PAN_MIN the arrow appears (agreement with the sentence planner, >=).
        assertEquals(GuideDirection.RIGHT, MoveGuides.fromCrop(box(cu = 0.56, s = 0.88))?.direction)
        // Just inside the pan dead zone and a near-full scale (gap .06 < DOLLY_MIN): nothing.
        assertNull(MoveGuides.fromCrop(box(cu = 0.53, s = 0.94)))
    }

    @Test
    fun verticalDeadZoneDoesNotBlockHorizontalArrow() =
        assertEquals(GuideDirection.LEFT, MoveGuides.fromCrop(box(cu = 0.40, cv = 0.51, s = 0.80))?.direction)

    @Test
    fun chevronAnglesStep45DegreesClockwiseFromRight() {
        // Display coordinates grow downward, so clockwise on screen means +90° = DOWN.
        val expected = mapOf(
            GuideDirection.RIGHT to 0f,
            GuideDirection.DOWN_RIGHT to 45f,
            GuideDirection.DOWN to 90f,
            GuideDirection.DOWN_LEFT to 135f,
            GuideDirection.LEFT to 180f,
            GuideDirection.UP_LEFT to -135f,
            GuideDirection.UP to -90f,
            GuideDirection.UP_RIGHT to -45f)
        expected.forEach { (direction, angle) -> assertEquals(angle, direction.arrowAngleDeg, 0.001f) }
    }

    @Test
    fun allDirectionsHaveChineseLabels() {
        GuideDirection.entries.forEach {
            assertTrue(it.arrowLabel.isNotBlank())
        }
    }
}
