package dev.streetsnap.app.edge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class D1NaFlexTest {
    // Packed RGB literals (alpha is ignored by the patch extractor).
    private val black = 0x000000
    private val white = 0xFFFFFF
    private val red = 0xFF0000
    private val green = 0x00FF00
    private val blue = 0x0000FF

    private val channelCount = 3

    private fun frame(fill: Int = black): IntArray =
        IntArray(D1NaFlex.SIDE * D1NaFlex.SIDE) { fill }

    private fun patchOffset(patchX: Int, patchY: Int, dx: Int = 0, dy: Int = 0): Int =
        ((patchY * D1NaFlex.GRID + patchX) * D1NaFlex.PATCH_DIM) +
            ((dy * D1NaFlex.PATCH + dx) * channelCount)

    private fun rgbAt(patches: FloatArray, index: Int): FloatArray =
        patches.copyOfRange(index, index + 3)

    @Test
    fun rejectsFramesOtherThanFixedSquare() {
        assertThrows(IllegalArgumentException::class.java) {
            D1NaFlex.toPatchPixels(IntArray(16))
        }
        assertThrows(IllegalArgumentException::class.java) {
            D1NaFlex.toPatchPixels(IntArray(D1NaFlex.SIDE * D1NaFlex.SIDE - 1))
        }
    }

    @Test
    fun emitsExpectedTensorShapeAndScalingEndpoints() {
        val dark = D1NaFlex.toPatchPixels(frame(black))
        assertEquals(D1NaFlex.MAX_PATCHES * D1NaFlex.PATCH_DIM, dark.size)
        assertTrue(dark.all { it == -1f })

        val bright = D1NaFlex.toPatchPixels(frame(white))
        assertTrue(bright.all { it == 1f })
    }

    @Test
    fun laysOutPatchesInRowMajorGridWithChannelOrderRGB() {
        val argb = frame()
        // Top-left pixel of the whole image is the top-left pixel of patch (0,0).
        argb[0] = red
        val patches = D1NaFlex.toPatchPixels(argb)
        assertArrayEquals(floatArrayOf(1f, -1f, -1f), rgbAt(patches, patchOffset(0, 0)), 0f)

        // Green pixel one cell right stays inside the same patch, at the next RGB triplet.
        argb[0] = black
        argb[1] = green
        val shifted = D1NaFlex.toPatchPixels(argb)
        assertArrayEquals(floatArrayOf(-1f, 1f, -1f),
            rgbAt(shifted, patchOffset(0, 0, dx = 1)), 0f)
    }

    @Test
    fun pixelsAcrossPatchBoundaryLandInTheNextPatchRow() {
        val argb = frame()
        // x=16 is the first pixel of patch (1,0).
        argb[16] = blue
        val patches = D1NaFlex.toPatchPixels(argb)
        assertArrayEquals(floatArrayOf(-1f, -1f, 1f), rgbAt(patches, patchOffset(1, 0)), 0f)
        // Patch (0,0) is untouched.
        assertArrayEquals(floatArrayOf(-1f, -1f, -1f), rgbAt(patches, patchOffset(0, 0)), 0f)
    }

    @Test
    fun verticalPatchStrideIsOneGridRow() {
        val argb = frame()
        // (0,16) is the first pixel of patch (0,1).
        argb[16 * D1NaFlex.SIDE] = red
        val patches = D1NaFlex.toPatchPixels(argb)
        assertArrayEquals(floatArrayOf(1f, -1f, -1f), rgbAt(patches, patchOffset(0, 1)), 0f)
    }

    @Test
    fun scalesMidGrayToZero() {
        // 128/127.5 - 1 ~= 0.0039, the closest integer gray to the center of [-1,1].
        val gray = 128 or (128 shl 8) or (128 shl 16)
        val patches = D1NaFlex.toPatchPixels(frame(gray))
        assertEquals(0f, patches[0], 0.01f)
    }
}
