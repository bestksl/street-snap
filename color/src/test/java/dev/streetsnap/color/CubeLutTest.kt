package dev.streetsnap.color

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader

class CubeLutTest {
    @Test fun identityInterpolatesBetweenLatticePointsAndClampsEdges() {
        val lut = CubeLut.parse(StringReader(cube(17)))
        assertEquals(17, lut.size)
        assertEquals("Test LUT", lut.title)
        assertArrayEquals(floatArrayOf(.213f, .547f, .897f), lut.sample(.213f, .547f, .897f), .00001f)
        assertArrayEquals(floatArrayOf(0f, 1f, .5f), lut.sample(-2f, 4f, .5f), .00001f)
    }

    @Test fun cube33UsesRedFastestAndKeepsChannelsIndependent() {
        val lut = CubeLut.parse(StringReader(cube(33) { r, g, b -> floatArrayOf(b, r, g) }))
        assertEquals(33, lut.size)
        assertArrayEquals(floatArrayOf(.91f, .123f, .456f), lut.sample(.123f, .456f, .91f), .00001f)
        val rgba = lut.toRgbaBuffer()
        assertEquals(0f, rgba.get(4), 0f) // red increased; output red (input blue) did not.
        assertEquals(1f / 32f, rgba.get(5), .00001f)
        assertEquals(1f, rgba.get(7), 0f)
    }

    @Test fun domainScalesEachChannelBeforeInterpolation() {
        val text = cube(17).replace("LUT_3D_SIZE 17", "DOMAIN_MIN 0.1 0.2 0.3\nDOMAIN_MAX 0.9 0.8 0.7\nLUT_3D_SIZE 17")
        val lut = CubeLut.parse(StringReader(text))
        assertArrayEquals(floatArrayOf(.25f, .5f, .75f), lut.sample(.3f, .5f, .6f), .00001f)
        val min = lut.domainMin
        min[0] = 99f
        assertEquals(.1f, lut.domainMin[0], 0f)
    }

    @Test fun supportsCommentsBomAndWindowsLines() {
        val text = "\uFEFF# Project original\r\n" + cube(17).replace("\n", " # comment\r\n")
        val lut = CubeLut.parse(StringReader(text))
        assertArrayEquals(floatArrayOf(.2f, .4f, .6f), lut.sample(.2f, .4f, .6f), .00001f)
        val titleWithHash = CubeLut.parse(StringReader(cube(17).replace("Test LUT", "LUT #1")))
        assertEquals("LUT #1", titleWithHash.title)
    }

    @Test fun rejectsTruncatedExtraAndMisorderedTables() {
        val valid = cube(17)
        rejects(valid.substringBeforeLast("\n").substringBeforeLast("\n"))
        rejects(valid + "0 0 0\n")
        rejects("0 0 0\n" + valid)
        rejects(valid + "DOMAIN_MIN 0 0 0\n")
        rejects(valid.replace("LUT_3D_SIZE 17", "LUT_3D_SIZE 17\nLUT_3D_SIZE 17"))
        rejects(valid.replace("LUT_3D_SIZE 17", "LUT_3D_SIZE 65"))
    }

    @Test fun rejectsInvalidValuesDomainsAndUnsupportedTables() {
        val valid = cube(17)
        rejects(valid.replaceFirst("0.0 0.0 0.0", "NaN 0 0"))
        rejects(valid.replaceFirst("0.0 0.0 0.0", "Infinity 0 0"))
        rejects(valid.replaceFirst("0.0 0.0 0.0", "1.1 0 0"))
        rejects(valid.replaceFirst("0.0 0.0 0.0", "0 0"))
        rejects(valid.replaceFirst("0.0 0.0 0.0", "0 0 0 0"))
        rejects("LUT_1D_SIZE 17\n" + valid)
        rejects("DOMAIN_MIN 1 0 0\n" + valid)
        rejects("DOMAIN_MAX 0 1 1\n" + valid)
        rejects("DOMAIN_MIN 0 0 0\nDOMAIN_MIN 0 0 0\n" + valid)
        rejects("#" + "x".repeat(5000))
    }

    @Test fun boundsTotalInputEvenWhenAllLinesAreComments() {
        val tooLarge = "# " + "x".repeat(1000) + "\n"
        rejects(tooLarge.repeat(4200))
    }

    @Test fun originalAndProjectPresetsAreValidSdr() {
        for (lut in listOf(BuiltInLuts.original, BuiltInLuts.mono, BuiltInLuts.sunny)) {
            val sample = lut.sample(.34f, .62f, .91f)
            assertEquals(3, sample.size)
            check(sample.all { it.isFinite() && it in 0f..1f })
        }
        val mono = BuiltInLuts.mono.sample(.34f, .62f, .91f)
        assertEquals(mono[0], mono[1], .00001f)
        assertEquals(mono[1], mono[2], .00001f)
    }

    private fun rejects(text: String) {
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse(StringReader(text)) }
    }

    private fun cube(size: Int, transform: (Float, Float, Float) -> FloatArray = { r, g, b -> floatArrayOf(r, g, b) }): String =
        buildString {
            append("TITLE \"Test LUT\"\nLUT_3D_SIZE $size\n")
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                val color = transform(r.toFloat() / (size - 1), g.toFloat() / (size - 1), b.toFloat() / (size - 1))
                append(color.joinToString(" ")).append('\n')
            }
        }
}
