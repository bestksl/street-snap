package dev.streetsnap.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class YuvFrameSamplerTest {
    @Test
    fun rotationAndCropUseTheSamePixelsAsTheViewportMapping() {
        val y = ByteBuffer.wrap(byteArrayOf(16, 16, 16, 16, 16, 40, 80, 16, 16, 120, 200.toByte(), 16, 16, 16, 16, 16))
        val uv = ByteBuffer.wrap(ByteArray(4) { 128.toByte() })
        val planes = listOf(YuvPlane(y, 4, 1), YuvPlane(uv, 2, 1), YuvPlane(uv, 2, 1))
        val original = YuvFrameSampler.copy(4, 4, FrameCrop(1, 1, 3, 3), 0, 384, planes).pixels
        val rotated = YuvFrameSampler.copy(4, 4, FrameCrop(1, 1, 3, 3), 90, 384, planes).pixels
        assertArrayEquals(intArrayOf(original[2], original[0], original[3], original[1]), rotated)
        assertEquals(2, YuvFrameSampler.copy(4, 4, FrameCrop(1, 1, 3, 3), 270, 384, planes).geometry.bitmapWidth)
    }

    @Test
    fun paddedRowsPixelStridesAndBufferOffsetsAreRespected() {
        val y = ByteBuffer.wrap(byteArrayOf(99, 16, 235.toByte(), 0, 0, 235.toByte(), 16, 0, 0)).apply { position(1) }
        val uv = ByteBuffer.wrap(byteArrayOf(99, 128.toByte(), 55, 55)).apply { position(1) }
        val frame = YuvFrameSampler.copy(2, 2, FrameCrop(0, 0, 2, 2), 0, 384,
            listOf(YuvPlane(y, 4, 1), YuvPlane(uv, 3, 2), YuvPlane(uv, 3, 2)))
        assertArrayEquals(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
            0xFFFFFFFF.toInt(), 0xFF000000.toInt()), frame.pixels)
    }

    @Test
    fun downsamplingKeepsAspectAndLimitsMemory() {
        val y = ByteBuffer.wrap(ByteArray(640 * 480) { 120 })
        val uv = ByteBuffer.wrap(ByteArray(320 * 240) { 128.toByte() })
        val frame = YuvFrameSampler.copy(640, 480, FrameCrop(0, 0, 640, 480), 90, 384,
            listOf(YuvPlane(y, 640, 1), YuvPlane(uv, 320, 1), YuvPlane(uv, 320, 1)))
        assertEquals(288, frame.geometry.bitmapWidth)
        assertEquals(384, frame.geometry.bitmapHeight)
        assertEquals(288 * 384, frame.pixels.size)
    }
}
