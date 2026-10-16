package dev.streetsnap.color

import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltInLutsTest {
    private val looks = listOf(BuiltInLuts.positive, BuiltInLuts.negative, BuiltInLuts.powderBlue)

    @Test fun authoredLooksKeepTheEntireSdrCubeFiniteAndBounded() {
        for (lut in looks) {
            for (r in 0..16) for (g in 0..16) for (b in 0..16) {
                assertTrue(lut.sample(r / 16f, g / 16f, b / 16f).all { it.isFinite() && it in 0f..1f })
            }
        }
    }

    @Test fun neutralRampRemainsOrderedWithSmallColorCasts() {
        for (lut in looks) {
            var previous = -1f
            for (step in 0..100) {
                val neutral = step / 100f
                val sample = lut.sample(neutral, neutral, neutral)
                val brightness = luma(sample)
                assertTrue("${lut.title} reverses the neutral ramp", brightness >= previous)
                assertTrue("${lut.title} adds excessive neutral color", sample.max() - sample.min() < .055f)
                previous = brightness
            }
        }
    }

    @Test fun positiveHasDeeperShadowsAndStrongerColorThanNegative() {
        val positiveShadow = luma(BuiltInLuts.positive.sample(.15f, .15f, .15f))
        val negativeShadow = luma(BuiltInLuts.negative.sample(.15f, .15f, .15f))
        assertTrue(positiveShadow + .04f < negativeShadow)
        val redPositive = BuiltInLuts.positive.sample(.7f, .35f, .30f)
        val redNegative = BuiltInLuts.negative.sample(.7f, .35f, .30f)
        assertTrue(redPositive.max() - redPositive.min() > redNegative.max() - redNegative.min())
        assertTrue(luma(BuiltInLuts.negative.sample(0f, 0f, 0f)) > .02f)
        assertTrue(luma(BuiltInLuts.negative.sample(1f, 1f, 1f)) < .99f)
    }

    @Test fun powderBlueBrightensAndSoftensBlueWithoutInvertingItsHue() {
        val input = floatArrayOf(.20f, .50f, .90f)
        val result = BuiltInLuts.powderBlue.sample(input[0], input[1], input[2])
        assertTrue(luma(result) > luma(input))
        assertTrue(result.max() - result.min() < (input.max() - input.min()) * .9f)
        assertTrue(result[2] > result[1] && result[1] > result[0])
        // Blue is selectively restrained, while a warm subject keeps a warm channel ordering.
        val warm = BuiltInLuts.powderBlue.sample(.72f, .48f, .36f)
        assertTrue(warm[0] > warm[1] && warm[1] > warm[2])
        assertEquals(.72f - .48f, warm[0] - warm[1], .00001f)
        assertEquals(.48f - .36f, warm[1] - warm[2], .00001f)
    }

    @Test fun powderBluePreservesBlackWhiteAndNeutralColorWhileLiftingMiddleTones() {
        val lut = BuiltInLuts.powderBlue
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), lut.sample(0f, 0f, 0f), .00001f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), lut.sample(1f, 1f, 1f), .00001f)
        val low = lut.sample(.1f, .1f, .1f)
        val middle = lut.sample(.5f, .5f, .5f)
        val high = lut.sample(.9f, .9f, .9f)
        assertTrue(luma(middle) - .5f > luma(low) - .1f)
        assertTrue(luma(middle) - .5f > luma(high) - .9f)
        for (sample in listOf(low, middle, high)) {
            assertEquals(sample[0], sample[1], .00001f)
            assertEquals(sample[1], sample[2], .00001f)
        }
    }

    private fun luma(rgb: FloatArray) = rgb[0] * .2126f + rgb[1] * .7152f + rgb[2] * .0722f
}
