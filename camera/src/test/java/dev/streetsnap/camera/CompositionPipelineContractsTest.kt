package dev.streetsnap.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CompositionPipelineContractsTest {
    @Test
    fun smootherBlendsAdjacentBoxesAndResetsAfterGap() {
        val smoother = BoundingBoxSmoother(amount = .5f, resetAfterMs = 500)
        val first = box(.1f, .1f, .5f, .5f)
        val second = box(.3f, .3f, .7f, .7f)

        assertSame(first, smoother.update(first, 100))
        val blended = smoother.update(second, 200)
        assertEquals(.2f, blended.left, .0001f)
        assertEquals(.6f, blended.right, .0001f)
        assertSame(second, smoother.update(second, 900))
    }

    @Test
    fun primarySubjectUsesConfidenceThresholdAndKeepsTheStrongest() {
        val weak = SubjectDetection(box(.1f, .1f, .2f, .2f), .3f, "person", 1)
        val strong = SubjectDetection(box(.2f, .2f, .8f, .8f), .9f, "person", 1)
        assertEquals(strong, selectPrimarySubject(listOf(weak, strong), .35f))
        assertEquals(null, selectPrimarySubject(listOf(weak), .35f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun boxesOutsideTheNormalizedFrameAreRejected() {
        box(-.1f, .1f, .5f, .5f)
    }

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        NormalizedBoundingBox(left, top, right, bottom)
}
