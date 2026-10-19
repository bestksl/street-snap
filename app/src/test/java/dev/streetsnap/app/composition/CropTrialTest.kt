package dev.streetsnap.app.composition

import dev.streetsnap.camera.NormalizedBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropTrialTest {

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        NormalizedBoundingBox(left, top, right, bottom)

    @Test
    fun fullFrameHasNothingToTrial() {
        assertNull(CropTrial.from(box(0f, 0f, 1f, 1f)))
    }

    @Test
    fun withinToleranceIsTreatedAsFullFrame() {
        assertNull(CropTrial.from(box(0.03f, 0.03f, 0.97f, 0.97f)))
    }

    @Test
    fun centredSquareZoomsUniformlyAndStaysCentred() {
        val trial = CropTrial.from(box(0.04f, 0.04f, 0.96f, 0.96f))!!
        assertEquals(1f / 0.92f, trial.scale, 1e-6f)
        assertEquals(0.5f, trial.centerU, 1e-6f)
        assertEquals(0.5f, trial.centerV, 1e-6f)
    }

    @Test
    fun offCentreCropMovesItsCentreIntoView() {
        // The current AVD scene's top suggestion: left 0, top 0.08, right 0.92, bottom 1.
        val trial = CropTrial.from(box(0f, 0.08f, 0.92f, 1f))!!
        assertEquals(1f / 0.92f, trial.scale, 1e-6f)
        assertEquals(0.46f, trial.centerU, 1e-6f)
        assertEquals(0.54f, trial.centerV, 1e-6f)
    }

    @Test
    fun coverScaleFollowsTheShorterDimension() {
        // Wide, short crop: height 0.5 demands the stronger scale.
        val trial = CropTrial.from(box(0.05f, 0.25f, 0.95f, 0.75f))!!
        assertEquals(2f, trial.scale, 1e-6f)
    }

    @Test
    fun tightestCropBandStaysBounded() {
        // Tightest suggestion band is 0.68; zoom never exceeds ~1.47x.
        val trial = CropTrial.from(box(0.16f, 0.16f, 0.84f, 0.84f))!!
        assertEquals(1f / 0.68f, trial.scale, 1e-4f)
    }

    @Test
    fun narrowStripScalesOnItsThinAxis() {
        // NormalizedBoundingBox's contract forbids zero/inverted sizes, so the only
        // legal degenerate shape is a thin strip: width 0.1 demands the 10x scale.
        val trial = CropTrial.from(box(0.45f, 0.1f, 0.55f, 0.9f))!!
        assertEquals(10f, trial.scale, 1e-4f)
        assertEquals(0.5f, trial.centerU, 1e-6f)
    }
}
