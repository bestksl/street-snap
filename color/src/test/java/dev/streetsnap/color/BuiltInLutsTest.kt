package dev.streetsnap.color

import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltInLutsTest {
    private val looks = listOf(
        BuiltInLuts.positive, BuiltInLuts.negative, BuiltInLuts.powderBlue, BuiltInLuts.mono,
        BuiltInLuts.sunny, BuiltInLuts.classicChrome, BuiltInLuts.classicNegative,
        BuiltInLuts.nostalgicNegative, BuiltInLuts.acros, BuiltInLuts.lumixNatural, BuiltInLuts.lumixMono,
    )

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
                assertTrue("${lut.title} reverses the neutral ramp at $step", brightness + .000001f >= previous)
                val castLimit = if (lut === BuiltInLuts.sunny) .06f else .055f
                assertTrue("${lut.title} adds excessive neutral color", sample.max() - sample.min() < castLimit)
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
        assertTrue(warm[0] - warm[2] > .28f)
    }

    @Test fun powderBlueKeepsEndpointsAndLiftsMidtonesWithControlledCoolShadows() {
        val lut = BuiltInLuts.powderBlue
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), lut.sample(0f, 0f, 0f), .00001f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), lut.sample(1f, 1f, 1f), .00001f)
        val low = lut.sample(.1f, .1f, .1f)
        val middle = lut.sample(.5f, .5f, .5f)
        val high = lut.sample(.9f, .9f, .9f)
        assertTrue(luma(middle) - .5f > luma(low) - .1f)
        assertTrue(luma(middle) - .5f > luma(high) - .9f)
        assertTrue(low[2] > low[1] && low[1] > low[0])
        assertTrue(middle[2] - middle[0] in .015f.. .04f)
        assertEquals(high[0], high[2], .005f)
    }

    @Test fun powderBlueRespondsToMutedNavyAndDoesNotNeedAnAlreadyVividSky() {
        val navy = floatArrayOf(.16f, .19f, .25f)
        val result = BuiltInLuts.powderBlue.sample(navy[0], navy[1], navy[2])
        assertTrue(luma(result) - luma(navy) > .05f)
        assertTrue(result[2] > result[1] && result[1] > result[0])
        // Cyan/blue separation from red must survive the midtone lift.
        assertTrue(result[1] - result[0] > navy[1] - navy[0])
        assertTrue(result[2] - result[0] > .09f)
    }

    @Test fun fujiStyleChoicesHaveDistinctMeasuredTonalDirections() {
        val shadow = floatArrayOf(.20f, .20f, .20f)
        val ccShadow = luma(BuiltInLuts.classicChrome.sample(shadow[0], shadow[1], shadow[2]))
        assertTrue(ccShadow < .19f)
        val red = floatArrayOf(.70f, .30f, .23f)
        val ccRed = BuiltInLuts.classicChrome.sample(red[0], red[1], red[2])
        assertTrue(ccRed.max() - ccRed.min() < .38f)
        val ncShadow = luma(BuiltInLuts.classicNegative.sample(.20f, .20f, .20f))
        val ncHighlight = luma(BuiltInLuts.classicNegative.sample(.80f, .80f, .80f))
        assertTrue(ncHighlight - ncShadow > .67f)
        val naHighlight = BuiltInLuts.nostalgicNegative.sample(.75f, .75f, .75f)
        assertTrue(naHighlight[0] > naHighlight[1] && naHighlight[1] > naHighlight[2])
        assertTrue(naHighlight[0] - naHighlight[2] > .03f)
        assertTrue(luma(BuiltInLuts.nostalgicNegative.sample(.20f, .20f, .20f)) > .20f)
    }

    @Test fun threeMonochromeLooksAreNeutralAndKeepDifferentShadowSeparation() {
        for (lut in listOf(BuiltInLuts.mono, BuiltInLuts.acros, BuiltInLuts.lumixMono)) {
            for (rgb in listOf(floatArrayOf(.7f,.3f,.1f), floatArrayOf(.1f,.2f,.9f))) {
                val value = lut.sample(rgb[0], rgb[1], rgb[2])
                assertEquals(value[0], value[1], .00001f)
                assertEquals(value[1], value[2], .00001f)
            }
        }
        val hard = luma(BuiltInLuts.mono.sample(.20f, .20f, .20f))
        val acros = luma(BuiltInLuts.acros.sample(.20f, .20f, .20f))
        val lumix = luma(BuiltInLuts.lumixMono.sample(.20f, .20f, .20f))
        assertTrue(hard + .04f < acros)
        assertTrue(acros + .01f < lumix)
    }

    @Test fun lumixNaturalKeepsNeutralBalanceAndGentleColor() {
        val gray = BuiltInLuts.lumixNatural.sample(.5f, .5f, .5f)
        assertEquals(gray[0], gray[1], .00001f)
        assertEquals(gray[1], gray[2], .00001f)
        assertTrue(gray[0] in .51f.. .53f)
        val red = BuiltInLuts.lumixNatural.sample(.7f, .3f, .2f)
        assertTrue(red[0] - red[2] in .45f.. .49f)
    }

    private fun luma(rgb: FloatArray) = rgb[0] * .2126f + rgb[1] * .7152f + rgb[2] * .0722f
}
