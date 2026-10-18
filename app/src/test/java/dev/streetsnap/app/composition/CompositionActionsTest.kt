package dev.streetsnap.app.composition

import dev.streetsnap.camera.NormalizedBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionActionsTest {

    /**
     * A square crop of side [s] centred on ([cu], [cv]) in normalized-frame units.
     * Callers keep it inside the frame (a real offset crop is necessarily tighter).
     */
    private fun box(cu: Double = 0.5, cv: Double = 0.5, s: Double = 1.0) =
        NormalizedBoundingBox(
            (cu - s / 2).toFloat(), (cv - s / 2).toFloat(),
            (cu + s / 2).toFloat(), (cv + s / 2).toFloat())

    @Test
    fun rightHalfStepPansRightAndMovesIn() {
        val move = CompositionActions.plan(box(cu = 0.63, s = 0.74),
            CompositionStyle.THIRDS, 0.68f, 0.5f)!!
        assertEquals(listOf(MoveDirection.RIGHT to 2, MoveDirection.FORWARD to 3),
            move.parts.map { it.direction to it.step })
        assertEquals("向右平移半步，再靠近一步，让主体落在右三分线", move.sentence)
    }

    @Test
    fun leftSmallStepPansLeftWithLeftThird() {
        val move = CompositionActions.plan(box(cu = 0.40, s = 0.80),
            CompositionStyle.THIRDS, 0.32f, 0.5f)!!
        assertEquals("向左平移一点，靠近半步，让主体落在左三分线", move.sentence)
    }

    @Test
    fun farShiftIsAFullStepAndCentresTheSubject() {
        val move = CompositionActions.plan(box(cu = 0.72, s = 0.56), CompositionStyle.CENTERED)!!
        assertEquals(MoveDirection.RIGHT to 3, move.parts.first().direction to move.parts.first().step)
        assertTrue(move.sentence.startsWith("向右平移一步"))
        assertEquals("让主体居中", move.targetPhrase)
    }

    @Test
    fun smallCentreOffsetStaysInsidePanDeadZone() {
        val move = CompositionActions.plan(box(cu = 0.55, s = 0.90), CompositionStyle.THIRDS)!!
        assertTrue("only the scale move survives, got ${move.parts}",
            move.parts.none { it.direction == MoveDirection.LEFT || it.direction == MoveDirection.RIGHT })
        assertNull(CompositionActions.plan(box(cv = 0.55, s = 0.90), CompositionStyle.THIRDS)!!
            .parts.firstOrNull { it.direction == MoveDirection.UP || it.direction == MoveDirection.DOWN })
        // A 6% scale difference is below the dolly dead zone: a near-full neutral frame says nothing.
        assertNull(CompositionActions.plan(box(s = 0.94), CompositionStyle.THIRDS))
    }

    @Test
    fun offsetAtDeadZoneEdgeFires() {
        val move = CompositionActions.plan(box(cu = 0.56, s = 0.88), CompositionStyle.DIAGONAL)!!
        assertEquals(1, move.parts.first { it.direction == MoveDirection.RIGHT }.step)
    }

    @Test
    fun verticalMovesRaiseAndLowerTheCamera() {
        val up = CompositionActions.plan(box(cv = 0.37, s = 0.74), CompositionStyle.DIAGONAL)!!
        assertEquals(MoveDirection.UP to 2,
            up.parts.first { it.direction == MoveDirection.UP }.let { it.direction to it.step })
        assertTrue(up.sentence.contains("机位抬高半步"))
        val down = CompositionActions.plan(box(cv = 0.63, s = 0.74), CompositionStyle.DIAGONAL)!!
        assertTrue(down.sentence.contains("机位降低半步"))
    }

    @Test
    fun scaleDifferenceIsAlwaysForwardInThreeBands() {
        val near = CompositionActions.plan(box(s = 0.88), CompositionStyle.TRIANGLE)!!
        assertEquals(1, near.parts.single().step)
        assertTrue(near.sentence.contains("靠近一点"))
        val half = CompositionActions.plan(box(s = 0.80), CompositionStyle.TRIANGLE)!!
        assertEquals(2, half.parts.single().step)
        assertTrue(half.sentence.contains("靠近半步"))
        val far = CompositionActions.plan(box(s = 0.68), CompositionStyle.TRIANGLE)!!
        assertEquals(3, far.parts.single().step)
        assertTrue(far.sentence.startsWith("再靠近一步"))
    }

    @Test
    fun multiAxisMoveOrdersPanTiltDolly() {
        val move = CompositionActions.plan(box(cu = 0.63, cv = 0.40, s = 0.74),
            CompositionStyle.LEADING_LINES)!!
        assertEquals(listOf(
            MoveDirection.RIGHT, MoveDirection.UP, MoveDirection.FORWARD),
            move.parts.map { it.direction })
        assertEquals("向右平移半步，机位抬高一点，再靠近一步", move.sentence)
        assertNull(move.targetPhrase)
    }

    @Test
    fun thirdsAnchorPhraseCoversAllFourLines() {
        assertEquals("让主体落在左三分线",
            CompositionActions.plan(box(cu = 0.60, s = 0.80),
                CompositionStyle.THIRDS, 0.32f, 0.5f)!!.targetPhrase)
        assertEquals("让主体落在右三分线",
            CompositionActions.plan(box(cu = 0.40, s = 0.80),
                CompositionStyle.THIRDS, 0.68f, 0.5f)!!.targetPhrase)
        assertEquals("让主体落在上三分线",
            CompositionActions.plan(box(cv = 0.60, s = 0.80),
                CompositionStyle.THIRDS, 0.5f, 0.34f)!!.targetPhrase)
        assertEquals("让主体落在下三分线",
            CompositionActions.plan(box(cv = 0.40, s = 0.80),
                CompositionStyle.THIRDS, 0.5f, 0.70f)!!.targetPhrase)
        // A centred energy mass is not near any third line, so no anchor is invented.
        assertNull(CompositionActions.plan(box(cu = 0.60, s = 0.80),
            CompositionStyle.THIRDS, 0.5f, 0.5f)!!.targetPhrase)
    }

    @Test
    fun stylesWhoseCopyIsAlreadyAMoveAbstain() {
        // A shifted, tight crop that would otherwise demand a strong move.
        val shifted = box(cu = 0.35, cv = 0.55, s = 0.66)
        for (style in listOf(CompositionStyle.EDGE_CUT, CompositionStyle.TOO_TIGHT,
            CompositionStyle.HORIZON_SPLIT, CompositionStyle.CONSERVATIVE)) {
            assertNull(style.name, CompositionActions.plan(shifted, style))
        }
    }

    @Test
    fun fullFrameNeutralCropGivesNoMoveForAnyStyle() {
        for (style in CompositionStyle.entries) {
            assertEquals(style.name, null, CompositionActions.plan(box(), style)?.sentence)
        }
    }
}
